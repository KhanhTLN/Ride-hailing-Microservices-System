FROM python:3.12-alpine
RUN apk add --no-cache openssl
WORKDIR /app
COPY service_tokens.py /app/service_tokens.py
CMD ["python", "-u", "/app/service_tokens.py"]
