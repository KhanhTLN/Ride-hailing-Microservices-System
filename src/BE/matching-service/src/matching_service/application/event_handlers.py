import logging

from matching_service.messaging.validation import decode_event

log = logging.getLogger(__name__)


class EventHandlers:
    def __init__(self, repository):
        self.repository = repository

    async def handle(self, record):
        event = decode_event(record.value, record.key)
        if event is not None:
            await self.repository.accept(event, record)
            log.info(
                "event_accepted",
                extra={
                    "event_id": event["event_id"],
                    "trip_id": event["data"]["trip_id"],
                    "attempt_id": event["data"]["attempt_id"],
                    "correlation_id": event["correlation_id"],
                    "result": "persisted",
                },
            )
