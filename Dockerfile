FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build
COPY maven-settings.xml /root/.m2/settings.xml
COPY pom.xml ./
COPY yoox-framework/pom.xml yoox-framework/
COPY yoox-framework/yoox-framework-context/pom.xml yoox-framework/yoox-framework-context/
COPY yoox-framework/yoox-framework-db/pom.xml yoox-framework/yoox-framework-db/
COPY yoox-framework/yoox-framework-mqtt/pom.xml yoox-framework/yoox-framework-mqtt/
COPY yoox-framework/yoox-framework-redis/pom.xml yoox-framework/yoox-framework-redis/
COPY yoox-framework/yoox-framework-storage/pom.xml yoox-framework/yoox-framework-storage/
COPY yoox-framework/yoox-framework-web/pom.xml yoox-framework/yoox-framework-web/
COPY yoox-framework/yoox-framework-websocket/pom.xml yoox-framework/yoox-framework-websocket/
COPY cloud-api/pom.xml cloud-api/
COPY cloud-service/pom.xml cloud-service/
RUN mvn -B -DskipTests dependency:go-offline || true

COPY yoox-framework/ yoox-framework/
COPY cloud-api/ cloud-api/
COPY cloud-service/ cloud-service/
RUN mvn -B -DskipTests clean package -pl cloud-service -am

FROM eclipse-temurin:17-jre-jammy
LABEL org.opencontainers.image.title="YOOX Cloud GCS API"
LABEL org.opencontainers.image.vendor="YOOX"

RUN groupadd --system yoox && useradd --system --gid yoox --home /app yoox \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /build/cloud-service/target/cloud-service-*.jar /app/app.jar
RUN mkdir -p /app/logs && chown -R yoox:yoox /app

USER yoox
EXPOSE 9000
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=20s --timeout=5s --start-period=60s --retries=5 CMD curl -fsS http://127.0.0.1:9000/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/app.jar"]
