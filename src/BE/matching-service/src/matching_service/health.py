import asyncio
from uuid import UUID, uuid4

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

router = APIRouter()


def correlation(request):
    try:
        return str(UUID(request.headers["x-correlation-id"]))
    except (KeyError, ValueError):
        return str(uuid4())


@router.get("/internal/matching/health/live")
async def live(request: Request):
    return JSONResponse(
        {"status": "ok", "service": "matching"}, headers={"X-Correlation-ID": correlation(request)}
    )


@router.get("/internal/matching/health/ready")
async def ready(request: Request):
    runtime = request.app.state.runtime
    cid = correlation(request)
    try:
        async with asyncio.timeout(3):
            await runtime.db.ready()
            await runtime.driver.ready()
            if not runtime.transport.ready or any(task.done() for task in runtime.tasks):
                raise RuntimeError("Workers or Kafka not ready")
    except Exception:
        return JSONResponse(
            {
                "code": "DEPENDENCY_UNAVAILABLE",
                "message": "Matching dependencies are not ready",
                "correlation_id": cid,
                "retryable": True,
                "details": {},
            },
            status_code=503,
            headers={"X-Correlation-ID": cid},
        )
    return JSONResponse({"status": "ok", "service": "matching"}, headers={"X-Correlation-ID": cid})
