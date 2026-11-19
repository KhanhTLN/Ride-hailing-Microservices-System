FROM python:3.12-slim
WORKDIR /app
COPY src/BE/matching-service/requirements.lock.txt ./requirements.lock.txt
RUN pip install --no-cache-dir -r requirements.lock.txt
COPY ds03-api-contracts-v1 /contracts
COPY src/BE/matching-service/matching /app/matching
COPY src/BE/matching-service/scripts /app/scripts
ENV CONTRACTS_DIR=/contracts HEALTH_HOST=0.0.0.0
RUN python scripts/generate_proto.py
EXPOSE 8084
CMD ["python", "-m", "matching", "run"]
