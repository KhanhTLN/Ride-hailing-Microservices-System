import asyncio
from uuid import UUID

import grpc

from ds03.common.v1 import common_pb2
from ds03.driver.v1 import driver_pb2, driver_pb2_grpc
from matching_service.clients.auth import driver_metadata
from matching_service.config import Settings
from matching_service.domain.errors import DriverError

RETRYABLE_CODES = {
    grpc.StatusCode.UNAVAILABLE,
    grpc.StatusCode.DEADLINE_EXCEEDED,
    grpc.StatusCode.UNKNOWN,
    grpc.StatusCode.INTERNAL,
    grpc.StatusCode.CANCELLED,
    grpc.StatusCode.RESOURCE_EXHAUSTED,
}
BUSINESS_CODES = {
    "DRIVER_UNAVAILABLE",
    "RESERVATION_EXPIRED",
    "RESERVATION_MISMATCH",
    "VERSION_CONFLICT",
    "IDEMPOTENCY_CONFLICT",
    "UNAUTHENTICATED",
    "FORBIDDEN",
    "NOT_FOUND",
    "INVALID_ARGUMENT",
    "DEPENDENCY_UNAVAILABLE",
    "DEADLINE_EXCEEDED",
    "INTERNAL",
    "RATE_LIMITED",
}


class DriverClient:
    def __init__(self, settings: Settings):
        self.settings = settings
        options = (
            ("grpc.max_receive_message_length", 65536),
            ("grpc.max_send_message_length", 65536),
            ("grpc.max_metadata_size", 8192),
        )
        if settings.driver_grpc_tls:
            roots = settings.driver_grpc_ca_file.read_bytes() if settings.driver_grpc_ca_file else None
            self.channel = grpc.aio.secure_channel(
                settings.driver_grpc_address, grpc.ssl_channel_credentials(roots), options=options
            )
        else:
            self.channel = grpc.aio.insecure_channel(settings.driver_grpc_address, options=options)
        self.stub = driver_pb2_grpc.DriverServiceStub(self.channel)

    async def ready(self):
        await asyncio.wait_for(self.channel.channel_ready(), timeout=1)

    async def close(self):
        await self.channel.close()

    async def _call(self, method, request, correlation_id, *, mutation=False):
        try:
            return await method(
                request,
                metadata=driver_metadata(self.settings, correlation_id),
                timeout=(
                    self.settings.grpc_mutation_timeout_seconds
                    if mutation
                    else self.settings.grpc_query_timeout_seconds
                ),
            )
        except grpc.aio.AioRpcError as exc:
            code = exc.code()
            trailer = dict(exc.trailing_metadata() or ()).get("error-code")
            name = trailer if trailer in BUSINESS_CODES else code.name
            if code == grpc.StatusCode.FAILED_PRECONDITION and trailer not in BUSINESS_CODES:
                # A bare precondition does not identify which business failure happened.
                name = "FAILED_PRECONDITION"
            retryable = code in RETRYABLE_CODES or code == grpc.StatusCode.ABORTED
            ambiguous = mutation and (retryable or name == "IDEMPOTENCY_CONFLICT")
            raise DriverError(name, retryable=retryable, ambiguous=ambiguous) from None

    async def find_nearby_drivers(self, attempt):
        request = driver_pb2.FindNearbyDriversRequest(
            pickup=common_pb2.Point(
                latitude=attempt.pickup_latitude,
                longitude=attempt.pickup_longitude,
                address=attempt.pickup_address or "",
            ),
            vehicle_type=common_pb2.VehicleType.Value(attempt.vehicle_type),
            radius_m=attempt.radius_m,
            limit=attempt.candidate_limit,
            excluded_driver_ids=[str(v) for v in attempt.excluded_driver_ids],
        )
        response = await self._call(self.stub.FindNearbyDrivers, request, attempt.correlation_id)
        try:
            candidates = []
            seen = {str(v) for v in attempt.excluded_driver_ids}
            if len(response.drivers) > attempt.candidate_limit:
                raise ValueError("Too many candidates")
            for candidate in response.drivers:
                driver_id = str(UUID(candidate.driver_id))
                if (
                    driver_id != candidate.driver_id
                    or driver_id in seen
                    or candidate.vehicle_type != request.vehicle_type
                    or candidate.distance_m > attempt.radius_m
                ):
                    raise ValueError("Invalid candidate")
                seen.add(driver_id)
                candidates.append({"driver_id": driver_id, "distance_m": candidate.distance_m})
            return sorted(candidates, key=lambda c: (c["distance_m"], c["driver_id"]))
        except ValueError:
            raise DriverError("INVALID_DRIVER_RESPONSE", retryable=True) from None

    @staticmethod
    def _reservation(response, payload, *, mutation=False, terminal=False):
        try:
            value = {
                "id": str(UUID(response.id)),
                "driver_id": str(UUID(response.driver_id)),
                "trip_id": str(UUID(response.trip_id)),
                "status": driver_pb2.ReservationStatus.Name(response.status),
                "expires_at": response.expires_at.ToJsonString(),
            }
            if (
                value["status"] not in ("HELD", "CONFIRMED", "RELEASED", "EXPIRED")
                or not response.HasField("expires_at")
                or any(value[k] != getattr(response, k) for k in ("id", "driver_id", "trip_id"))
                or any(value[k] != payload[k] for k in ("driver_id", "trip_id") if k in payload)
                or ("reservation_id" in payload and value["id"] != payload["reservation_id"])
                or (terminal and value["status"] not in ("RELEASED", "EXPIRED"))
            ):
                raise ValueError("Mismatched reservation")
            return value
        except (ValueError, OverflowError):
            raise DriverError("INVALID_DRIVER_RESPONSE", retryable=True, ambiguous=mutation) from None

    async def reserve_driver(self, payload, correlation_id):
        request = driver_pb2.ReserveDriverRequest(
            driver_id=payload["driver_id"],
            trip_id=payload["trip_id"],
            hold_seconds=payload["hold_seconds"],
            mutation=common_pb2.MutationContext(idempotency_key=payload["idempotency_key"]),
        )
        response = await self._call(self.stub.ReserveDriver, request, correlation_id, mutation=True)
        return self._reservation(response, payload, mutation=True)

    async def get_reservation(self, reservation_id, trip_id, driver_id, correlation_id):
        response = await self._call(
            self.stub.GetReservation,
            driver_pb2.GetReservationRequest(reservation_id=str(reservation_id)),
            correlation_id,
        )
        return self._reservation(
            response,
            {"reservation_id": str(reservation_id), "trip_id": str(trip_id), "driver_id": str(driver_id)},
        )

    async def release_reservation(self, payload, correlation_id):
        request = driver_pb2.ReleaseReservationRequest(
            reservation_id=payload["reservation_id"],
            trip_id=payload["trip_id"],
            reason=payload["reason"],
            mutation=common_pb2.MutationContext(idempotency_key=payload["idempotency_key"]),
        )
        response = await self._call(self.stub.ReleaseReservation, request, correlation_id, mutation=True)
        return self._reservation(response, payload, mutation=True, terminal=True)
