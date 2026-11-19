"""Optional real-broker test; creates/deletes only UUID-named test topics."""

import asyncio
import os
from types import SimpleNamespace
from uuid import uuid4

import pytest
from aiokafka.admin import AIOKafkaAdminClient, NewTopic

from matching_service.messaging.consumer import KafkaTransport
from tests.helpers import record, trip_request


async def test_real_kafka_consume_and_commit(settings):
    address = os.environ.get("TEST_KAFKA_BOOTSTRAP_SERVERS")
    if not address:
        pytest.skip("Set TEST_KAFKA_BOOTSTRAP_SERVERS to test a live Kafka broker")
    suffix = uuid4().hex
    topics = [f"matching-test-input-{suffix}", f"matching-test-output-{suffix}"]
    admin = AIOKafkaAdminClient(bootstrap_servers=address)
    await admin.start()
    task = None
    try:
        await admin.create_topics([NewTopic(name, 1, 1) for name in topics])
        seen = asyncio.Event()

        async def handle(message):
            from matching_service.messaging.validation import decode_event

            decode_event(message.value, message.key)
            seen.set()

        cfg = settings.model_copy(
            update={
                "kafka_bootstrap_servers": address,
                "kafka_trip_topic": topics[0],
                "kafka_matching_topic": topics[1],
                "kafka_consumer_group": suffix,
            }
        )
        transport = KafkaTransport(cfg, SimpleNamespace(handle=handle), None)
        task = asyncio.create_task(transport.run())
        async with asyncio.timeout(30):
            while not transport.ready:
                await asyncio.sleep(0.1)
            message = record(trip_request())
            await transport.publish(topics[0], message.key, message.value)
            await seen.wait()
            from aiokafka import TopicPartition

            tp = TopicPartition(topics[0], 0)
            while (await transport.consumer.committed(tp) or 0) < 1:
                await asyncio.sleep(0.1)
    finally:
        if task:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        await admin.delete_topics(topics)
        await admin.close()
