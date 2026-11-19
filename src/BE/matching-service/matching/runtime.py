from contextlib import ExitStack
import json
import logging
from threading import Event, Thread
import time

from confluent_kafka import Consumer, Producer
from sqlalchemy import select, text

from .db import Outbox

log = logging.getLogger(__name__)


def publish(producer, topic, key, payload):
    """Wait for broker acknowledgement. Uncertain delivery keeps the outbox row."""
    outcomes = []
    producer.produce(topic, key=key.encode(),
        value=json.dumps(payload, separators=(",", ":")).encode(),
        on_delivery=lambda error, message: outcomes.append(error))
    if producer.flush(5) or not outcomes or outcomes[0] is not None:
        raise RuntimeError("KAFKA_DELIVERY_UNCONFIRMED")


class Runtime:
    def __init__(self, workflow, settings):
        self.workflow, self.settings = workflow, settings
        self.stop = Event()
        self.failed = False
        self.threads = []
        self.stack = ExitStack()

    def __enter__(self):
        self.lock_connection = self.stack.enter_context(self.workflow.db.single_instance())
        for target in (self.consume, self.work, self.deliver):
            thread = Thread(target=self.guard, args=(target,), name=target.__name__, daemon=True)
            self.threads.append(thread)
            thread.start()
        return self

    def __exit__(self, *args):
        self.stop.set()
        for thread in self.threads:
            thread.join(timeout=10)
        # Never release the ownership lock while a worker may still issue mutations.
        if any(thread.is_alive() for thread in self.threads):
            raise RuntimeError("Worker did not stop; terminate process before starting another runtime")
        self.workflow.driver.close()
        self.stack.close()

    def guard(self, target):
        try:
            target()
        except Exception:
            self.failed = True
            self.stop.set()
            log.exception("Runtime component stopped: %s", target.__name__)

    def consume(self):
        config = self.settings.kafka_config()
        config.update({"group.id": self.settings.consumer_group,
            "auto.offset.reset": "earliest", "enable.auto.commit": False,
            "enable.auto.offset.store": False, "max.poll.interval.ms": 300000,
            "allow.auto.create.topics": False})
        consumer = Consumer(config)
        consumer.subscribe([self.settings.trip_topic])
        try:
            while not self.stop.is_set():
                msg = consumer.poll(1)
                if msg is None:
                    continue
                if msg.error():
                    log.error("Kafka consume error: %s", msg.error().code())
                    self.stop.wait(1)
                    continue
                # On DB/commit failure, terminate this consumer. Never continue to a
                # later offset after failing to persist an earlier message.
                self.workflow.accept_record(msg.topic(), msg.partition(), msg.offset(), msg.key(), msg.value() or b"")
                consumer.commit(message=msg, asynchronous=False)
        finally:
            consumer.close()

    def work(self):
        last_check = 0
        while not self.stop.is_set():
            if time.time() - last_check > 2:
                # If the session owning the advisory lock dies, stop all workers.
                self.lock_connection.execute(text("SELECT 1"))
                self.lock_connection.commit()
                last_check = time.time()
            # Bound ingress processing to avoid starving the persisted workflow.
            for _ in range(20):
                if not self.workflow.process_inbox():
                    break
            self.workflow.step()
            self.stop.wait(0.1)

    def deliver(self):
        config = self.settings.kafka_config()
        config.update({"enable.idempotence": True, "acks": "all",
                       "message.timeout.ms": 5000})
        producer = Producer(config)
        while not self.stop.is_set():
            with self.workflow.db.session() as session:
                row = session.scalar(select(Outbox).where(Outbox.published == False)
                                     .order_by(Outbox.id).limit(1))
            if row is None:
                self.stop.wait(0.2)
                continue
            try:
                publish(producer, row.topic, row.key, row.payload)
                with self.workflow.db.session.begin() as session:
                    session.get(Outbox, row.id).published = True
            except Exception:
                # At-least-once: a successful publish followed by a crash can repeat
                # the SAME event_id. Keep order by retrying this row before others.
                log.warning("Outbox retry: id=%s", row.id)
                self.stop.wait(1)
        producer.flush(5)
