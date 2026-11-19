import asyncio
import os
from datetime import timedelta
from importlib.resources import files
from urllib.parse import urlparse
from uuid import uuid4

import asyncpg
import grpc
import pytest
import pytest_asyncio

from ds03.common.v1 import common_pb2
from ds03.driver.v1 import driver_pb2, driver_pb2_grpc
from matching_service.clients.driver_client import DriverClient
from matching_service.config import Settings
from matching_service.domain.events import utcnow
from matching_service.persistence.database import Database
from matching_service.persistence.repository import MatchingRepository


@pytest.fixture
def settings():
    return Settings(
        _env_file=None,
        database_url=os.environ.get(
            "TEST_DATABASE_URL", "postgresql+asyncpg://postgres@localhost/matching_test_unconfigured"
        ),
    )


@pytest_asyncio.fixture
async def repository(settings):
    url = os.environ.get("TEST_DATABASE_URL")
    if not url:
        pytest.skip("Use scripts/run_isolated_tests.py or set TEST_DATABASE_URL to a disposable database")
    if not urlparse(url).path.removeprefix("/").startswith("matching_test_"):
        pytest.fail("Integration tests refuse to modify a database without the matching_test_ prefix")
    connection = await asyncpg.connect(url.replace("postgresql+asyncpg://", "postgresql://", 1))
    try:
        sql = files("matching_service").joinpath("resources", "schema.sql").read_text(encoding="utf-8")
        await connection.execute(sql)
        await connection.execute(
            "TRUNCATE public.outbox_events, public.rpc_operations, public.processed_events, "
            "public.cancellation_tombstones, public.message_failures, "
            "public.event_sequences, public.matching_attempts"
        )
    finally:
        await connection.close()
    db = Database(url)
    try:
        yield MatchingRepository(db, settings)
    finally:
        await db.close()


class FakeDriver(driver_pb2_grpc.DriverServiceServicer):
    """Only a test peer. Production reservation/ownership belongs to Java Driver."""

    def __init__(self):
        self.drivers = [str(uuid4()), str(uuid4())]
        self.reservations = {}
        self.keys = {}
        self.reserve_keys = []
        self.release_count = 0
        self.lose_response = False
        self.fail_find = False
        self.fail_release = False
        self.confirm_on_release = False
        self.reserve_entered = asyncio.Event()
        self.resume_reserve = None
        self.lock = asyncio.Lock()

    async def FindNearbyDrivers(self, request, context):
        if self.fail_find:
            await context.abort(grpc.StatusCode.UNAVAILABLE, "test outage")
        busy = {
            r.driver_id
            for r in self.reservations.values()
            if r.status in (driver_pb2.HELD, driver_pb2.CONFIRMED)
        }
        return driver_pb2.FindNearbyDriversResponse(
            drivers=[
                driver_pb2.NearbyDriver(
                    driver_id=driver,
                    vehicle_id=str(uuid4()),
                    vehicle_type=common_pb2.MOTORBIKE,
                    distance_m=100 + i,
                )
                for i, driver in enumerate(self.drivers)
                if driver not in busy and driver not in request.excluded_driver_ids
            ][: request.limit]
        )

    async def ReserveDriver(self, request, context):
        key = request.mutation.idempotency_key
        self.reserve_keys.append(key)
        self.reserve_entered.set()
        if self.resume_reserve:
            await self.resume_reserve.wait()
        async with self.lock:
            if key in self.keys:
                old_request, reservation_id = self.keys[key]
                if old_request != request.SerializeToString():
                    context.set_trailing_metadata((("error-code", "IDEMPOTENCY_CONFLICT"),))
                    await context.abort(grpc.StatusCode.ALREADY_EXISTS, "key reused")
                return self.reservations[reservation_id]
            if any(
                r.driver_id == request.driver_id and r.status in (driver_pb2.HELD, driver_pb2.CONFIRMED)
                for r in self.reservations.values()
            ):
                context.set_trailing_metadata((("error-code", "DRIVER_UNAVAILABLE"),))
                await context.abort(grpc.StatusCode.FAILED_PRECONDITION, "busy")
            response = driver_pb2.Reservation(
                id=str(uuid4()), driver_id=request.driver_id, trip_id=request.trip_id, status=driver_pb2.HELD
            )
            response.expires_at.FromDatetime(utcnow() + timedelta(seconds=request.hold_seconds))
            response.updated_at.FromDatetime(utcnow())
            self.keys[key] = (request.SerializeToString(), response.id)
            self.reservations[response.id] = response
            if self.lose_response:
                self.lose_response = False
                await context.abort(grpc.StatusCode.UNAVAILABLE, "response lost after commit")
            return response

    async def GetReservation(self, request, context):
        if request.reservation_id not in self.reservations:
            await context.abort(grpc.StatusCode.NOT_FOUND, "not found")
        return self.reservations[request.reservation_id]

    async def ReleaseReservation(self, request, context):
        self.release_count += 1
        response = self.reservations[request.reservation_id]
        if self.confirm_on_release:
            self.confirm_on_release = False
            response.status = driver_pb2.CONFIRMED
        if response.status == driver_pb2.CONFIRMED:
            await context.abort(grpc.StatusCode.PERMISSION_DENIED, "Trip owns confirmed reservation")
        if self.fail_release:
            await context.abort(grpc.StatusCode.UNAVAILABLE, "outage")
        response.status = driver_pb2.RELEASED
        return response


@pytest_asyncio.fixture
async def driver(settings):
    fake = FakeDriver()
    server = grpc.aio.server()
    driver_pb2_grpc.add_DriverServiceServicer_to_server(fake, server)
    port = server.add_insecure_port("127.0.0.1:0")
    await server.start()
    client = DriverClient(settings.model_copy(update={"driver_grpc_address": f"127.0.0.1:{port}"}))
    try:
        yield fake, client
    finally:
        await client.close()
        await server.stop(0)
