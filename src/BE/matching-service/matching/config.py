from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

ROOT = Path(__file__).resolve().parents[1]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=ROOT / ".env", extra="ignore")
    demo_mode: bool = False
    database_url: str = "postgresql+psycopg://matching:matching_demo@localhost:5544/matching_db"
    contracts_dir: Path = ROOT.parents[2] / "ds03-api-contracts-v1"
    kafka_bootstrap_servers: str = "localhost:19092"
    kafka_security_protocol: str = "PLAINTEXT"
    kafka_sasl_mechanism: str = "PLAIN"
    kafka_sasl_username: str = ""
    kafka_sasl_password: str = ""
    kafka_ca_file: str = ""
    trip_topic: str = "ds03.trip.events.v1"
    matching_topic: str = "ds03.matching.events.v1"
    consumer_group: str = "matching-v1"
    driver_grpc_target: str = "localhost:50052"
    driver_tls: bool = True
    driver_ca_file: str = ""
    user_token_url: str = ""
    service_client_id: str = "matching-service"
    service_client_secret: str = ""
    health_host: str = "127.0.0.1"
    health_port: int = 8084

    def kafka_config(self):
        config = {"bootstrap.servers": self.kafka_bootstrap_servers,
                  "security.protocol": self.kafka_security_protocol}
        if "SASL" in self.kafka_security_protocol:
            config.update({"sasl.mechanism": self.kafka_sasl_mechanism,
                           "sasl.username": self.kafka_sasl_username,
                           "sasl.password": self.kafka_sasl_password})
        if self.kafka_ca_file:
            config["ssl.ca.location"] = self.kafka_ca_file
        return config

    def check(self):
        if not self.demo_mode:
            if not self.driver_tls or not self.user_token_url.startswith("https://"):
                raise ValueError("Real integration requires Driver TLS and HTTPS USER_TOKEN_URL")
            if not self.service_client_secret:
                raise ValueError("Configure the matching service client at user-service first")
            if self.kafka_security_protocol not in ("SSL", "SASL_SSL"):
                raise ValueError("Real integration requires Kafka TLS")
