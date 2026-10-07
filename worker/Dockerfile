# TaskMesh worker — multi-stage build
# Build:  docker build -t taskmesh-worker:0.1.0 ./worker
# Run:    docker run -e TASKMESH_DATABASE_URL=postgresql://taskmesh:...@postgres:5432/taskmesh taskmesh-worker:0.1.0

FROM python:3.12-slim AS build
WORKDIR /src
COPY pyproject.toml README.md ./
COPY src ./src
RUN pip install --no-cache-dir build && python -m build --wheel --outdir /dist

FROM python:3.12-slim
LABEL org.opencontainers.image.title="TaskMesh Worker" \
      org.opencontainers.image.description="TaskMesh Python worker runtime — job claiming, 7 built-in handlers, heartbeat, control API" \
      org.opencontainers.image.version="0.1.0" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/ParsaFathii/TaskMesh"
WORKDIR /app
# Pillow needs no extra system libs with official wheels; keep the image minimal.
COPY --from=build /dist/*.whl /tmp/
RUN pip install --no-cache-dir /tmp/*.whl \
    && rm /tmp/*.whl \
    && useradd --system --uid 10001 taskmesh
ENV TASKMESH_STORAGE_DIR=/var/lib/taskmesh \
    TASKMESH_CONTROL_PORT=9100
RUN mkdir -p /var/lib/taskmesh && chown -R taskmesh:taskmesh /var/lib/taskmesh
USER taskmesh
EXPOSE 9100
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:9100/health', timeout=4).status==200 else 1)"
ENTRYPOINT ["python", "-m", "taskmesh_worker"]
