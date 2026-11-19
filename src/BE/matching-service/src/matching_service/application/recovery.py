import asyncio
import logging

from matching_service.domain.errors import DriverError, LostClaimError
from matching_service.messaging.dead_letter import retry_one

log = logging.getLogger(__name__)


class RecoveryWorker:
    def __init__(self, repository, workflow, handlers, settings):
        self.repository, self.workflow, self.handlers, self.settings = (
            repository,
            workflow,
            handlers,
            settings,
        )

    async def run_once(self):
        attempt = await self.repository.claim_attempt()
        if attempt:
            try:
                await self.workflow.advance(attempt.attempt_id, attempt.locked_by)
            except LostClaimError:
                log.warning("workflow_claim_lost", extra={"attempt_id": str(attempt.attempt_id)})
            except Exception as exc:
                log.error(
                    "workflow_error",
                    extra={"attempt_id": str(attempt.attempt_id), "code": type(exc).__name__},
                )
                # PENDING RPCs and their original keys remain durable on unexpected failures.
                try:
                    await self.repository.query_error(
                        attempt.attempt_id, attempt.locked_by, DriverError("WORKFLOW_ERROR", retryable=True)
                    )
                except Exception:
                    pass  # Lease recovery will re-claim after a DB outage.
        await retry_one(self.repository, self.handlers)

    async def run(self):
        while True:
            try:
                await self.run_once()
            except Exception as exc:
                log.error("recovery_unavailable", extra={"code": type(exc).__name__})
            await asyncio.sleep(self.settings.worker_poll_seconds)
