from datetime import datetime, timezone
import json
from uuid import UUID

from jsonschema import Draft7Validator, FormatChecker


def timestamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def iso_now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class Contracts:
    def __init__(self, directory):
        self.validators = {}
        for name in ("TripRequested", "MatchingCancelled", "DriverReserved",
                     "MatchingFailed", "DeadLetter"):
            path = directory / "kafka" / "schemas" / f"{name}.schema.json"
            self.validators[name] = Draft7Validator(json.loads(path.read_text()),
                                                   format_checker=FormatChecker())

    def validate(self, name, event):
        self.validators[name].validate(event)

    def incoming(self, raw, key):
        if len(raw) > 65536:
            raise ValueError("MESSAGE_TOO_LARGE")
        event = json.loads(raw)
        if not isinstance(event, dict):
            raise ValueError("INVALID_ENVELOPE")
        name = event.get("event_type")
        if not isinstance(name, str) or not name:
            raise ValueError("MISSING_EVENT_TYPE")
        if name not in ("TripRequested", "MatchingCancelled"):
            return None  # Other valid Trip event types belong to other consumers.
        self.validate(name, event)
        trip_id = event["data"]["trip_id"]
        if key != trip_id.encode() or event["aggregate_id"] != trip_id:
            raise ValueError("TRIP_KEY_MISMATCH")
        ids = [event[x] for x in ("event_id", "correlation_id", "causation_id", "aggregate_id")]
        ids += [event["data"][x] for x in ("trip_id", "attempt_id")]
        if name == "TripRequested":
            ids += [event["data"]["passenger_id"], *event["data"]["excluded_driver_ids"]]
            if len(event["data"]["excluded_driver_ids"]) > 100:
                raise ValueError("TOO_MANY_EXCLUSIONS")
        if any(str(UUID(x)) != x for x in ids):
            raise ValueError("UUID_NOT_CANONICAL")
        return event
