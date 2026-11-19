"""Publish a FRESH contract-valid event and observe its matching result."""
import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import sys
import time
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from confluent_kafka import Consumer, Producer
from matching.config import Settings
from matching.contracts import Contracts, iso_now
from matching.runtime import publish


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("scenario", choices=["success", "duplicate", "no-driver", "cancel-before", "cancel-after", "expired"])
    args = parser.parse_args()
    settings = Settings()
    contracts = Contracts(settings.contracts_dir)
    producer = Producer({**settings.kafka_config(), "enable.idempotence": True, "message.timeout.ms": 5000})
    consumer = Consumer({**settings.kafka_config(), "group.id": f"matching-demo-{uuid4()}",
                         "auto.offset.reset": "earliest", "enable.auto.commit": False})
    consumer.subscribe([settings.matching_topic])
    trip, attempt, correlation = (str(uuid4()) for _ in range(3))
    deadline = datetime.now(timezone.utc) + timedelta(seconds=-1 if args.scenario == "expired" else 120)
    event = {"event_id": str(uuid4()), "event_type": "TripRequested", "schema_version": 1,
        "producer": "trip", "occurred_at": iso_now(), "correlation_id": correlation,
        "causation_id": str(uuid4()), "aggregate_id": trip, "aggregate_version": 1,
        "data": {"trip_id": trip, "attempt_id": attempt, "passenger_id": str(uuid4()),
            "pickup": {"latitude": 21.0285, "longitude": 105.8542}, "vehicle_type": "MOTORBIKE",
            "search_deadline": deadline.isoformat(), "excluded_driver_ids": []}}
    if args.scenario == "no-driver":
        event["data"]["excluded_driver_ids"] = [f"00000000-0000-4000-8000-{i:012d}" for i in (1, 2)]
    cancel = {**event, "event_id": str(uuid4()), "event_type": "MatchingCancelled", "aggregate_version": 2,
        "data": {"trip_id": trip, "attempt_id": attempt, "reason": "Demo cancellation"}}

    def send(message):
        contracts.incoming(json.dumps(message).encode(), trip.encode())
        publish(producer, settings.trip_topic, trip, message)

    print(f"trip_id={trip}\nattempt_id={attempt}\ncorrelation_id={correlation}")
    try:
        if args.scenario == "cancel-before":
            send(cancel)
        send(event)
        if args.scenario == "duplicate":
            send(event)
        if args.scenario == "cancel-before":
            print("Expected: CANCELLED in matching_attempts; no DriverReserved event for this trip.")
            return
        deadline_wait = time.monotonic() + 30
        while time.monotonic() < deadline_wait:
            msg = consumer.poll(1)
            if msg is None or msg.error():
                continue
            result = json.loads(msg.value())
            if result.get("aggregate_id") != trip:
                continue
            contracts.validate(result["event_type"], result)
            print(json.dumps(result, indent=2))
            expected = "MatchingFailed" if args.scenario in ("no-driver", "expired") else "DriverReserved"
            if result["event_type"] != expected:
                raise SystemExit(f"Unexpected result; expected {expected}")
            if args.scenario == "cancel-after":
                send(cancel)
                print("Cancellation sent. Inspect matching_attempts=CANCELLED and mock reservation=RELEASED.")
            return
        raise SystemExit("No result in 30s. Inspect Matching logs, mock Driver and health/ready.")
    finally:
        consumer.close()


if __name__ == "__main__":
    main()
