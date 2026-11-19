import base64
import json

from matching_service.domain.events import IncomingRecord


def headers_json(headers):
    return [
        [key, base64.b64encode(value).decode("ascii") if value is not None else None]
        for key, value in headers
    ]


def restored_record(failure):
    return IncomingRecord(
        topic=failure.original_topic,
        partition=failure.original_partition,
        offset=failure.original_offset,
        value=failure.original_bytes,
        key=failure.original_key,
        headers=tuple(
            (key, base64.b64decode(value) if value is not None else None)
            for key, value in failure.original_headers
        ),
    )


def dead_letter_payload(failure):
    payload = {
        "original_topic": failure.original_topic,
        "partition": failure.original_partition,
        "offset": str(failure.original_offset),
        "consumer_group": failure.consumer_group,
        "attempts": failure.attempts,
        "error_code": failure.error_code,
        "failed_at": failure.failed_at.isoformat().replace("+00:00", "Z"),
    }
    try:
        original = json.loads(failure.original_bytes, parse_constant=lambda _: None)
        # Re-encoding must not lose duplicate fields or bytes of malformed JSON.
        if not isinstance(original, dict):
            raise ValueError("Not an object")
        from matching_service.messaging.validation import _no_duplicates

        original = json.loads(
            failure.original_bytes,
            object_pairs_hook=_no_duplicates,
            parse_constant=lambda _: (_ for _ in ()).throw(ValueError()),
        )
        json.dumps(original, allow_nan=False)
        payload["original_message"] = original
    except (ValueError, UnicodeError, RecursionError):
        payload["original_bytes_base64"] = base64.b64encode(failure.original_bytes).decode("ascii")
    return payload


async def retry_one(repository, handlers):
    failure = await repository.claim_failure()
    if failure is None:
        return
    try:
        await handlers.handle(restored_record(failure))
    except Exception as exc:
        await repository.failure_result(failure.id, failure.locked_by, error_code=type(exc).__name__)
    else:
        await repository.failure_result(failure.id, failure.locked_by)
