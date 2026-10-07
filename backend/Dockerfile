# TaskMesh backend — multi-stage build
# Build:  docker build -t taskmesh-backend:0.1.0 ./backend
# Run:    docker run -p 8080:8080 -e TASKMESH_DB_URL=jdbc:postgresql://postgres:5432/taskmesh taskmesh-backend:0.1.0

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="TaskMesh Backend" \
      org.opencontainers.image.description="Distributed job processing and worker orchestration platform — REST API, queue sweeper, WebSocket events" \
      org.opencontainers.image.version="0.1.0" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/ParsaFathii/TaskMesh"
WORKDIR /app
RUN useradd --system --uid 10001 taskmesh
COPY --from=build /src/target/taskmesh-backend.jar app.jar
ENV TASKMESH_STORAGE_DIR=/var/lib/taskmesh
RUN mkdir -p /var/lib/taskmesh && chown -R taskmesh:taskmesh /var/lib/taskmesh
USER taskmesh
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/api/v1/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
