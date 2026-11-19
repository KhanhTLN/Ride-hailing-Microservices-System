"""Local learning fixture, NOT the group's real Driver implementation.

SQLite persistence lets a demo Matching restart recover the same reservation.
No user authentication or real driver location/vehicle lifecycle is implemented.
"""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import datetime, timezone
import logging
import os
from pathlib import Path
import sqlite3
import sys
from threading import RLock
import time
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from matching.driver import pb, rpc, common
from google.protobuf.timestamp_pb2 import Timestamp
import grpc

DRIVER_IDS = ["00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002"]


class MockDriver(rpc.DriverServiceServicer):
    def __init__(self, db_path="mock-driver.sqlite3", lose_response=False):
        self.db = sqlite3.connect(db_path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.lock = RLock()
        self.lose_response = lose_response
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS reservations (
                id TEXT PRIMARY KEY, driver_id TEXT, trip_id TEXT, status TEXT, expires REAL);
            CREATE TABLE IF NOT EXISTS mutations (
                key TEXT PRIMARY KEY, signature TEXT, reservation_id TEXT, response_lost INTEGER DEFAULT 0);
        """)

    @contextmanager
    def transaction(self):
        with self.lock, self.db:
            self.db.execute("UPDATE reservations SET status='EXPIRED' WHERE status='HELD' AND expires<=?", (time.time(),))
            yield

    @staticmethod
    def abort(context, status, code):
        context.set_trailing_metadata((("error-code", code),))
        context.abort(status, code)

    def response(self, row):
        expires = Timestamp()
        expires.FromDatetime(datetime.fromtimestamp(row["expires"], timezone.utc))
        updated = Timestamp()
        updated.GetCurrentTime()
        return pb.Reservation(id=row["id"], driver_id=row["driver_id"], trip_id=row["trip_id"],
            status=pb.ReservationStatus.Value(row["status"]), expires_at=expires, updated_at=updated)

    def FindNearbyDrivers(self, request, context):
        with self.transaction():
            drivers = []
            for index, driver_id in enumerate(DRIVER_IDS):
                if driver_id in request.excluded_driver_ids:
                    continue
                busy = self.db.execute("SELECT id FROM reservations WHERE driver_id=? AND status IN ('HELD','CONFIRMED')", (driver_id,)).fetchone()
                if not busy:
                    now = Timestamp()
                    now.GetCurrentTime()
                    drivers.append(pb.NearbyDriver(driver_id=driver_id, vehicle_id=driver_id,
                        vehicle_type=request.vehicle_type, distance_m=(index + 1) * 100,
                        location_received_at=now))
            return pb.FindNearbyDriversResponse(drivers=drivers[:request.limit])

    def ReserveDriver(self, request, context):
        key = "reserve/" + request.mutation.idempotency_key
        signature = f"{request.driver_id}/{request.trip_id}/{request.hold_seconds}"
        lost = False
        with self.transaction():
            mutation = self.db.execute("SELECT * FROM mutations WHERE key=?", (key,)).fetchone()
            if mutation:
                if mutation["signature"] != signature:
                    self.abort(context, grpc.StatusCode.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT")
                row = self.db.execute("SELECT * FROM reservations WHERE id=?", (mutation["reservation_id"],)).fetchone()
            else:
                if request.driver_id not in DRIVER_IDS:
                    self.abort(context, grpc.StatusCode.FAILED_PRECONDITION, "DRIVER_UNAVAILABLE")
                busy = self.db.execute("SELECT id FROM reservations WHERE (driver_id=? OR trip_id=?) AND status IN ('HELD','CONFIRMED')",
                    (request.driver_id, request.trip_id)).fetchone()
                if busy:
                    self.abort(context, grpc.StatusCode.FAILED_PRECONDITION, "DRIVER_UNAVAILABLE")
                reservation_id = str(uuid4())
                self.db.execute("INSERT INTO reservations VALUES (?,?,?,?,?)",
                    (reservation_id, request.driver_id, request.trip_id, "HELD", time.time() + request.hold_seconds))
                self.db.execute("INSERT INTO mutations VALUES (?,?,?,?)",
                    (key, signature, reservation_id, int(self.lose_response)))
                row = self.db.execute("SELECT * FROM reservations WHERE id=?", (reservation_id,)).fetchone()
                lost = self.lose_response
            reply = self.response(row)
        if lost:
            # Intentionally abort AFTER SQLite commit, simulating lost response.
            self.abort(context, grpc.StatusCode.UNAVAILABLE, "SIMULATED_LOST_RESPONSE")
        return reply

    def GetReservation(self, request, context):
        with self.transaction():
            row = self.db.execute("SELECT * FROM reservations WHERE id=?", (request.reservation_id,)).fetchone()
            if row is None:
                self.abort(context, grpc.StatusCode.NOT_FOUND, "NOT_FOUND")
            return self.response(row)

    def ReleaseReservation(self, request, context):
        with self.transaction():
            row = self.db.execute("SELECT * FROM reservations WHERE id=?", (request.reservation_id,)).fetchone()
            if row is None:
                self.abort(context, grpc.StatusCode.NOT_FOUND, "NOT_FOUND")
            if row["trip_id"] != request.trip_id:
                self.abort(context, grpc.StatusCode.FAILED_PRECONDITION, "RESERVATION_MISMATCH")
            if row["status"] == "CONFIRMED":
                self.abort(context, grpc.StatusCode.PERMISSION_DENIED, "FORBIDDEN")
            if row["status"] == "HELD":
                self.db.execute("UPDATE reservations SET status='RELEASED' WHERE id=?", (row["id"],))
            row = self.db.execute("SELECT * FROM reservations WHERE id=?", (row["id"],)).fetchone()
            return self.response(row)

    def ConfirmReservation(self, request, context):
        # Test fixture only: actual Matching never invokes this RPC.
        with self.transaction():
            row = self.db.execute("SELECT * FROM reservations WHERE id=?", (request.reservation_id,)).fetchone()
            if row is None:
                self.abort(context, grpc.StatusCode.NOT_FOUND, "NOT_FOUND")
            if row["trip_id"] != request.trip_id:
                self.abort(context, grpc.StatusCode.FAILED_PRECONDITION, "RESERVATION_MISMATCH")
            if row["status"] not in ("HELD", "CONFIRMED"):
                self.abort(context, grpc.StatusCode.FAILED_PRECONDITION, "RESERVATION_EXPIRED")
            self.db.execute("UPDATE reservations SET status='CONFIRMED' WHERE id=?", (row["id"],))
            return self.response(self.db.execute("SELECT * FROM reservations WHERE id=?", (row["id"],)).fetchone())


def main():
    server = grpc.server(ThreadPoolExecutor(max_workers=4))
    mock = MockDriver(lose_response=os.environ.get("MOCK_LOSE_RESERVE_RESPONSE", "false").lower() == "true")
    rpc.add_DriverServiceServicer_to_server(mock, server)
    server.add_insecure_port("127.0.0.1:50052")
    server.start()
    print("Mock Driver: 127.0.0.1:50052. Local-only, no authentication.")
    try:
        server.wait_for_termination()
    except KeyboardInterrupt:
        server.stop(3).wait()
    finally:
        mock.db.close()


if __name__ == "__main__":
    main()
