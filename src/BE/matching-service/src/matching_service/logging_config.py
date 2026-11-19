import json
import logging
from datetime import UTC, datetime


class JsonFormatter(logging.Formatter):
    def format(self, record):
        value = {
            "timestamp": datetime.now(UTC).isoformat(),
            "service": "matching-service",
            "level": record.levelname,
            "event_type": record.getMessage(),
        }
        for name in ("attempt_id", "trip_id", "event_id", "correlation_id", "code", "offset", "result"):
            if hasattr(record, name):
                value[name] = getattr(record, name)
        return json.dumps(value, ensure_ascii=False)


def configure_logging(level="INFO"):
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    logging.basicConfig(level=level.upper(), handlers=[handler], force=True)
    # SQL parameters, broker payloads and metadata must not enter application logs.
    logging.getLogger("sqlalchemy.engine").setLevel(logging.WARNING)
    logging.getLogger("aiokafka").setLevel(logging.CRITICAL)
