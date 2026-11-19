from concurrent.futures import ThreadPoolExecutor
import importlib.util
from pathlib import Path
from uuid import uuid4

import grpc

from matching.config import ROOT, Settings
from matching.driver import DriverClient, DriverError, pb, rpc, common


def test_real_grpc_lost_response_and_reservation_lifecycle(tmp_path):
    spec = importlib.util.spec_from_file_location("mock_driver", ROOT / "scripts" / "mock_driver.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    mock = module.MockDriver(str(tmp_path / "driver.db"), lose_response=True)
    server = grpc.server(ThreadPoolExecutor(max_workers=2))
    rpc.add_DriverServiceServicer_to_server(mock, server)
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    client = DriverClient(Settings(_env_file=None, demo_mode=True, driver_tls=False,
                                  driver_grpc_target=f"127.0.0.1:{port}"))
    trip, key, correlation = [str(uuid4()) for _ in range(3)]
    try:
        candidates = client.find({"pickup": {"latitude": 21, "longitude": 105},
            "vehicle_type": "MOTORBIKE", "excluded_driver_ids": []}, correlation)
        try:
            client.reserve(candidates[0], trip, key, correlation)
            raise AssertionError("Expected simulated lost response")
        except DriverError as exc:
            assert not exc.rejected
        result = client.reserve(candidates[0], trip, key, correlation)
        assert result["status"] == "HELD"
        assert mock.db.execute("SELECT count(*) FROM reservations").fetchone()[0] == 1
        assert client.get(result["id"], correlation)["id"] == result["id"]
        released = client.release(result["id"], trip, str(uuid4()), correlation)
        assert released["status"] == "RELEASED"
    finally:
        client.close()
        server.stop(0).wait()
        mock.db.close()
