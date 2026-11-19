import asyncio
import logging

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer, TopicPartition
from aiokafka.errors import CommitFailedError

from matching_service.domain.events import IncomingRecord

log = logging.getLogger(__name__)


class KafkaTransport:
    def __init__(self, settings, handlers, repository):
        self.settings, self.handlers, self.repository = settings, handlers, repository
        self.producer = self.consumer = None
        self.ready = False

    async def process(self, record):
        try:
            await self.handlers.handle(record)
        except Exception as exc:
            # A DB error while writing the failure propagates, so the original
            # Kafka offset cannot be acknowledged without a durable handoff.
            await self.repository.record_failure(record, type(exc).__name__)
            log.warning("event_deferred", extra={"code": type(exc).__name__, "offset": record.offset})

    async def publish(self, topic, key, value):
        if not self.ready or self.producer is None:
            raise RuntimeError("Kafka is not ready")
        await asyncio.wait_for(self.producer.send_and_wait(topic, key=key, value=value), timeout=10)

    async def run(self):
        while True:
            producer = AIOKafkaProducer(
                bootstrap_servers=self.settings.kafka_bootstrap_servers,
                client_id=self.settings.service_name,
                enable_idempotence=True,
                max_request_size=131072,
                request_timeout_ms=10000,
            )
            consumer = AIOKafkaConsumer(
                self.settings.kafka_trip_topic,
                bootstrap_servers=self.settings.kafka_bootstrap_servers,
                group_id=self.settings.kafka_consumer_group,
                enable_auto_commit=False,
                auto_offset_reset="earliest",
                max_poll_records=1,
                max_poll_interval_ms=300000,
            )
            self.producer, self.consumer = producer, consumer
            try:
                await producer.start()
                await consumer.start()
                for topic in (self.settings.kafka_trip_topic, self.settings.kafka_matching_topic):
                    if not await asyncio.wait_for(producer.partitions_for(topic), timeout=10):
                        raise RuntimeError("Required Kafka topic is missing")
                self.ready = True
                async for message in consumer:
                    record = IncomingRecord(
                        message.topic,
                        message.partition,
                        message.offset,
                        message.value if message.value is not None else b"",
                        message.key,
                        tuple(message.headers or ()),
                    )
                    await self.process(record)
                    try:
                        await consumer.commit(
                            {TopicPartition(message.topic, message.partition): message.offset + 1}
                        )
                    except CommitFailedError:
                        # DB already committed. The new owner may replay safely.
                        log.info("offset_rebalance_replay")
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                log.error("kafka_reconnecting", extra={"code": type(exc).__name__})
            finally:
                self.ready = False
                try:
                    await asyncio.wait_for(
                        asyncio.gather(consumer.stop(), producer.stop(), return_exceptions=True), timeout=5
                    )
                except TimeoutError:
                    log.warning("kafka_shutdown_timeout")
                self.consumer = self.producer = None
            await asyncio.sleep(2)
