import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from confluent_kafka.admin import AdminClient, NewTopic
from confluent_kafka import KafkaException, KafkaError
from matching.config import Settings

settings = Settings()
admin = AdminClient(settings.kafka_config())
topics = [settings.trip_topic, settings.matching_topic,
          f"{settings.trip_topic}.dlq.{settings.consumer_group}"]
for name, future in admin.create_topics([
    NewTopic(x, num_partitions=3, replication_factor=1,
             config={"retention.ms": str(7 * 86400 * 1000)}) for x in topics]).items():
    try:
        future.result()
        print("Created:", name)
    except KafkaException as exc:
        if exc.args[0].code() != KafkaError.TOPIC_ALREADY_EXISTS:
            raise
        print("Already exists:", name)
