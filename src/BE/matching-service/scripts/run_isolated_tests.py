"""Boot a disposable PostgreSQL cluster; never use the user's DATABASE_URL.

Requires initdb, postgres, pg_ctl and createdb on PATH. Kafka tests can additionally
use TEST_KAFKA_BOOTSTRAP_SERVERS. All test database names start with matching_test_.
"""

import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from uuid import uuid4


def main():
    service = Path(__file__).resolve().parents[1]
    for name in ("initdb", "postgres", "pg_ctl", "createdb"):
        if not shutil.which(name):
            raise SystemExit(f"{name} is not on PATH")
    root = Path(tempfile.mkdtemp(prefix="codex-matching-tests-"))
    data = root / "data"
    process = None
    try:
        subprocess.run(
            [
                "initdb",
                "-D",
                str(data),
                "-U",
                "postgres",
                "--auth=trust",
                "--encoding=UTF8",
                "--no-locale",
                "--no-sync",
            ],
            check=True,
            capture_output=True,
        )
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        with (root / "postgres.log").open("wb") as logfile:
            process = subprocess.Popen(
                ["postgres", "-D", str(data), "-h", "127.0.0.1", "-p", str(port), "-c", "fsync=off"],
                stdout=logfile,
                stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            )
        for _ in range(100):
            if process.poll() is not None:
                raise RuntimeError("Temporary PostgreSQL exited during startup")
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                    break
            except OSError:
                time.sleep(0.1)
        else:
            raise RuntimeError("Temporary PostgreSQL startup timed out")
        database = "matching_test_" + uuid4().hex
        subprocess.run(
            ["createdb", "-h", "127.0.0.1", "-p", str(port), "-U", "postgres", database], check=True
        )
        env = os.environ.copy()
        env["TEST_DATABASE_URL"] = f"postgresql+asyncpg://postgres@127.0.0.1:{port}/{database}"
        env["DATABASE_URL"] = env["TEST_DATABASE_URL"]
        env["PYTHONPATH"] = str(service / "src")
        subprocess.run([sys.executable, "-m", "alembic", "upgrade", "head"], cwd=service, env=env, check=True)
        print(
            "Running tests against a disposable database (the local matching database is untouched)",
            flush=True,
        )
        return subprocess.run(
            [sys.executable, "-m", "pytest", *(sys.argv[1:] or ["-q"])], cwd=service, env=env
        ).returncode
    except subprocess.CalledProcessError as exc:
        if exc.stderr:
            print(exc.stderr.decode(errors="replace"), file=sys.stderr)
        raise
    finally:
        if process is not None and process.poll() is None:
            subprocess.run(
                ["pg_ctl", "-D", str(data), "-m", "immediate", "-w", "stop"], capture_output=True, timeout=20
            )
            process.wait(timeout=20)
        # Only remove the verified directory allocated by this invocation.
        resolved = root.resolve()
        if resolved.parent == Path(tempfile.gettempdir()).resolve() and resolved.name.startswith(
            "codex-matching-tests-"
        ):
            shutil.rmtree(resolved)


if __name__ == "__main__":
    raise SystemExit(main())
