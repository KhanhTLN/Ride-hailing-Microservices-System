from contextlib import contextmanager
import time

from sqlalchemy import (JSON, Boolean, Column, Float, Integer, LargeBinary, String, UniqueConstraint, create_engine, text)
from sqlalchemy.orm import declarative_base, sessionmaker

Base = declarative_base()

class Inbox(Base):
    __tablename__ = "matching_inbox"
    id = Column(Integer, primary_key=True)
    topic = Column(String(255), nullable=False)
    partition = Column(Integer, nullable=False)
    offset = Column(String(30), nullable=False)
    key = Column(LargeBinary)
    raw = Column(LargeBinary, nullable=False)
    state = Column(String(20), default="PENDING", nullable=False)
    retries = Column(Integer, default=0, nullable=False)
    next_run = Column(Float, default=0, nullable=False)
    last_error = Column(String(80))
    __table_args__ = (UniqueConstraint("topic", "partition", "offset"),)


class Processed(Base):
    __tablename__ = "matching_processed_events"
    event_id = Column(String(36), primary_key=True)
    digest = Column(String(64), nullable=False)
    created_at = Column(Float, default=time.time, nullable=False)


class Cancellation(Base):
    __tablename__ = "matching_cancellations"
    attempt_id = Column(String(36), primary_key=True)
    trip_id = Column(String(36), nullable=False)
    created_at = Column(Float, default=time.time, nullable=False)


class Attempt(Base):
    __tablename__ = "matching_attempts"
    attempt_id = Column(String(36), primary_key=True)
    trip_id = Column(String(36), nullable=False, index=True)
    request = Column(JSON, nullable=False)
    deadline = Column(Float, nullable=False)
    state = Column(String(30), default="SEARCHING", nullable=False)
    candidates = Column(JSON)
    candidate_index = Column(Integer, default=0, nullable=False)
    driver_id = Column(String(36))
    reserve_key = Column(String(36))
    reserve_started = Column(Float)
    reservation = Column(JSON)
    cancel_requested = Column(Boolean, default=False, nullable=False)
    deadline_cleanup = Column(Boolean, default=False, nullable=False)
    retries = Column(Integer, default=0, nullable=False)
    next_run = Column(Float, default=0, nullable=False)
    last_error = Column(String(80))


class Sequence(Base):
    __tablename__ = "matching_sequences"
    trip_id = Column(String(36), primary_key=True)
    version = Column(Integer, nullable=False, default=0)


class Outbox(Base):
    __tablename__ = "matching_outbox"
    id = Column(Integer, primary_key=True)
    event_id = Column(String(36), unique=True, nullable=False)
    topic = Column(String(255), nullable=False)
    key = Column(String(255), nullable=False)
    payload = Column(JSON, nullable=False)
    published = Column(Boolean, default=False, nullable=False)


class Database:
    def __init__(self, url):
        self.engine = create_engine(url, pool_pre_ping=True)
        self.session = sessionmaker(self.engine, expire_on_commit=False)

    def initialize(self):
        # Explicit init-db command only. Do not execute DDL on application startup.
        Base.metadata.create_all(self.engine)

    @contextmanager
    def single_instance(self):
        # A dedicated session lock survives commits, but does not hold a transaction
        # around network calls. One runtime per DB is an explicit starter constraint.
        if self.engine.dialect.name != "postgresql":
            raise RuntimeError("Service runtime requires PostgreSQL; SQLite is for tests only")
        with self.engine.connect() as connection:
            acquired = connection.execute(text("SELECT pg_try_advisory_lock(7300301)")).scalar()
            connection.commit()
            if not acquired:
                raise RuntimeError("Another Matching runtime already owns this database")
            try:
                yield connection
            finally:
                connection.execute(text("SELECT pg_advisory_unlock(7300301)"))
                connection.commit()
