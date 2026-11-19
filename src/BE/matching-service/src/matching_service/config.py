from pathlib import Path

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    service_name: str = "matching-service"
    http_host: str = "127.0.0.1"
    http_port: int = Field(default=8084, ge=1, le=65535)
    database_url: SecretStr = SecretStr("postgresql+asyncpg://postgres@localhost:5432/matching")
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_consumer_group: str = "matching-v1"
    kafka_trip_topic: str = "ds03.trip.events.v1"
    kafka_matching_topic: str = "ds03.matching.events.v1"
    driver_grpc_address: str = "localhost:9091"
    driver_grpc_bearer_token: SecretStr = SecretStr("")
    driver_grpc_tls: bool = False
    driver_grpc_ca_file: Path | None = None
    matching_radius_m: int = Field(default=5000, ge=100, le=10000)
    matching_candidate_limit: int = Field(default=20, ge=1, le=20)
    reservation_hold_seconds: int = Field(default=30, ge=15, le=60)
    worker_poll_seconds: float = Field(default=0.5, gt=0)
    worker_lease_seconds: int = Field(default=30, ge=10)
    grpc_query_timeout_seconds: float = Field(default=2, gt=0, le=10)
    grpc_mutation_timeout_seconds: float = Field(default=3, gt=0, le=10)
    log_level: str = "INFO"

    @model_validator(mode="after")
    def validate_transport(self):
        if not self.database_url.get_secret_value().startswith("postgresql+asyncpg://"):
            raise ValueError("DATABASE_URL must use postgresql+asyncpg://")
        if self.worker_lease_seconds <= self.grpc_mutation_timeout_seconds + 5:
            raise ValueError("Worker lease must exceed an RPC deadline plus DB processing time")
        if self.driver_grpc_ca_file and not self.driver_grpc_tls:
            raise ValueError("DRIVER_GRPC_CA_FILE requires DRIVER_GRPC_TLS=true")
        return self
