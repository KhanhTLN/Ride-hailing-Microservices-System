import json
from functools import lru_cache
from importlib.resources import files
from uuid import UUID

from jsonschema import Draft7Validator, FormatChecker

from matching_service.domain.errors import InvalidEventError

MAX_MESSAGE_BYTES = 64 * 1024
SUPPORTED_INPUTS = {"TripRequested", "MatchingCancelled"}


@lru_cache
def validator(event_type: str) -> Draft7Validator:
    path = files("matching_service").joinpath("resources", "schemas", f"{event_type}.schema.json")
    return Draft7Validator(json.loads(path.read_text(encoding="utf-8")), format_checker=FormatChecker())


def _canonical_ids(value):
    if isinstance(value, dict):
        for name, field in value.items():
            if name.endswith("_id") and isinstance(field, str) and str(UUID(field)) != field:
                raise InvalidEventError("IDs must be lowercase canonical UUIDs")
            _canonical_ids(field)
    elif isinstance(value, list):
        for field in value:
            _canonical_ids(field)


def _no_duplicates(pairs):
    value = {}
    for key, field in pairs:
        if key in value:
            raise InvalidEventError("Duplicate JSON field")
        value[key] = field
    return value


def validate_payload(payload: dict, event_type: str):
    error = next(validator(event_type).iter_errors(payload), None)
    if error is not None:
        # Do not log values from potentially sensitive messages.
        raise InvalidEventError(f"Invalid {event_type} schema at {list(error.absolute_path)}")
    if event_type != "DeadLetter":
        try:
            _canonical_ids(payload)
        except (ValueError, TypeError) as exc:
            raise InvalidEventError("Invalid canonical UUID") from exc


def decode_event(raw: bytes, key: bytes | None) -> dict | None:
    if len(raw) > MAX_MESSAGE_BYTES:
        raise InvalidEventError("Message exceeds 64 KiB")
    try:
        event = json.loads(
            raw,
            object_pairs_hook=_no_duplicates,
            parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Non-finite number")),
        )
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise InvalidEventError("Invalid UTF-8/JSON") from exc
    if not isinstance(event, dict):
        raise InvalidEventError("Expected JSON object")
    event_type = event.get("event_type")
    if not isinstance(event_type, str):
        raise InvalidEventError("Missing event_type")
    if event_type not in SUPPORTED_INPUTS:
        return None
    validate_payload(event, event_type)
    data = event["data"]
    if event["aggregate_id"] != data["trip_id"] or key != data["trip_id"].encode("ascii"):
        raise InvalidEventError("Kafka key, aggregate_id and trip_id must match")
    excluded = data.get("excluded_driver_ids", [])
    if len(excluded) > 100 or any(str(UUID(v)) != v for v in excluded):
        raise InvalidEventError("Invalid excluded_driver_ids")
    return event
