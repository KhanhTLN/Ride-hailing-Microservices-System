import asyncio
import os

from alembic import context
from sqlalchemy.ext.asyncio import create_async_engine

from matching_service.config import Settings
from matching_service.persistence import models  # noqa: F401
from matching_service.persistence.database import Base


def migrate(connection):
    context.configure(connection=connection, target_metadata=Base.metadata)
    with context.begin_transaction():
        context.run_migrations()


async def online():
    url = os.environ.get("DATABASE_URL") or Settings().database_url.get_secret_value()
    engine = create_async_engine(url, hide_parameters=True)
    async with engine.connect() as connection:
        await connection.run_sync(migrate)
    await engine.dispose()


if context.is_offline_mode():
    raise RuntimeError("Run migrations online with DATABASE_URL configured")
else:
    asyncio.run(online())
