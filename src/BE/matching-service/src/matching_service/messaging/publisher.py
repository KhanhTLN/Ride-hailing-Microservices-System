import asyncio
import json
import logging

from matching_service.domain.errors import LostClaimError
from matching_service.messaging.validation import MAX_MESSAGE_BYTES, validate_payload

log = logging.getLogger(__name__)


class OutboxPublisher:
    def __init__(self, repository, transport, settings):
        self.repository, self.transport, self.settings = repository, transport, settings

    async def publish_one(self):
        if not self.transport.ready:
            return False
        event = await self.repository.claim_outbox()
        if event is None:
            return False
        try:
            validate_payload(event.payload, event.event_type)
            body = json.dumps(
                event.payload, ensure_ascii=False, allow_nan=False, separators=(",", ":")
            ).encode()
            if len(body) > MAX_MESSAGE_BYTES:
                # Preserve original bytes in DB, never silently truncate a DLQ record.
                raise ValueError("OUTBOX_PAYLOAD_TOO_LARGE_REQUIRES_OPERATOR")
            await self.transport.publish(event.topic, event.kafka_key.encode("ascii"), body)
        except Exception as exc:
            code = str(exc) if type(exc) is ValueError else type(exc).__name__
            await self.repository.outbox_result(event.event_id, event.locked_by, error=code)
            log.error("outbox_retry", extra={"event_id": str(event.event_id), "code": code})
        else:
            await self.repository.outbox_result(event.event_id, event.locked_by)
            log.info(
                "event_published",
                extra={
                    "event_id": str(event.event_id),
                    "correlation_id": str(event.correlation_id) if event.correlation_id else None,
                    "result": "sent",
                },
            )
        return True

    async def run(self):
        while True:
            try:
                worked = await self.publish_one()
            except LostClaimError:
                worked = False
            except Exception as exc:
                log.error("outbox_unavailable", extra={"code": type(exc).__name__})
                worked = False
            await asyncio.sleep(0 if worked else self.settings.worker_poll_seconds)
