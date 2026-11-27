"""Local Compose fixture for Driver's RS256 service authentication.

This is not an Identity Service. Private signing material stays in its own
volume; each backend receives only its public configuration or its own token.
"""
import base64
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time
from uuid import uuid4

KEY = Path("/keys/private.pem")
AUTH = Path("/auth")
ISSUER = "rhds-local-compose"
CALLERS = {"matching": "driver:match", "trip": "driver:trip"}


def openssl(*args, data=None):
    return subprocess.run(
        ["openssl", *args], input=data, capture_output=True, check=True
    ).stdout


def base64url(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def write_atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(value, encoding="utf-8")
    temporary.chmod(0o644)
    os.replace(temporary, path)


def initialize():
    KEY.parent.mkdir(parents=True, exist_ok=True)
    KEY.parent.chmod(0o700)
    if not KEY.exists():
        temporary = KEY.with_suffix(".tmp")
        openssl("genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(temporary))
        temporary.chmod(0o600)
        os.replace(temporary, KEY)
    public_key = openssl("pkey", "-in", str(KEY), "-pubout", "-outform", "DER")
    write_atomic(AUTH / "driver/driver.grpc.token-public-key", base64.b64encode(public_key).decode("ascii"))
    write_atomic(AUTH / "driver/driver.grpc.token-issuer", ISSUER)


def refresh():
    now = int(time.time())
    header = base64url(b'{"alg":"RS256","typ":"JWT"}')
    for caller, scope in CALLERS.items():
        claims = {"iss": ISSUER, "sub": f"{caller}-service", "aud": "driver-service",
                  "scope": scope, "iat": now, "exp": now + 240, "jti": str(uuid4())}
        payload = base64url(json.dumps(claims, separators=(",", ":")).encode())
        message = f"{header}.{payload}"
        signature = openssl("dgst", "-sha256", "-sign", str(KEY), data=message.encode())
        write_atomic(AUTH / caller / "token", f"{message}.{base64url(signature)}")


def check():
    for caller in CALLERS:
        payload = (AUTH / caller / "token").read_text().split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
        if claims["exp"] <= time.time() + 30:
            raise RuntimeError("Local service tokens need refreshing")
    for property_name in ("driver.grpc.token-public-key", "driver.grpc.token-issuer"):
        if not (AUTH / "driver" / property_name).read_text().strip():
            raise RuntimeError("Missing Driver authentication configuration")


def main():
    if sys.argv[1:] == ["--check"]:
        check()
        return
    stopping = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stopping.set())
    initialize()
    while not stopping.is_set():
        refresh()
        print("Refreshed local Driver tokens for Matching and Trip", flush=True)
        stopping.wait(60)


if __name__ == "__main__":
    main()
