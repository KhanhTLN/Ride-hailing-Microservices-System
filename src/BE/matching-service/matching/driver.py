from dataclasses import dataclass
from pathlib import Path
import os
import sys
import time

import grpc
import httpx

from .config import ROOT

# Generated imports keep the ds03 package layout declared by the contract.
sys.path.insert(0, str(ROOT / "generated"))
from ds03.common.v1 import common_pb2 as common
from ds03.driver.v1 import driver_pb2 as pb, driver_pb2_grpc as rpc


@dataclass
class DriverError(Exception):
    code: str
    # Only a documented business rejection proves that Reserve did not commit.
    rejected: bool = False


class DriverClient:
    def __init__(self, settings):
        self.settings = settings
        self.token = ""
        self.expires_at = 0
        self.token_file = os.getenv("MATCHING_DRIVER_TOKEN_FILE", "")
        if settings.driver_tls:
            roots = Path(settings.driver_ca_file).read_bytes() if settings.driver_ca_file else None
            self.channel = grpc.secure_channel(settings.driver_grpc_target,
                                               grpc.ssl_channel_credentials(roots))
        elif settings.demo_mode:
            self.channel = grpc.insecure_channel(settings.driver_grpc_target)
        else:
            raise ValueError("Plaintext Driver channel is demo-only")
        self.stub = rpc.DriverServiceStub(self.channel)

    def metadata(self, correlation_id):
        values = [("x-correlation-id", correlation_id)]
        if self.token_file:
            try:
                token = Path(self.token_file).read_text(encoding="utf-8").strip()
                if not token:
                    raise ValueError("Empty Driver service token")
            except (OSError, ValueError) as exc:
                raise DriverError("SERVICE_TOKEN_UNAVAILABLE") from exc
            values.append(("authorization", f"Bearer {token}"))
        elif not self.settings.demo_mode:
            if time.time() >= self.expires_at:
                try:
                    response = httpx.post(self.settings.user_token_url, json={
                        "grant_type": "client_credentials",
                        "client_id": self.settings.service_client_id,
                        "client_secret": self.settings.service_client_secret,
                        "audience": "driver-service", "scope": "driver:match",
                    }, timeout=3)
                    response.raise_for_status()
                    data = response.json()
                    self.token = data["access_token"]
                    self.expires_at = time.time() + max(1, int(data["expires_in"]) - 30)
                except (httpx.HTTPError, ValueError, KeyError) as exc:
                    raise DriverError("SERVICE_TOKEN_UNAVAILABLE") from exc
            values.append(("authorization", f"Bearer {self.token}"))
        return values

    def call(self, method, request, correlation_id, timeout):
        try:
            return method(request, timeout=timeout, metadata=self.metadata(correlation_id))
        except grpc.RpcError as exc:
            trailers = dict(exc.trailing_metadata() or [])
            code = trailers.get("error-code", exc.code().name)
            if exc.code() == grpc.StatusCode.UNAUTHENTICATED:
                self.expires_at = 0
            # Do not classify INTERNAL/UNAVAILABLE/DEADLINE_EXCEEDED as rejection.
            rejected = (exc.code() == grpc.StatusCode.FAILED_PRECONDITION and
                        code == "DRIVER_UNAVAILABLE")
            raise DriverError(code, rejected) from exc

    def find(self, data, correlation):
        request = pb.FindNearbyDriversRequest(
            pickup=common.Point(**data["pickup"]),
            vehicle_type=common.VehicleType.Value(data["vehicle_type"]),
            radius_m=5000, limit=20, excluded_driver_ids=data["excluded_driver_ids"])
        reply = self.call(self.stub.FindNearbyDrivers, request, correlation, 2)
        # Validate response size/order locally; Driver remains authority on eligibility.
        return [x.driver_id for x in sorted(reply.drivers, key=lambda x: (x.distance_m, x.driver_id))][:20]

    def reserve(self, driver_id, trip_id, key, correlation):
        reply = self.call(self.stub.ReserveDriver, pb.ReserveDriverRequest(
            driver_id=driver_id, trip_id=trip_id, hold_seconds=30,
            mutation=common.MutationContext(idempotency_key=key)), correlation, 3)
        return self.reservation(reply)

    def get(self, reservation_id, correlation):
        return self.reservation(self.call(self.stub.GetReservation,
            pb.GetReservationRequest(reservation_id=reservation_id), correlation, 2))

    def release(self, reservation_id, trip_id, key, correlation):
        return self.reservation(self.call(self.stub.ReleaseReservation,
            pb.ReleaseReservationRequest(reservation_id=reservation_id, trip_id=trip_id,
                reason="MATCHING_CANCELLED", mutation=common.MutationContext(idempotency_key=key)),
            correlation, 3))

    @staticmethod
    def reservation(reply):
        return {"id": reply.id, "driver_id": reply.driver_id, "trip_id": reply.trip_id,
                "status": pb.ReservationStatus.Name(reply.status),
                "expires_at": reply.expires_at.ToJsonString()}

    def ready(self):
        grpc.channel_ready_future(self.channel).result(timeout=1)

    def close(self):
        self.channel.close()
