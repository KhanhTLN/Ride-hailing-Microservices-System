import logging
import sys
from uuid import UUID, uuid4

from .config import Settings
from .db import Database


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    settings = Settings()
    db = Database(settings.database_url)
    command = sys.argv[1] if len(sys.argv) > 1 else "run"
    if command == "init-db":
        db.initialize()
        print("Matching tables initialized. Existing tables/data are preserved.")
        return
    if command != "run":
        raise SystemExit("Usage: python -m matching [init-db|run]")
    settings.check()
    from confluent_kafka.admin import AdminClient
    from fastapi import FastAPI, Request
    from fastapi.responses import JSONResponse
    from sqlalchemy import text, select
    import uvicorn
    from .contracts import Contracts
    from .db import Attempt
    from .driver import DriverClient
    from .runtime import Runtime
    from .workflow import Workflow

    driver = DriverClient(settings)
    workflow = Workflow(db, Contracts(settings.contracts_dir), driver, settings)
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    admin = AdminClient(settings.kafka_config())
    with Runtime(workflow, settings) as runtime:
        @app.get("/internal/matching/health/live")
        def live():
            return {"status": "ok", "service": "matching"}

        @app.get("/internal/matching/health/ready")
        def ready(request: Request):
            correlation = request.headers.get("X-Correlation-ID", "")
            try:
                correlation = str(UUID(correlation))
            except ValueError:
                correlation = str(uuid4())
            try:
                if runtime.failed or runtime.stop.is_set() or not all(t.is_alive() for t in runtime.threads):
                    raise RuntimeError("WORKER_STOPPED")
                with db.session() as session:
                    session.execute(text("SELECT 1"))
                    if session.scalar(select(Attempt.attempt_id).where(Attempt.state == "NEEDS_REVIEW").limit(1)):
                        raise RuntimeError("RECONCILIATION_REQUIRED")
                topics = admin.list_topics(timeout=1).topics
                if any(topic not in topics or topics[topic].error for topic in
                       (settings.trip_topic, settings.matching_topic)):
                    raise RuntimeError("TOPICS_UNAVAILABLE")
                driver.ready()
                driver.metadata(correlation)
                return JSONResponse({"status": "ok", "service": "matching"},
                                    headers={"X-Correlation-ID": correlation})
            except Exception:
                return JSONResponse(status_code=503, headers={"X-Correlation-ID": correlation},
                    content={"code": "DEPENDENCY_UNAVAILABLE", "message": "Matching is not ready",
                             "correlation_id": correlation, "retryable": True, "details": {}})

        uvicorn.run(app, host=settings.health_host, port=settings.health_port, log_level="info")


if __name__ == "__main__":
    main()
