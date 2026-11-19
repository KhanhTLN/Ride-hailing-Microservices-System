import asyncio
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI

from matching_service.application.event_handlers import EventHandlers
from matching_service.application.matching_workflow import MatchingWorkflow
from matching_service.application.recovery import RecoveryWorker
from matching_service.clients.driver_client import DriverClient
from matching_service.config import Settings
from matching_service.health import router
from matching_service.logging_config import configure_logging
from matching_service.messaging.consumer import KafkaTransport
from matching_service.messaging.publisher import OutboxPublisher
from matching_service.persistence.database import Database
from matching_service.persistence.repository import MatchingRepository


class Runtime:
    def __init__(self, settings):
        self.db = Database(settings.database_url.get_secret_value())
        self.repository = MatchingRepository(self.db, settings)
        self.driver = DriverClient(settings)
        self.handlers = EventHandlers(self.repository)
        self.workflow = MatchingWorkflow(self.repository, self.driver)
        self.transport = KafkaTransport(settings, self.handlers, self.repository)
        self.recovery = RecoveryWorker(self.repository, self.workflow, self.handlers, settings)
        self.publisher = OutboxPublisher(self.repository, self.transport, settings)
        self.tasks = []

    async def start(self):
        self.tasks = [
            asyncio.create_task(component.run(), name=name)
            for name, component in (
                ("kafka", self.transport),
                ("recovery", self.recovery),
                ("outbox", self.publisher),
            )
        ]

    async def close(self):
        for task in self.tasks:
            task.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        await self.driver.close()
        await self.db.close()


def create_app(settings: Settings | None = None):
    settings = settings or Settings()

    @asynccontextmanager
    async def lifespan(app):
        configure_logging(settings.log_level)
        runtime = Runtime(settings)
        app.state.runtime = runtime
        try:
            await runtime.start()
            yield
        finally:
            await runtime.close()

    app = FastAPI(
        title="Matching Service", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None
    )
    app.include_router(router)
    return app


def run():
    settings = Settings()
    uvicorn.run(create_app(settings), host=settings.http_host, port=settings.http_port)


if __name__ == "__main__":
    run()
