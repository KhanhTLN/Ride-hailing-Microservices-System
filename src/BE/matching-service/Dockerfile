FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
COPY migrations ./migrations
COPY alembic.ini ./
RUN pip install --no-cache-dir . && useradd --create-home matching
USER matching
ENV HTTP_HOST=0.0.0.0
EXPOSE 8084
CMD ["matching-service"]
