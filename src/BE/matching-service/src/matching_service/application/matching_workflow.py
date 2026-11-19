import logging
from datetime import timedelta

from matching_service.domain.errors import DriverError
from matching_service.domain.events import utcnow

log = logging.getLogger(__name__)


class MatchingWorkflow:
    def __init__(self, repository, driver):
        self.repository, self.driver = repository, driver

    async def advance(self, attempt_id, token):
        attempt, operation = await self.repository.load_work(attempt_id, token)
        log.info(
            "workflow_step",
            extra={
                "attempt_id": str(attempt_id),
                "trip_id": str(attempt.trip_id),
                "correlation_id": str(attempt.correlation_id),
                "result": attempt.current_step,
            },
        )
        try:
            # An uncertain reserve is reconciled even after cancellation/deadline.
            if operation and operation.operation == "RESERVE":
                await self._reserve(attempt, token, operation)
            elif (operation and operation.operation == "RELEASE") or (
                attempt.reservation_id and (attempt.cancellation_requested_at or attempt.failure_reason)
            ):
                await self._cleanup(attempt, token)
            elif attempt.cancellation_requested_at:
                await self.repository.fail(attempt_id, token, "NO_DRIVER")
            elif attempt.search_deadline <= utcnow():
                await self.repository.fail(attempt_id, token, "DEADLINE_EXCEEDED")
            elif attempt.current_step == "FIND_CANDIDATES":
                candidates = await self.driver.find_nearby_drivers(attempt)
                await self.repository.save_candidates(attempt_id, token, candidates)
            else:
                operation = await self.repository.prepare_reserve(attempt_id, token)
                if operation:
                    await self._reserve(attempt, token, operation)
        except DriverError as exc:
            await self.repository.query_error(attempt_id, token, exc)
            log.warning("driver_query_failed", extra={"attempt_id": str(attempt_id), "code": exc.code})

    async def _reserve(self, attempt, token, operation):
        try:
            # The contract only guarantees mutation-key retention for seven days.
            # Beyond it, keep durable work for operator reconciliation, never recreate a hold blindly.
            if operation.created_at < utcnow() - timedelta(days=7):
                raise DriverError("RECONCILIATION_REQUIRES_OPERATOR", ambiguous=True)
            response = await self.driver.reserve_driver(operation.request_payload, attempt.correlation_id)
        except DriverError as exc:
            await self.repository.rpc_error(attempt.attempt_id, token, operation.id, exc)
            log.warning(
                "driver_reserve_failed", extra={"attempt_id": str(attempt.attempt_id), "code": exc.code}
            )
        else:
            await self.repository.reserved(attempt.attempt_id, token, operation.id, response)
            log.info(
                "reservation_reconciled",
                extra={
                    "attempt_id": str(attempt.attempt_id),
                    "correlation_id": str(attempt.correlation_id),
                    "result": response["status"],
                },
            )

    async def _cleanup(self, attempt, token):
        response = await self.driver.get_reservation(
            attempt.reservation_id, attempt.trip_id, attempt.current_driver_id, attempt.correlation_id
        )
        if response["status"] in ("RELEASED", "EXPIRED", "CONFIRMED"):
            # CONFIRMED belongs to Trip, and must never be released by Matching.
            await self.repository.released(attempt.attempt_id, token, response)
            return
        operation = await self.repository.prepare_release(attempt.attempt_id, token)
        if operation.status in ("SUCCEEDED", "FAILED"):
            raise DriverError("INCONSISTENT_RESERVATION_STATE", retryable=True)
        try:
            if operation.created_at < utcnow() - timedelta(days=7):
                raise DriverError("RECONCILIATION_REQUIRES_OPERATOR", ambiguous=True)
            response = await self.driver.release_reservation(
                operation.request_payload, attempt.correlation_id
            )
        except DriverError as exc:
            await self.repository.rpc_error(attempt.attempt_id, token, operation.id, exc)
        else:
            await self.repository.released(attempt.attempt_id, token, response)
