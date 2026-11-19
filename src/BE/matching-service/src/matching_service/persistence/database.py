from contextlib import asynccontextmanager

from sqlalchemy import text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass


class Database:
    def __init__(self, url: str):
        self.engine = create_async_engine(
            url,
            pool_pre_ping=True,
            connect_args={"server_settings": {"timezone": "UTC"}, "timeout": 5, "command_timeout": 10},
            hide_parameters=True,
        )
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)

    @asynccontextmanager
    async def transaction(self):
        async with self.sessions.begin() as session:
            yield session

    async def ready(self):
        async with self.engine.connect() as connection:
            # Fixed internal table names; never interpolate names supplied by clients.
            for name in (
                "matching_attempts",
                "rpc_operations",
                "processed_events",
                "cancellation_tombstones",
                "message_failures",
                "event_sequences",
                "outbox_events",
            ):
                await connection.execute(text(f"SELECT 1 FROM public.{name} LIMIT 0"))

    async def close(self):
        await self.engine.dispose()
