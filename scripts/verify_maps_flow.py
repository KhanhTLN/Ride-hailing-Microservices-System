"""Maps integration checks against two temporary Pricing replicas and stub providers.

Requires the built pricing-service image, healthy User/Pricing DB, Docker Desktop.
Uses the real shared PostgreSQL cache/rate gate; makes NO public provider requests.
Test account/quotes are retained, temporary containers are removed in finally.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import sys
import threading
import time
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlparse
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parents[1]
ROUTE = {"code": "Ok", "routes": [{"distance": 4321.2, "duration": 912.3,
         "geometry": {"type": "LineString", "coordinates": [[105.85, 21.02], [105.86, 21.03]]}}]}


class Provider(BaseHTTPRequestHandler):
    calls = []
    lock = threading.Lock()

    def log_message(self, *_):
        pass

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        with self.lock:
            self.calls.append({"path": self.path, "time": time.monotonic(), "agent": self.headers.get("User-Agent")})
        code = 200
        if parsed.path.startswith("/route/"):
            data = ROUTE
            if "/0.0000000,0.0000000;" in self.path:
                data = {"code": "NoRoute"}
        elif parsed.path.startswith("/api/"):
            data = {"features": [{"geometry": {"type": "Point", "coordinates": [105.85, 21.02]},
                     "properties": {"osm_type": "N", "osm_id": 1, "name": "Public landmark", "city": "Hanoi", "country": "Vietnam"}}]}
        else:
            data = {"place_id": 1, "display_name": "Public landmark", "lat": "21.02", "lon": "105.85"}
            if parsed.path.endswith("/search"):
                data = [data]
            if query.get("q") == ["unavailable provider"]:
                code, data = 503, {}
        raw = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file")
    parser.add_argument("--user-url", default="http://127.0.0.1:8082")
    parser.add_argument("--port", type=int, default=18083)
    parser.add_argument("--report", default="deploy/maps-flow-report.json")
    args = parser.parse_args()
    compose = ["docker", "compose", "-f", str(ROOT / "deploy/docker-compose.yml")]
    if args.env_file:
        compose += ["--env-file", args.env_file]
    names, checks = [], []
    token = None
    run_id = uuid.uuid4().hex[:12]
    server = ThreadingHTTPServer(("0.0.0.0", 0), Provider)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    endpoint = "http://host.docker.internal:" + str(server.server_port)

    def command(parts):
        result = subprocess.run(parts, cwd=ROOT, text=True, capture_output=True, timeout=120)
        if result.returncode:
            raise RuntimeError(result.stderr[-2500:])
        return result.stdout.strip()

    def http(base, path, body=None, key=None, auth=True):
        headers = {"Content-Type": "application/json", "X-Correlation-ID": str(uuid.uuid4())}
        if auth and token:
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = key
        request = Request(base + path, data=json.dumps(body).encode() if body is not None else None, headers=headers)
        try:
            with urlopen(request, timeout=15) as response:
                return response.status, json.load(response), {key.lower(): value for key, value in response.headers.items()}
        except HTTPError as error:
            return error.code, json.load(error), {key.lower(): value for key, value in error.headers.items()}

    def check(name, condition):
        checks.append({"name": name, "result": "PASS" if condition else "FAIL"})
        print(checks[-1]["result"] + " " + name, flush=True)
        if not condition:
            raise AssertionError(name)

    try:
        for index in range(2):
            name = "rhds-maps-test-" + run_id + "-" + str(index)
            names.append(name)
            command(compose + ["run", "-d", "--no-deps", "--name", name, "-p", f"127.0.0.1:{args.port + index}:8083",
                    "-e", "NOMINATIM_BASE_URL=" + endpoint, "-e", "PHOTON_BASE_URL=" + endpoint, "-e", "OSRM_BASE_URL=" + endpoint,
                    "-e", "OSRM_MOTORBIKE_BASE_URL=" + endpoint, "pricing-service"])
        bases = [f"http://127.0.0.1:{args.port + i}" for i in range(2)]
        for base in bases:
            deadline = time.monotonic() + 90
            while True:
                try:
                    if http(base, "/internal/pricing/health/ready", auth=False)[0] == 200:
                        break
                except (OSError, ValueError):
                    pass
                if time.monotonic() > deadline:
                    raise TimeoutError("Temporary Pricing replica did not become ready")
                time.sleep(1)
        credentials = {"email": "maps-" + run_id + "@example.test", "password": "MapsTest-Password-12345"}
        registered = http(args.user_url, "/api/v1/auth/register", {**credentials, "display_name": "Maps integration", "role": "PASSENGER"}, run_id, False)
        check("register-passenger", registered[0] == 201)
        login = http(args.user_url, "/api/v1/auth/login", credentials, auth=False)
        token = login[1]["access_token"]
        check("map-api-requires-access-token", http(bases[0], "/api/v1/maps/places?q=Hanoi", auth=False)[0] == 401)
        time.sleep(1.2)  # Respect the existing shared gate even if the public app was used recently.
        places = http(bases[0], "/api/v1/maps/places?q=public%20landmark")
        check("address-search", places[0] == 200 and places[1][0]["latitude"] == 21.02)
        calls = len(Provider.calls)
        cached = http(bases[1], "/api/v1/maps/places?q=public%20landmark")
        check("cache-shared-across-replicas", cached[1] == places[1] and len(Provider.calls) == calls)
        # Start a fresh gate window after both JVMs have warmed up. Check across replicas.
        time.sleep(1.2)
        calls = len(Provider.calls)
        with ThreadPoolExecutor(max_workers=2) as pool:
            burst = list(pool.map(lambda i: http(bases[i], "/api/v1/maps/places?q=rate%20landmark%20" + str(i)), range(2)))
        check("global-rate-limit-and-retry-header", sorted(result[0] for result in burst) == [200, 429]
              and next(result for result in burst if result[0] == 429)[2].get("retry-after") == "2" and len(Provider.calls) == calls + 1)
        time.sleep(1.2)
        reverse = http(bases[1], "/api/v1/maps/reverse?latitude=21.02&longitude=105.85")
        check("reverse-geocoding", reverse[0] == 200 and reverse[1]["longitude"] == 105.85)
        check("invalid-coordinates-rejected", http(bases[0], "/api/v1/maps/reverse?latitude=91&longitude=105")[0] == 422)
        suggestions = http(bases[0], "/api/v1/maps/suggestions?q=public%20landmark")
        check("autocomplete-photon", suggestions[0] == 200 and suggestions[1][0]["latitude"] == 21.02)
        calls = len(Provider.calls)
        shared_suggestions = http(bases[1], "/api/v1/maps/suggestions?q=public%20landmark")
        check("autocomplete-cache-shared", shared_suggestions[1] == suggestions[1] and len(Provider.calls) == calls)
        check("autocomplete-requires-auth", http(bases[0], "/api/v1/maps/suggestions?q=Hanoi", auth=False)[0] == 401)
        body = {"pickup": {"latitude": 21.02, "longitude": 105.85}, "dropoff": {"latitude": 21.03, "longitude": 105.86}, "vehicle_type": "MOTORBIKE"}
        time.sleep(1.2)
        route = http(bases[0], "/api/v1/maps/route", body)
        check("osrm-metrics-and-geometry", route[0] == 200 and route[1]["distance_m"] == 4322 and route[1]["duration_s"] == 913 and route[1]["geometry"] == ROUTE["routes"][0]["geometry"])
        check("osrm-coordinate-order", any("105.8500000,21.0200000;105.8600000,21.0300000" in call["path"] for call in Provider.calls))
        calls = len(Provider.calls)
        quote = http(bases[1], "/api/v1/quotes", {**body, "estimated_distance_m": 1, "fare": {"amount": "1", "currency": "VND"}}, run_id)
        check("quote-server-authority-and-cache", quote[0] == 201 and quote[1]["fare"]["amount"] == "32000" and quote[1]["estimated_distance_m"] == 4322 and len(Provider.calls) == calls)
        replay = http(bases[0], "/api/v1/quotes", body, run_id)
        check("quote-replay-across-replicas", replay[0] == 201 and replay[1] == quote[1])
        conflict = http(bases[1], "/api/v1/quotes", {**body, "vehicle_type": "CAR"}, run_id)
        check("quote-idempotency-conflict", conflict[0] == 409)
        time.sleep(1.2)
        absent = http(bases[0], "/api/v1/maps/route", {**body, "pickup": {"latitude": 0, "longitude": 0}})
        check("no-route-does-not-fallback", absent[0] == 422 and absent[1]["code"] == "ROUTE_NOT_FOUND")
        time.sleep(1.2)
        failure = http(bases[1], "/api/v1/maps/places?q=unavailable%20provider")
        check("provider-failure-is-503", failure[0] == 503 and failure[1]["retryable"])
        time.sleep(1.2)
        calls = len(Provider.calls)
        with ThreadPoolExecutor(max_workers=6) as pool:
            responses = list(pool.map(lambda i: http(bases[i % 2], "/api/v1/maps/places?q=concurrent%20landmark"), range(6)))
        check("concurrent-identical-misses-coalesced", all(result[0] == 200 for result in responses) and len(Provider.calls) == calls + 1)
        check("provider-user-agent", all(call["agent"] and not call["agent"].startswith("Java/") for call in Provider.calls))
        print("Maps integration passed; no public provider requests.", flush=True)
    finally:
        for name in names:
            command(["docker", "rm", "-f", name])
        server.shutdown()
        (ROOT / args.report).write_text(json.dumps({"run_id": run_id, "checks": checks, "provider_calls": len(Provider.calls), "public_provider_requests": 0,
            "summary": {"PASS": sum(c["result"] == "PASS" for c in checks), "FAIL": sum(c["result"] == "FAIL" for c in checks)}}, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
