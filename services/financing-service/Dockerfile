# syntax=docker/dockerfile:1
# Build from the repository root: docker build -f services/financing-service/Dockerfile .

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
ENV MAVEN_OPTS="-Dmaven.repo.local=/workspace/.m2"

# Dependency layer: rebuilt only when a POM changes.
COPY mvnw pom.xml ./
COPY .mvn/wrapper/maven-wrapper.properties .mvn/wrapper/
COPY platform-common/pom.xml platform-common/
COPY platform-messaging/pom.xml platform-messaging/
COPY services/auth-service/pom.xml services/auth-service/
COPY services/invoice-service/pom.xml services/invoice-service/
COPY services/financing-service/pom.xml services/financing-service/
COPY services/notification-service/pom.xml services/notification-service/
COPY services/audit-service/pom.xml services/audit-service/
COPY services/gateway/pom.xml services/gateway/
RUN chmod +x mvnw && ./mvnw -B -q -pl services/financing-service -am dependency:go-offline

COPY platform-common/src/main platform-common/src/main
COPY platform-messaging/src/main platform-messaging/src/main
COPY services/financing-service/src/main services/financing-service/src/main
RUN ./mvnw -B -q -pl services/financing-service -am package -DskipTests \
    && cp services/financing-service/target/financing-service-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app -h /app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./
USER 10001:10001

ENV SERVER_PORT=8082 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=25.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8082
HEALTHCHECK --interval=15s --timeout=5s --start-period=180s --start-interval=5s --retries=4 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT}/actuator/health/readiness" || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
