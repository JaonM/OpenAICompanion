FROM python:3.13-slim
WORKDIR /app
COPY scripts/requirements-service.txt .
RUN pip install --no-cache-dir -r requirements-service.txt \
    && useradd --uid 10001 --create-home companion \
    && mkdir -m 700 /data && chown companion:companion /data
COPY scripts/*.py ./
COPY scripts/service_logging.json ./
USER companion
ENV COMPANION_SERVICE_DATA=/data
EXPOSE 9444
CMD ["uvicorn", "companion_service:create_app", "--factory", "--host", "0.0.0.0", "--port", "9444", "--workers", "1", "--limit-concurrency", "64", "--timeout-keep-alive", "5", "--timeout-graceful-shutdown", "30", "--no-proxy-headers", "--no-access-log", "--log-config", "/app/service_logging.json"]
