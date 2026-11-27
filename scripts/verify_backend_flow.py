"""Exercise the local Compose stack and save redacted, reproducible evidence.

From the repo root (Docker Desktop and the backend stack must be running):
  src/BE/matching-service/.venv/Scripts/python.exe scripts/verify_backend_flow.py

Uses only new flow-<run_id>@example.invalid accounts and UUIDs. A newly created
admin fixture is promoted through SQL because there is no admin bootstrap API.
Contract-valid Kafka injections and manual gRPC calls are explicitly fixtures,
not proof that the automatic Trip -> Matching -> Driver saga works.
Leaves evidence rows in the local DBs. Finally releases this run's reservations,
cancels its unfinished trips, switches its driver offline and demotes its admin.
Never records passwords or access/refresh/service tokens in the JSON report.
Exit 0: all checks passed; 1: logic failures found; 2: test setup/runtime error.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
from pathlib import Path
import secrets
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from uuid import uuid4

from jsonschema import Draft7Validator, FormatChecker

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "ds03-api-contracts-v1/kafka/schemas"
PORTS = {"user": 8082, "driver": 8081, "pricing": 8083, "trip": 8080, "matching": 8084}
SENSITIVE = {"password", "access_token", "refresh_token", "authorization", "service_token"}
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def uid():
    return str(uuid4())


def now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def redacted(value):
    if isinstance(value, dict):
        return {k: "[REDACTED]" if k.lower() in SENSITIVE else redacted(v) for k, v in value.items()}
    if isinstance(value, list):
        return [redacted(v) for v in value]
    return value


PUBLISH = """
import json,sys
from confluent_kafka import Producer
from matching.config import Settings
d=json.load(sys.stdin)
p=Producer({**Settings().kafka_config(), 'enable.idempotence': True, 'message.timeout.ms': 5000})
delivered=[]
def callback(error,message):
    if error: raise RuntimeError(str(error))
    delivered.append({'partition':message.partition(),'offset':message.offset()})
p.produce(d['topic'],key=d['event']['aggregate_id'].encode(),value=json.dumps(d['event']).encode(),on_delivery=callback)
if p.flush(6) or not delivered: raise RuntimeError('Fixture event delivery failed')
print(json.dumps({'event_id':d['event']['event_id'],**delivered[0]}))
"""

COMMITTED = """
import json,sys
from confluent_kafka import Consumer,TopicPartition
from matching.config import Settings
d=json.load(sys.stdin)
c=Consumer({**Settings().kafka_config(),'group.id':d['group'],'enable.auto.commit':False})
try:
    result=c.committed([TopicPartition(d['topic'],d['partition'])],timeout=5)
    print(json.dumps({'offset':result[0].offset}))
finally: c.close()
"""

READ_DLQ = """
import json,sys,time
from confluent_kafka import Consumer,TopicPartition
from matching.config import Settings
d=json.load(sys.stdin)
c=Consumer({**Settings().kafka_config(),'group.id':d['group'],'enable.auto.commit':False})
c.assign([TopicPartition(d['topic'],d['partition'],0)])
try:
    deadline=time.monotonic()+12
    while time.monotonic()<deadline:
        msg=c.poll(.5)
        if msg is None or msg.error(): continue
        event=json.loads(msg.value())
        if event.get('original_message',{}).get('event_id')==d['event_id']:
            print(json.dumps(event)); break
    else: raise RuntimeError('Trip DLQ fixture record was not found')
finally: c.close()
"""

DRIVER_RPC = """
import json,sys
from matching.config import Settings
from matching.driver import DriverClient,DriverError,pb,common
d=json.load(sys.stdin)
c=DriverClient(Settings())
if d.get('service_token'):
    c.metadata=lambda correlation: [('x-correlation-id',correlation),('authorization','Bearer '+d['service_token'])]
try:
    op=d['operation']; a=d['args']; corr=d['correlation']
    if op=='find': result=c.find(a,corr)
    elif op=='reserve': result=c.reserve(a['driver_id'],a['trip_id'],a['key'],corr)
    elif op=='get': result=c.get(a['reservation_id'],corr)
    elif op=='release': result=c.release(a['reservation_id'],a['trip_id'],a['key'],corr)
    elif op=='confirm':
        result=c.reservation(c.call(c.stub.ConfirmReservation,pb.ConfirmReservationRequest(
            reservation_id=a['reservation_id'],trip_id=a['trip_id'],
            mutation=common.MutationContext(idempotency_key=a['key'])),corr,3))
    print(json.dumps({'ok':True,'result':result}))
except DriverError as e: print(json.dumps({'ok':False,'code':e.code,'rejected':e.rejected}))
finally: c.close()
"""


class FlowCheck:
    def __init__(self, args):
        self.args = args
        self.run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:6]
        self.correlation = uid()
        self.password = "Flow-" + secrets.token_urlsafe(20)
        self.report = {"run_id": self.run_id, "started_at": now(), "correlation_id": self.correlation,
                       "scope": "live local stack; Kafka/gRPC fixtures are labelled", "checks": [], "http": [], "fixtures": []}
        self.trips = []
        self.accounts = {}
        self.trip_owners = {}
        self.driver = None
        self.admin = None
        self.heartbeat_stop = threading.Event()
        self.heartbeat_thread = None
        self.sequence = 1
        self.intentional_driver_down = False
        self.point = {"latitude": 21.028511, "longitude": 105.854167, "address": "Flow test pickup"}
        self.docker_base = ["docker", "compose"]
        if args.env_file:
            self.docker_base += ["--env-file", args.env_file]
        self.docker_base += ["-f", str(ROOT / "deploy/docker-compose.yml")]
        config = json.loads(self.docker(["config", "--format", "json"]))
        for name, target in PORTS.items():
            for port in config["services"][name + "-service"].get("ports", []):
                if port["target"] == target:
                    PORTS[name] = int(port["published"])

    def docker(self, args, payload=None, timeout=45):
        result = subprocess.run(self.docker_base + args, input=payload, text=True,
                                encoding="utf-8", capture_output=True, cwd=ROOT, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"Docker {args[:3]} failed: {result.stderr[-1800:]}")
        return result.stdout.strip()

    def sql(self, service, query):
        database = {"user": "rhds_user", "driver": "rhds_driver", "trip": "rhds_trip", "matching": "matching_db"}[service]
        user = "matching" if service == "matching" else "postgres"
        statement = f"SELECT COALESCE(json_agg(r), '[]'::json) FROM ({query}) r"
        raw = self.docker(["exec", "-T", service + "-db", "psql", "-X", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1",
                           "-U", user, "-d", database, "-c", statement])
        return json.loads(raw)

    def set_admin_fixture_role(self, role):
        assert role in ("ADMIN", "PASSENGER")
        self.docker(["exec", "-T", "user-db", "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "rhds_user", "-c",
                     f'''UPDATE public."user" SET role='{role}' WHERE id='{self.admin['id']}' '''])

    def http(self, service, method, path, body=None, token=None, user=None, key=None, record=True, correlation=None):
        if token is None and user in self.accounts:
            token = self.accounts[user]["tokens"]["access_token"]
        headers = {"X-Correlation-ID": correlation or self.correlation, "Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if user:
            headers["X-User-Id"] = user
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(f"http://127.0.0.1:{PORTS[service]}{path}",
                                         data=json.dumps(body).encode() if body is not None else None,
                                         headers=headers, method=method)
        try:
            response = OPENER.open(request, timeout=12)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read().decode()
            data = json.loads(raw) if raw else None
            status = response.code
            response_correlation=response.headers.get("X-Correlation-ID")
        if record:
            self.report["http"].append(redacted({"at": now(), "service": service, "method": method, "path": path,
                                                "headers": headers, "request": body, "status": status, "response": data, "response_correlation_id": response_correlation}))
        return status, data

    def require(self, response, statuses=(200,)):
        status, data = response
        if status not in statuses:
            raise RuntimeError(f"Prerequisite HTTP {status}: {redacted(data)}")
        return data

    def check(self, name, condition, expected, actual):
        state = "PASS" if condition else "FAIL"
        self.report["checks"].append({"id": name, "result": state, "expected": expected, "actual": redacted(actual)})
        print(f"{state}: {name}", flush=True)

    def wait(self, function, predicate=bool, timeout=20):
        deadline = time.monotonic() + timeout
        while True:
            value = function()
            if predicate(value):
                return value
            if time.monotonic() >= deadline:
                raise TimeoutError(f"Condition not reached in {timeout}s; last value={redacted(value)}")
            time.sleep(0.5)

    def account(self, label, role="PASSENGER"):
        email = f"flow-{self.run_id}-{label}@example.invalid".lower()
        register_key=uid()
        register_body={"email":email,"password":self.password,"display_name":"Flow "+label,"role":role}
        registered=self.require(self.http("user","POST","/api/v1/auth/register",register_body,key=register_key),(201,))
        if label=="passenger":
            replay=self.require(self.http("user","POST","/api/v1/auth/register",register_body,key=register_key),(201,))
            self.check("register-idempotency-replay",replay["id"]==registered["id"],"Same user for a repeated registration",replay["id"])
            status,body=self.http("user","POST","/api/v1/auth/register",{**register_body,"display_name":"Changed body"},key=register_key)
            self.check("register-idempotency-conflict",status==409,"Same key with a changed body is rejected",{"status":status,"body":body})
        tokens = self.require(self.http("user", "POST", "/api/v1/auth/login", {"email": email, "password": self.password}))
        account={"id": registered["id"], "email": email, "tokens": tokens}
        self.accounts[account["id"]]=account
        return account

    def schema_errors(self, event):
        path = SCHEMAS / (event["event_type"] + ".schema.json")
        validator = Draft7Validator(json.loads(path.read_text()), format_checker=FormatChecker())
        return [f"{'/'.join(str(p) for p in e.path) or '$'}: {e.message}"
                for e in validator.iter_errors(event)]

    def envelope(self, name, producer, trip, data, version=1):
        return {"event_id": uid(), "event_type": name, "schema_version": 1, "producer": producer,
                "occurred_at": now(), "correlation_id": self.correlation, "causation_id": uid(),
                "aggregate_id": trip, "aggregate_version": version, "data": data}

    def request_event(self, trip=None, attempt=None, passenger=None, point=None, expired=False, budget_seconds=120):
        trip = trip or uid()
        data = {"trip_id": trip, "attempt_id": attempt or uid(), "passenger_id": passenger or uid(),
                "pickup": point or self.point, "vehicle_type": "MOTORBIKE", "excluded_driver_ids": [],
                "search_deadline": (datetime.now(timezone.utc) + timedelta(seconds=-5 if expired else budget_seconds)).isoformat()}
        return self.envelope("TripRequested", "trip", trip, data)

    def publish(self, topic, event, validate=True):
        errors = self.schema_errors(event)
        if errors and validate:
            raise RuntimeError(f"Test fixture violates schema: {errors}")
        receipt = json.loads(self.docker(["exec", "-T", "matching-service", "python", "-c", PUBLISH], json.dumps({"topic": topic, "event": event})))
        self.report["fixtures"].append({"kind": "injected Kafka event", "topic": topic, "event": event, "receipt": receipt})
        return receipt

    def wait_consumed(self, topic, receipt, group):
        def committed():
            return json.loads(self.docker(["exec", "-T", "matching-service", "python", "-c", COMMITTED],
                              json.dumps({"topic": topic, "partition": receipt["partition"], "group": group})))["offset"]
        return self.wait(committed, lambda offset: offset > receipt["offset"], timeout=25)

    def rpc(self, operation, args, caller="matching"):
        data = {"operation": operation, "args": args, "correlation": self.correlation}
        if caller == "trip":
            # Capture the local fixture token in memory only; never print or save it.
            data["service_token"] = self.docker(["exec", "-T", "local-service-tokens", "python", "-c",
                                                  "from pathlib import Path; print(Path('/auth/trip/token').read_text())"])
        result = json.loads(self.docker(["exec", "-T", "matching-service", "python", "-c", DRIVER_RPC], json.dumps(data)))
        self.report["fixtures"].append({"kind": "direct gRPC fixture", "caller": caller, "operation": operation,
                                        "args": args, "response": result})
        return result

    def quote_trip(self, account):
        quote = self.require(self.http("pricing", "POST", "/api/v1/quotes",
                             {"pickup": self.point, "dropoff": {**self.point, "latitude": self.point["latitude"] + .01}, "vehicle_type": "MOTORBIKE"},
                             token=account["tokens"]["access_token"], user=account["id"], key=uid()), (201,))
        key = uid()
        body = {"quote_id": quote["id"], "payment_method": "SIMULATED"}
        trip = self.require(self.http("trip", "POST", "/api/v1/trips", body,
                            token=account["tokens"]["access_token"], user=account["id"], key=key), (201,))
        self.trips.append((trip["id"], account["id"]))
        self.trip_owners[trip["id"]]=account["id"]
        return trip, quote, key, body

    def trip(self, trip_id):
        return self.require(self.http("trip", "GET", f"/api/v1/trips/{trip_id}", user=self.trip_owners[trip_id], record=False))

    def action(self, trip_id, action, user=None, token=None, version=None, key=None):
        if version is None:
            version = self.trip(trip_id)["version"]
        body={"expected_version":version}
        current=self.trip(trip_id)
        if action in ("accept","reject"):
            body.update({"attempt_id":current["attempt_id"],"reservation_id":current.get("reservation_id")})
        if action=="cancel": body["reason"]="FLOW_TEST_CANCEL"
        result=self.http("trip", "POST", f"/api/v1/trips/{trip_id}/{action}", body,
                         user=user, token=token, key=key or uid())
        if result[0]==202:
            operation=self.wait(lambda: self.require(self.http("trip","GET",f"/api/v1/operations/{result[1]['id']}",user=user,token=token,record=False)),
                                lambda value: value["status"] in ("SUCCEEDED","FAILED"),timeout=40)
            return result[0],operation
        return result

    def outbox(self, trip_id):
        return self.sql("trip", f"SELECT event_type,status,aggregate_version,payload FROM outbox_event WHERE aggregate_id='{trip_id}' ORDER BY created_at")

    def attempt(self, attempt_id):
        return self.sql("matching", f"SELECT attempt_id,trip_id,state,reservation,cancel_requested,last_error,next_run FROM matching_attempts WHERE attempt_id='{attempt_id}'")

    def maintain_presence(self):
        while not self.heartbeat_stop.wait(5):
            try:
                self.sequence += 1
                self.require(self.http("driver", "POST", "/api/v1/drivers/me/heartbeat", {"session_id": self.session}, token=self.driver["tokens"]["access_token"], record=False))
                self.require(self.http("driver", "PUT", "/api/v1/drivers/me/location",
                             {"session_id": self.session, "sequence_number": self.sequence, "point": self.point, "recorded_at": now()},
                             token=self.driver["tokens"]["access_token"], record=False))
            except Exception as error:
                if not self.intentional_driver_down:
                    self.report.setdefault("presence_errors", []).append(str(error))

    def run(self):
        self.report["containers_before"] = [json.loads(line) for line in self.docker(["ps", "-a", "--format", "json"]).splitlines() if line.strip()]
        for service, path in (("user", "identity"), ("driver", "driver"), ("pricing", "pricing"), ("trip", "trip"), ("matching", "matching")):
            status, body = self.http(service, "GET", f"/internal/{path}/health/ready")
            self.check("health-" + service, status == 200, "HTTP 200", {"status": status, "body": body})

        invalid_status,invalid_body=self.http("user","GET","/internal/identity/health/ready",correlation="invalid-correlation-fixture")
        invalid_header=self.report["http"][-1]["response_correlation_id"]
        self.check("invalid-correlation-id-rejected",invalid_status==422 and invalid_body.get("correlation_id")==invalid_header,
                   "Reject malformed correlation before it can produce invalid events; response/header use one ID",{"status":invalid_status,"body":invalid_body,"header":invalid_header})
        passenger = self.account("passenger")
        self.driver = self.account("driver", "DRIVER")
        status,own=self.http("user","GET","/api/v1/users/me",token=passenger["tokens"]["access_token"])
        self.check("user-profile-own-access",status==200 and own["id"]==passenger["id"],"Authenticated user can read own profile",{"status":status,"body":own})
        status,_=self.http("user","GET",f"/api/v1/users/{self.driver['id']}",token=passenger["tokens"]["access_token"])
        self.check("user-profile-other-owner-blocked",status==403,"Other user's profile requires ADMIN",status)
        status,_=self.http("user","GET","/api/user",token=passenger["tokens"]["access_token"])
        self.check("legacy-user-list-requires-admin",status==403,"Legacy user list is not public to passengers",status)
        self.admin = self.account("admin-fixture")
        self.check("register-login", passenger["tokens"]["user"]["roles"] == ["PASSENGER"] and self.driver["tokens"]["user"]["roles"] == ["DRIVER"],
                   "Correct PASSENGER and DRIVER identities", {"passenger_id": passenger["id"], "driver_user_id": self.driver["id"]})
        encoded_claims = passenger["tokens"]["access_token"].split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(encoded_claims + "=" * (-len(encoded_claims) % 4)))
        self.check("user-access-token-claims", claims["sub"] == passenger["id"] and claims["roles"] == ["PASSENGER"] and "ds03-api" in claims["aud"] and claims["exp"] > time.time(),
                   "Correct subject, role, audience and unexpired access token", claims)
        status, _ = self.http("user", "POST", "/api/v1/auth/login", {"email": passenger["email"].upper(), "password": self.password})
        self.check("login-email-normalization", status == 200, "Login applies the same email normalization as registration", status)
        status, _ = self.http("user", "POST", "/api/v1/auth/register", {"email": f"flow-{self.run_id}-forbidden@example.invalid", "password": self.password, "display_name": "Forbidden admin", "role": "ADMIN"}, key=uid())
        self.check("public-register-admin-rejected", status >= 400, "Reject public ADMIN registration", status)
        admin_id = self.admin["id"]
        self.set_admin_fixture_role("ADMIN")
        self.report["fixtures"].append({"kind": "SQL admin bootstrap", "new_test_user_id": admin_id})
        self.admin["tokens"] = self.require(self.http("user", "POST", "/api/v1/auth/login", {"email": self.admin["email"], "password": self.password}))

        original_refresh = passenger["tokens"]["refresh_token"]
        status, fresh = self.http("user", "POST", "/api/v1/auth/refresh", {"refresh_token": original_refresh})
        self.check("refresh-valid", status == 200 and "access_token" in fresh, "New token pair", status)
        status, _ = self.http("user", "POST", "/api/v1/auth/refresh", {"refresh_token": original_refresh})
        self.check("refresh-rotation-rejects-reuse", status == 401, "401 for refresh token already rotated", status)
        family_status,_=self.http("user","POST","/api/v1/auth/refresh",{"refresh_token":fresh["refresh_token"]})
        self.check("refresh-reuse-revokes-family",family_status==401,"Reuse also revokes the replacement refresh token",family_status)
        logout_pair=self.require(self.http("user","POST","/api/v1/auth/login",{"email":passenger["email"],"password":self.password}))
        self.require(self.http("user", "POST", "/api/v1/auth/logout", {"refresh_token": logout_pair["refresh_token"]}))
        status, _ = self.http("user", "POST", "/api/v1/auth/refresh", {"refresh_token": logout_pair["refresh_token"]})
        self.check("logout-revokes-refresh", status == 401, "401 after logout", status)
        status, _ = self.http("user", "POST", "/api/v1/auth/refresh", {"refresh_token": passenger["tokens"]["access_token"]})
        self.check("access-token-cannot-refresh", status == 401, "Reject access token at refresh endpoint", status)
        concurrent_pair=self.require(self.http("user","POST","/api/v1/auth/login",{"email":passenger["email"],"password":self.password}))
        with ThreadPoolExecutor(max_workers=3) as pool:
            results=list(pool.map(lambda _:self.http("user","POST","/api/v1/auth/refresh",{"refresh_token":concurrent_pair["refresh_token"]})[0],range(3)))
        self.check("refresh-concurrent-single-use",results.count(200)==1 and results.count(401)==2,"Only one concurrent refresh may rotate a token",results)
        quote_body={"pickup":self.point,"dropoff":{**self.point,"latitude":self.point["latitude"]+.01},"vehicle_type":"MOTORBIKE"}
        quote_key=uid()
        with ThreadPoolExecutor(max_workers=2) as pool:
            quotes=list(pool.map(lambda _:self.http("pricing","POST","/api/v1/quotes",quote_body,user=passenger["id"],key=quote_key),range(2)))
        self.check("pricing-concurrent-idempotency",all(r[0]==201 for r in quotes) and quotes[0][1]["id"]==quotes[1][1]["id"],"Concurrent quote retries return one quote",quotes)
        status,changed=self.http("pricing","POST","/api/v1/quotes",{**quote_body,"vehicle_type":"CAR"},user=passenger["id"],key=quote_key)
        self.check("pricing-idempotency-conflict",status==409,"Same quote key with changed inputs is rejected",{"status":status,"body":changed})

        token = self.driver["tokens"]["access_token"]
        profile_key = uid()
        profile_body = {"license_number": "FLOW-" + self.run_id}
        profile = self.require(self.http("driver", "POST", "/api/v1/drivers/me", profile_body, token=token, key=profile_key), (201,))
        self.driver_id = profile["id"]
        status,_=self.http("driver","GET",f"/api/drivers/{self.driver['id']}")
        self.check("legacy-driver-profile-requires-auth",status==401,"Legacy profile requires authentication",status)
        status,_=self.http("driver","GET",f"/api/drivers/{self.driver['id']}",token=passenger["tokens"]["access_token"])
        self.check("legacy-driver-profile-owner-enforced",status==403,"Only driver owner or ADMIN can read legacy profile",status)
        replay = self.require(self.http("driver", "POST", "/api/v1/drivers/me", profile_body, token=token, key=profile_key), (201,))
        self.check("driver-create-idempotency", replay["id"] == profile["id"], "Same driver ID", replay["id"])
        status, _ = self.http("driver", "POST", "/api/v1/drivers/me", {"license_number": "OTHER-" + self.run_id}, token=token, key=profile_key)
        self.check("driver-idempotency-conflict", status == 409, "409 for same key with different body", status)
        status, _ = self.http("driver", "PATCH", "/api/v1/drivers/me/availability", {"accepting_trips": True}, token=token, key=uid())
        self.check("unapproved-driver-cannot-go-online", status >= 400, "Reject while PENDING", status)
        status, _ = self.http("driver", "PATCH", f"/api/v1/admin/drivers/{self.driver_id}/status", {"account_status": "APPROVED", "reason": "Flow test"}, token=token, key=uid())
        self.check("driver-cannot-self-approve", status == 403, "403 for DRIVER role", status)
        self.require(self.http("driver", "PATCH", f"/api/v1/admin/drivers/{self.driver_id}/status", {"account_status": "APPROVED", "reason": "Local flow fixture"}, token=self.admin["tokens"]["access_token"], key=uid()))
        self.require(self.http("driver", "PUT", "/api/v1/drivers/me/vehicle", {"plate_number": "FLOW-" + uuid4().hex[:10], "vehicle_type": "MOTORBIKE", "brand": "Test", "model": "Simulated", "color": "Black", "seat_count": 2}, token=token, key=uid()))
        self.require(self.http("driver", "PATCH", "/api/v1/drivers/me/availability", {"accepting_trips": True}, token=token, key=uid()))
        session = self.require(self.http("driver", "POST", "/api/v1/drivers/me/location-sessions", token=token, key=uid()), (200, 201))
        self.session = session["session_id"]
        self.require(self.http("driver", "POST", "/api/v1/drivers/me/heartbeat", {"session_id": self.session}, token=token))
        location = {"session_id": self.session, "sequence_number": 1, "point": self.point, "recorded_at": now()}
        self.require(self.http("driver", "PUT", "/api/v1/drivers/me/location", location, token=token))
        status, _ = self.http("driver", "PUT", "/api/v1/drivers/me/location", location, token=token)
        self.check("location-same-sequence-replay", status == 200, "Replay same payload succeeds", status)
        status, _ = self.http("driver", "PUT", "/api/v1/drivers/me/location", {**location, "sequence_number": 0}, token=token)
        self.check("location-old-sequence-rejected", status == 409, "409 for stale sequence", status)
        status, _ = self.http("driver", "POST", "/api/v1/drivers/me/heartbeat", {"session_id": uid()}, token=token)
        self.check("heartbeat-wrong-session-rejected", status == 409, "409 for wrong session", status)
        self.heartbeat_thread = threading.Thread(target=self.maintain_presence, daemon=True)
        self.heartbeat_thread.start()

        self.require(self.http("driver", "PATCH", "/api/v1/drivers/me/availability", {"accepting_trips": False}, token=token, key=uid()))
        trip, quote, key, body = self.quote_trip(passenger)
        self.natural_trip = trip["id"]
        self.report["natural_trip"] = trip
        self.check("quote-and-create-trip", trip["status"] == "SEARCHING", "201 SEARCHING", trip)
        search = self.sql("trip", f"SELECT search_budget_seconds FROM trip WHERE id='{trip['id']}'")[0]
        self.check("trip-search-budget-10-minutes", search["search_budget_seconds"] == 600, "New trip reserves 600 seconds for matching", search)
        waiting = self.wait(lambda: self.attempt(trip["attempt_id"]),
                            lambda rows: rows and rows[0]["state"] == "SEARCHING" and rows[0]["next_run"] > time.time())
        self.check("empty-search-keeps-trip-searching", self.trip(trip["id"])["status"] == "SEARCHING",
                   "First empty search schedules another round while the same Trip is SEARCHING", waiting)
        self.require(self.http("driver", "PATCH", "/api/v1/drivers/me/availability", {"accepting_trips": True}, token=token, key=uid()))
        self.check("trip-version-starts-at-one",trip["version"]==1,"New Trip resource version starts at 1",trip["version"])
        wrong_account=self.account("wrong-owner")
        status, wrong_owner = self.http("trip", "POST", "/api/v1/trips", body, token=wrong_account["tokens"]["access_token"], user=passenger["id"], key=uid())
        self.check("quote-owner-enforced", status >= 400, "Quote cannot create a trip for a different passenger", {"status": status, "body": wrong_owner})
        other_quote = self.require(self.http("pricing", "POST", "/api/v1/quotes",
                                  {"pickup": self.point, "dropoff": {**self.point, "longitude": self.point["longitude"] + .01}, "vehicle_type": "MOTORBIKE"},
                                  user=passenger["id"], key=uid()), (201,))
        status, second_trip = self.http("trip", "POST", "/api/v1/trips", {"quote_id": other_quote["id"], "payment_method": "SIMULATED"}, user=passenger["id"], key=uid())
        self.check("one-active-trip-per-passenger", status == 409, "Reject a second active trip for the same passenger", {"status": status, "body": second_trip})
        status, replay = self.http("trip", "POST", "/api/v1/trips", body, user=passenger["id"], token=passenger["tokens"]["access_token"], key=key)
        self.check("trip-create-idempotency", status == 201 and replay.get("id") == trip["id"], "Same 201 and trip ID for same key", {"status": status, "body": replay})
        status, _ = self.http("trip", "GET", f"/api/v1/trips/{trip['id']}")
        self.check("trip-read-requires-auth", status == 401, "401 without user JWT", status)
        refresh_status,_=self.http("trip","GET",f"/api/v1/trips/{trip['id']}",token=passenger["tokens"]["refresh_token"])
        self.check("refresh-token-cannot-authenticate-trip",refresh_status==401,"Refresh tokens cannot authenticate REST business APIs",refresh_status)
        self.wait(lambda:self.trip(trip["id"]),lambda t:t["status"]=="DRIVER_OFFERED")
        status, _ = self.action(trip["id"], "accept", user=self.driver["id"], token=token, version=trip["version"] + 100)
        self.check("trip-stale-version-rejected", status in (403,409), "409 version conflict", status)
        published = self.wait(lambda: self.outbox(trip["id"]), lambda rows: bool(rows) and rows[0]["status"] == "SENT")
        event = next(row["payload"] for row in published if row["event_type"] == "TripRequested")
        errors = self.schema_errors(event)
        self.check("trip-requested-contract", not errors, "TripRequested validates against v1 JSON Schema", errors)
        inbox = self.wait(lambda: self.sql("matching", f"SELECT id,state,retries,last_error FROM matching_inbox WHERE convert_from(raw,'UTF8') LIKE '%{trip['id']}%'"))
        natural_attempt = self.wait(lambda: self.attempt(trip["attempt_id"]))
        self.check("automatic-trip-to-matching", bool(natural_attempt), "Matching creates attempt from actual Trip event", {"inbox": inbox, "attempt": natural_attempt, "trip_status": self.trip(trip["id"])["status"]})

        offered=self.wait(lambda:self.trip(trip["id"]),lambda t:t["status"]=="DRIVER_OFFERED")
        self.check("automatic-driver-offered",offered["driver_id"]==self.driver_id,"Real TripRequested -> HELD -> DRIVER_OFFERED",offered)
        self.require(self.action(trip["id"],"cancel",user=passenger["id"]),(202,))
        self.test_concurrent_trip_creation()
        self.test_reservations()
        self.test_matching_fixtures()
        self.test_trip_saga(passenger)
        self.test_invalid_transitions(trip, passenger)
        self.test_stale_matching()
        self.test_saga_recovery()

        print("Waiting for an intentionally malformed fixture event to reach DLQ...", flush=True)
        malformed=self.request_event()
        del malformed["data"]["search_deadline"]
        self.publish("ds03.trip.events.v1",malformed,validate=False)
        invalid_matching=self.envelope("MatchingFailed","matching",uid(),{"trip_id":"placeholder","attempt_id":uid(),"reason":"INVALID_REASON_FIXTURE"})
        invalid_matching["data"]["trip_id"]=invalid_matching["aggregate_id"]
        poison_receipt=self.publish("ds03.matching.events.v1",invalid_matching,validate=False)
        bad_id=malformed["aggregate_id"]
        dlq = self.wait(lambda: self.sql("matching", f"SELECT id,state,retries,last_error FROM matching_inbox WHERE convert_from(raw,'UTF8') LIKE '%{bad_id}%'"),
                        lambda rows: any(row["state"] == "DLQ" for row in rows), timeout=145)
        dlq_outbox = self.wait(lambda:self.sql("matching", f"SELECT topic,published,payload FROM matching_outbox WHERE topic LIKE '%.dlq.%' AND payload::text LIKE '%{bad_id}%'"),
                               lambda rows:bool(rows) and rows[0]["published"])
        self.check("invalid-trip-event-to-dlq",bool(dlq_outbox),"Malformed fixture retries then DLQ; real TripRequested is valid",{"inbox":dlq,"outbox":dlq_outbox})
        trip_dlq=json.loads(self.docker(["exec","-T","matching-service","python","-c",READ_DLQ],json.dumps({"group":"flow-dlq-"+self.run_id,"topic":"ds03.matching.events.v1.dlq.trip-matching-v1","partition":poison_receipt["partition"],"event_id":invalid_matching["event_id"]})))
        dlq_schema=json.loads((SCHEMAS/"DeadLetter.schema.json").read_text())
        dlq_errors=[e.message for e in Draft7Validator(dlq_schema,format_checker=FormatChecker()).iter_errors(trip_dlq)]
        self.check("trip-poison-event-dlq-contract",not dlq_errors and trip_dlq["consumer_group"]=="trip-matching-v1" and trip_dlq["attempts"]==6,"Trip consumer emits the contract DeadLetter envelope after retries",{"envelope":trip_dlq,"errors":dlq_errors})
        processed=self.sql("trip",f"SELECT event_id FROM trip_processed_event WHERE event_id='{invalid_matching['event_id']}'")
        self.check("trip-invalid-event-not-marked-processed",not processed,"Invalid event is rejected before the processed marker",processed)
        trip_rows = []
        for trip_id, _ in self.trips:
            trip_rows.extend(self.outbox(trip_id))
        invalid_events = [{"event_type": row["event_type"], "aggregate_version": row["aggregate_version"], "errors": self.schema_errors(row["payload"])}
                          for row in trip_rows if self.schema_errors(row["payload"])]
        self.check("all-trip-outbox-events-contract", not invalid_events, "Every Trip-produced event matches its v1 schema", invalid_events)
        self.report["payment_notification_scope"] = "Payment and Notification services are absent; payment events below are injected consumer tests, not a real payment/ledger test."

    def test_concurrent_trip_creation(self):
        account=self.account("concurrent-trip")
        def quote():
            return self.require(self.http("pricing","POST","/api/v1/quotes",{"pickup":self.point,"dropoff":{**self.point,"latitude":self.point["latitude"]+.01},"vehicle_type":"MOTORBIKE"},user=account["id"],key=uid()),(201,))
        accepted_quote=quote()
        body={"quote_id":accepted_quote["id"],"payment_method":"SIMULATED"}
        key=uid()
        with ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda _:self.http("trip","POST","/api/v1/trips",body,user=account["id"],key=key),range(2)))
        self.check("concurrent-create-same-key-one-trip",all(r[0]==201 for r in results) and results[0][1]["id"]==results[1][1]["id"],"Concurrent same-key create returns one Trip ID",results)
        ids={r[1]["id"] for r in results if r[0]==201}
        for trip_id in ids:
            self.trips.append((trip_id,account["id"])); self.trip_owners[trip_id]=account["id"]
            events=self.outbox(trip_id)
            self.check("concurrent-create-single-outbox",sum(r["event_type"]=="TripRequested" for r in events)==1,"Exactly one initial TripRequested",events)
            self.wait(lambda:self.trip(trip_id),lambda t:t["status"]=="DRIVER_OFFERED")
            self.require(self.action(trip_id,"cancel",user=account["id"]),(202,))
        bodies=[{"quote_id":quote()["id"],"payment_method":"SIMULATED"} for _ in range(2)]
        with ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda b:self.http("trip","POST","/api/v1/trips",b,user=account["id"],key=uid()),bodies))
        self.check("concurrent-create-different-keys-single-active",sorted(r[0] for r in results)==[201,409],"Different keys cannot create two active trips for one passenger",results)
        for status,trip in results:
            if status==201:
                self.trips.append((trip["id"],account["id"])); self.trip_owners[trip["id"]]=account["id"]
                self.wait(lambda:self.trip(trip["id"]),lambda t:t["status"]=="DRIVER_OFFERED")
                self.require(self.action(trip["id"],"cancel",user=account["id"]),(202,))

    def test_reservations(self):
        find = self.rpc("find", {"pickup": self.point, "vehicle_type": "MOTORBIKE", "excluded_driver_ids": []})
        self.check("grpc-find-real-driver", find["ok"] and self.driver_id in find["result"], "Fresh, approved test driver is discoverable", find)
        requests = [{"driver_id": self.driver_id, "trip_id": uid(), "key": uid()} for _ in range(2)]
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda data: self.rpc("reserve", data), requests))
        winners = [(request, result["result"]) for request, result in zip(requests, results) if result["ok"]]
        self.check("concurrent-reserve-single-winner", len(winners) == 1 and any(result.get("code") == "DRIVER_UNAVAILABLE" for result in results),
                   "Only one active reservation for a driver", results)
        for request, reservation in winners:
            replay = self.rpc("reserve", request)
            self.check("grpc-reserve-idempotency", replay["ok"] and replay["result"]["id"] == reservation["id"], "Same reservation for same mutation key", replay)
            self.rpc("release", {"reservation_id": reservation["id"], "trip_id": request["trip_id"], "key": uid()})

    def test_matching_fixtures(self):
        for label, event, reason in (
            ("no-driver", self.request_event(point={"latitude": -60, "longitude": -130}, budget_seconds=12), "DEADLINE_EXCEEDED"),
            ("expired-deadline", self.request_event(expired=True), "DEADLINE_EXCEEDED"),
        ):
            self.publish("ds03.trip.events.v1", event)
            attempt = self.wait(lambda: self.attempt(event["data"]["attempt_id"]), lambda rows: bool(rows) and rows[0]["state"] == "FAILED")
            output = self.wait(lambda: self.sql("matching", f"SELECT payload,published FROM matching_outbox WHERE key='{event['aggregate_id']}'"))
            self.check("matching-" + label, output[0]["payload"]["data"]["reason"] == reason and not self.schema_errors(output[0]["payload"]),
                       reason + " with valid event", {"attempt": attempt, "output": output})
        event = self.request_event()
        cancel = self.envelope("MatchingCancelled", "trip", event["aggregate_id"],
                               {"trip_id": event["aggregate_id"], "attempt_id": event["data"]["attempt_id"], "reason": "Flow cancel-before fixture"}, 2)
        self.publish("ds03.trip.events.v1", cancel)
        self.publish("ds03.trip.events.v1", event)
        attempt = self.wait(lambda: self.attempt(event["data"]["attempt_id"]), lambda rows: bool(rows) and rows[0]["state"] == "CANCELLED")
        rows = self.sql("driver", f"SELECT id FROM driver_reservation WHERE trip_id='{event['aggregate_id']}'")
        self.check("matching-cancel-before-request", not rows, "Cancellation tombstone prevents reservation", {"attempt": attempt, "reservations": rows})
        event = self.request_event()
        self.publish("ds03.trip.events.v1", event)
        attempt = self.wait(lambda: self.attempt(event["data"]["attempt_id"]), lambda rows: bool(rows) and rows[0]["state"] == "RESERVED")
        reservation = attempt[0]["reservation"]
        cancel = self.envelope("MatchingCancelled", "trip", event["aggregate_id"],
                               {"trip_id": event["aggregate_id"], "attempt_id": event["data"]["attempt_id"], "reason": "Flow cancel-after fixture"}, 2)
        self.publish("ds03.trip.events.v1", cancel)
        cancelled = self.wait(lambda: self.attempt(event["data"]["attempt_id"]), lambda rows: bool(rows) and rows[0]["state"] == "CANCELLED")
        released = self.rpc("get", {"reservation_id": reservation["id"]})
        self.check("matching-cancel-after-reserve-releases", released["ok"] and released["result"]["status"] == "RELEASED",
                   "Cancellation reconciles and releases HELD", {"attempt": cancelled, "reservation": released})

    def test_trip_saga(self, first_passenger):
        passenger = self.account("saga-passenger")
        trip, quote, _, _ = self.quote_trip(passenger)
        offered = self.wait(lambda: self.trip(trip["id"]), lambda value: value["status"] == "DRIVER_OFFERED")
        source=next(row["payload"] for row in self.outbox(trip["id"]) if row["event_type"]=="TripRequested")
        self.publish("ds03.trip.events.v1",source)
        self.publish("ds03.trip.events.v1",source)
        self.wait_consumed("ds03.trip.events.v1",self.report["fixtures"][-1]["receipt"],"matching-v1")
        attempt = self.attempt(trip["attempt_id"])[0]
        reservation = attempt["reservation"]
        rows = self.sql("driver", f"SELECT id,status,trip_id FROM driver_reservation WHERE trip_id='{trip['id']}'")
        outputs = self.sql("matching", f"SELECT event_id,payload,published FROM matching_outbox WHERE key='{trip['id']}' AND payload->>'event_type'='DriverReserved'")
        self.check("matching-valid-request-offers-trip", offered["driver_id"] == self.driver_id and rows[0]["status"] == "HELD",
                   "Automatic real Trip event -> Driver HELD -> Trip DRIVER_OFFERED", {"trip": offered, "reservation": rows, "matching_output": outputs})
        self.check("matching-duplicate-event-dedup", len(rows) == 1 and len(outputs) == 1, "One reservation and one DriverReserved despite duplicate delivery", {"reservations": len(rows), "outputs": len(outputs)})
        token = self.driver["tokens"]["access_token"]
        accept_key = uid()
        missing_status,_=self.http("trip","POST",f"/api/v1/trips/{trip['id']}/accept",{"expected_version":offered["version"]},user=self.driver["id"],key=uid())
        self.check("accept-requires-offer-identifiers",missing_status==422,"Offer action requires attempt_id and reservation_id",missing_status)
        stale_status,stale_body=self.http("trip","POST",f"/api/v1/trips/{trip['id']}/accept",{"expected_version":offered["version"],"attempt_id":uid(),"reservation_id":offered["reservation_id"]},user=self.driver["id"],key=uid())
        self.check("accept-stale-offer-identifiers-rejected",stale_status==409,"Current version does not authorize a different offer attempt",{"status":stale_status,"body":stale_body})
        status, operation = self.action(trip["id"], "accept", user=self.driver["id"], token=token, version=offered["version"], key=accept_key)
        self.require((status, operation), (202,))
        accepted = self.trip(trip["id"])
        current = self.rpc("get", {"reservation_id": reservation["id"]})
        self.check("accept-confirms-reservation", current["ok"] and current["result"]["status"] == "CONFIRMED" and accepted["status"] == "ACCEPTED",
                   "Driver CONFIRMED before Trip ACCEPTED", {"trip": accepted, "reservation": current, "operation": operation})
        status, repeated = self.action(trip["id"], "accept", user=self.driver["id"], token=token, version=offered["version"], key=accept_key)
        self.check("accept-idempotency-replay", status == 202 and repeated.get("id") == operation["id"], "Same operation for a retried accept", {"status": status, "body": repeated})
        blocked_release = self.rpc("release", {"reservation_id": reservation["id"], "trip_id": trip["id"], "key": uid()})
        self.check("matching-cannot-release-confirmed", not blocked_release["ok"] and blocked_release["code"] == "FORBIDDEN",
                   "Matching lacks permission to release CONFIRMED", blocked_release)
        status, wrong_driver = self.action(trip["id"], "arrive", user=first_passenger["id"], token=first_passenger["tokens"]["access_token"])
        self.check("trip-action-owner-enforced", status in (403, 404), "Reject action by a different user/PASSENGER", {"status": status, "body": wrong_driver})
        if status != 202:
            self.require(self.action(trip["id"], "arrive", user=self.driver["id"], token=token), (202,))
        self.require(self.action(trip["id"], "start", user=self.driver["id"], token=token), (202,))
        cancel_status,cancel_op=self.action(trip["id"],"cancel",user=passenger["id"])
        self.check("in-progress-cancel-rejected",cancel_status==409,"Baseline forbids cancellation during IN_PROGRESS",{"status":cancel_status,"body":cancel_op})
        status, completed_op = self.action(trip["id"], "complete", user=self.driver["id"], token=token)
        self.require((status, completed_op), (202,))
        completed = self.trip(trip["id"])
        current = self.rpc("get", {"reservation_id": reservation["id"]})
        self.check("complete-releases-driver", current["ok"] and current["result"]["status"] in ("RELEASED", "EXPIRED"),
                   "Release driver before COMPLETE operation SUCCEEDED", {"trip": completed, "operation": completed_op, "reservation": current})
        self.check("complete-fare-preserved", Decimal(completed["estimated_fare"]["amount"]) == Decimal(quote["fare"]["amount"]) and completed["estimated_fare"]["currency"] == quote["fare"]["currency"],
                   "Final fare equals accepted quote numerically for baseline", {"quote": quote["fare"], "completed": completed["estimated_fare"]})
        payment = self.sql("trip", f"SELECT payment_id,attempt_number,payment_status,fare_amount::text FROM trip WHERE id='{trip['id']}'")[0]
        status, retry = self.action(trip["id"], "retry-payment", user=passenger["id"], token=passenger["tokens"]["access_token"])
        self.check("payment-retry-requires-failed", status == 409, "Reject retry while payment is PENDING", {"status": status, "body": retry})
        stale = self.envelope("PaymentSucceeded", "payment", trip["id"],
                              {"trip_id": trip["id"], "passenger_id": passenger["id"], "payment_id": uid(),
                               "attempt_number": payment["attempt_number"], "amount": {"amount": str(int(float(payment["fare_amount"]))), "currency": "VND"}})
        receipt = self.publish("ds03.payment.events.v1", stale)
        self.wait_consumed("ds03.payment.events.v1", receipt, "trip-payment-v1")
        observed = self.trip(trip["id"])
        self.check("payment-result-validates-identity-and-attempt", observed["payment_status"] != "SUCCEEDED",
                   "Ignore payment event with a wrong payment_id/old attempt", {"original_payment": payment, "injected_event": stale, "trip": observed})
        wrong_attempt=self.envelope("PaymentSucceeded","payment",trip["id"],
            {"trip_id":trip["id"],"passenger_id":passenger["id"],"payment_id":payment["payment_id"],"attempt_number":0+payment["attempt_number"]+1,
             "amount":{"amount":str(int(Decimal(payment["fare_amount"]))),"currency":"VND"}})
        receipt=self.publish("ds03.payment.events.v1",wrong_attempt)
        self.wait_consumed("ds03.payment.events.v1",receipt,"trip-payment-v1")
        self.check("payment-wrong-attempt-ignored",self.trip(trip["id"])["payment_status"]=="PENDING","Ignore wrong attempt with correct payment ID",self.trip(trip["id"])["payment_status"])
        failed=self.envelope("PaymentFailed","payment",trip["id"],{**wrong_attempt["data"],"attempt_number":payment["attempt_number"],"failure_code":"SIMULATED_DECLINE"})
        receipt=self.publish("ds03.payment.events.v1",failed)
        self.wait_consumed("ds03.payment.events.v1",receipt,"trip-payment-v1")
        self.check("payment-valid-failure",self.trip(trip["id"])["payment_status"]=="FAILED","Apply matching payment failure",self.trip(trip["id"])["payment_status"])
        self.require(self.action(trip["id"],"retry-payment",user=passenger["id"]),(202,))
        updated=self.sql("trip",f"SELECT payment_id,attempt_number FROM trip WHERE id='{trip['id']}'")[0]
        self.check("payment-retry-preserves-id",updated["payment_id"]==payment["payment_id"] and updated["attempt_number"]==payment["attempt_number"]+1,"Same payment ID, next attempt",updated)
        late=self.envelope("PaymentSucceeded","payment",trip["id"],{**wrong_attempt["data"],"attempt_number":payment["attempt_number"]})
        receipt=self.publish("ds03.payment.events.v1",late)
        self.wait_consumed("ds03.payment.events.v1",receipt,"trip-payment-v1")
        self.check("payment-old-attempt-ignored",self.trip(trip["id"])["payment_status"]=="PENDING","Old result cannot settle the new attempt",self.trip(trip["id"])["payment_status"])
        valid=self.envelope("PaymentSucceeded","payment",trip["id"],{**wrong_attempt["data"],"attempt_number":updated["attempt_number"]})
        self.publish("ds03.payment.events.v1",valid)
        receipt=self.publish("ds03.payment.events.v1",valid)
        self.wait_consumed("ds03.payment.events.v1",receipt,"trip-payment-v1")
        self.check("payment-consumer-success",self.trip(trip["id"])["payment_status"]=="SUCCEEDED","Accept current payment result",self.trip(trip["id"])["payment_status"])
        rows=self.sql("trip",f"SELECT event_id FROM trip_processed_event WHERE event_id='{valid['event_id']}'")
        self.check("payment-event-dedup",len(rows)==1,"One durable processed event record",rows)
        self.report["saga_trip"] = trip["id"]

    def test_invalid_transitions(self, trip, passenger):
        status, operation=self.action(trip["id"],"accept",user=self.driver["id"])
        self.check("accept-requires-offer-and-reservation",status in (403,409),"Reject accept without a live offer",{"status":status,"body":operation})

    def test_stale_matching(self):
        token=self.driver["tokens"]["access_token"]
        self.require(self.http("driver","PATCH","/api/v1/drivers/me/availability",{"accepting_trips":False},token=token,key=uid()))
        passenger=self.account("stale-attempt")
        trip,_,_,_=self.quote_trip(passenger)
        self.wait(lambda: self.attempt(trip["attempt_id"]), lambda rows: rows and rows[0]["state"] == "SEARCHING" and rows[0]["next_run"] > time.time())
        event=self.envelope("MatchingFailed","matching",trip["id"],{"trip_id":trip["id"],"attempt_id":uid(),"reason":"NO_DRIVER"})
        receipt=self.publish("ds03.matching.events.v1",event)
        self.wait_consumed("ds03.matching.events.v1",receipt,"trip-matching-v1")
        observed=self.trip(trip["id"])
        self.check("stale-matching-attempt-ignored",observed["status"]=="SEARCHING","Ignore failure for the old attempt",observed)
        self.require(self.action(trip["id"],"cancel",user=passenger["id"]),(202,))
        self.require(self.http("driver","PATCH","/api/v1/drivers/me/availability",{"accepting_trips":True},token=token,key=uid()))

    def healthy(self, service, path=None):
        def probe():
            try:
                status,_=self.http(service,"GET",f"/internal/{path or service}/health/ready",record=False)
                return status==200
            except (OSError,urllib.error.URLError): return False
        self.wait(probe,timeout=50)

    def test_saga_recovery(self):
        account=self.account("recovery")
        trip,_,_,_=self.quote_trip(account)
        self.wait(lambda:self.trip(trip["id"]),lambda t:t["status"]=="DRIVER_OFFERED")
        token=self.driver["tokens"]["access_token"]
        for action in ("accept","arrive","start"):
            self.require(self.action(trip["id"],action,user=self.driver["id"],token=token),(202,))
        row=self.sql("trip",f"SELECT reservation_id,version FROM trip WHERE id='{trip['id']}'")[0]
        self.docker(["restart","pricing-service"])
        self.healthy("pricing")
        self.report["fixtures"].append({"kind":"fault injection","action":"restart pricing-service after quote accepted","trip_id":trip["id"]})
        self.intentional_driver_down=True
        try:
            self.docker(["stop","driver-service"])
            key=uid()
            status,operation=self.http("trip","POST",f"/api/v1/trips/{trip['id']}/complete",{"expected_version":row["version"]},user=self.driver["id"],token=token,key=key)
            self.require((status,operation),(202,))
            completed=self.wait(lambda:self.trip(trip["id"]),lambda t:t["status"]=="COMPLETED",timeout=25)
            pending=self.require(self.http("trip","GET",f"/api/v1/operations/{operation['id']}",user=account["id"]))
            held=self.sql("driver",f"SELECT status FROM driver_reservation WHERE id='{row['reservation_id']}'")[0]
            self.check("pricing-quote-survives-restart",completed["status"]=="COMPLETED","Final fare succeeds from the persisted accepted quote after Pricing restarts",completed)
            self.check("complete-driver-down-stays-pending",pending["status"]=="PENDING" and held["status"]=="CONFIRMED","Trip COMPLETED but operation PENDING until Driver release",{"operation":pending,"reservation":held})
            self.docker(["restart","trip-service"])
            self.healthy("trip")
            recovered=self.require(self.http("trip","GET",f"/api/v1/operations/{operation['id']}",user=account["id"]))
            self.check("saga-pending-survives-trip-restart",recovered["status"]=="PENDING","Persisted pending operation survives Trip restart",recovered)
            replay_status,replay=self.http("trip","POST",f"/api/v1/trips/{trip['id']}/complete",{"expected_version":row["version"]},user=self.driver["id"],token=token,key=key)
            self.check("complete-replay-during-recovery",replay_status==202 and replay["id"]==operation["id"],"Retry returns the original pending operation",{"status":replay_status,"operation":replay})
        finally:
            self.docker(["start","driver-service"])
            self.healthy("driver")
            self.intentional_driver_down=False
        finished=self.wait(lambda:self.require(self.http("trip","GET",f"/api/v1/operations/{operation['id']}",user=account["id"],record=False)),lambda op:op["status"]=="SUCCEEDED",timeout=50)
        released=self.sql("driver",f"SELECT status FROM driver_reservation WHERE id='{row['reservation_id']}'")[0]
        events=self.sql("trip",f"SELECT event_type FROM outbox_event WHERE aggregate_id='{trip['id']}' AND event_type IN ('TripCompleted','PaymentRequested')")
        self.check("saga-resumes-release-after-driver-recovery",released["status"]=="RELEASED","Persisted operation releases Driver after service recovery",{"operation":finished,"reservation":released})
        self.check("saga-recovery-does-not-duplicate-payment",len(events)==2,"One TripCompleted and one PaymentRequested despite restart/replay",events)

    def cleanup(self):
        self.heartbeat_stop.set()
        if self.heartbeat_thread:
            self.heartbeat_thread.join(timeout=15)
        errors = []
        if hasattr(self, "driver_id"):
            try:
                rows = self.sql("driver", f"SELECT id,trip_id FROM driver_reservation WHERE driver_id='{self.driver_id}' AND status IN ('HELD','CONFIRMED')")
                for row in rows:
                    self.rpc("release", {"reservation_id": row["id"], "trip_id": row["trip_id"], "key": uid()}, caller="trip")
                self.http("driver", "PATCH", "/api/v1/drivers/me/availability", {"accepting_trips": False}, token=self.driver["tokens"]["access_token"], key=uid())
            except Exception as error:
                errors.append(str(error))
        for trip_id, passenger_id in self.trips:
            try:
                if self.trip(trip_id)["status"] not in ("COMPLETED", "CANCELLED", "NO_DRIVER"):
                    self.action(trip_id, "cancel", user=passenger_id)
            except Exception as error:
                errors.append(str(error))
        if self.admin:
            try:
                self.set_admin_fixture_role("PASSENGER")
            except Exception as error:
                errors.append(str(error))
        self.report["cleanup"] = {"retained": "Test accounts/trips/events retained as evidence; driver switched offline, reservations released, admin fixture demoted", "errors": errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", help="Optional Compose env file, e.g. deploy/.env")
    parser.add_argument("--report", default="deploy/backend-flow-report.json")
    args = parser.parse_args()
    check = None
    code = 2
    try:
        check = FlowCheck(args)
        print("Run " + check.run_id, flush=True)
        check.run()
        code = int(any(row["result"] == "FAIL" for row in check.report["checks"]))
    except Exception as error:
        if check:
            check.report["runtime_error"] = str(error)
        print("TEST SETUP/RUNTIME ERROR: " + str(error), flush=True)
    finally:
        if check:
            check.cleanup()
            check.report["finished_at"] = now()
            check.report["summary"] = {state: sum(row["result"] == state for row in check.report["checks"]) for state in ("PASS", "FAIL")}
            destination = ROOT / args.report
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(json.dumps(redacted(check.report), indent=2, ensure_ascii=False), encoding="utf-8")
            print(json.dumps(check.report["summary"]) + "; report=" + str(destination), flush=True)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
