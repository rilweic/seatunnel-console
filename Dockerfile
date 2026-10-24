# syntax=docker/dockerfile:1
# Build context: the seatunnel-console directory. Usually built through docker/docker-compose.yml.
#   docker build -t seatunnel-console .

FROM node:22-alpine AS ui
WORKDIR /build/console-ui
COPY console-ui/package.json console-ui/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY console-ui/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS server
WORKDIR /build
COPY pom.xml ./
COPY console-common/ console-common/
COPY console-engine/ console-engine/
COPY console-datasource/ console-datasource/
COPY console-server/ console-server/
# The SPA is served by Spring Boot from classpath:/static.
COPY --from=ui /build/console-ui/dist/ console-server/src/main/resources/static/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
# The console works in local time (scheduled times, variables); keep it in line with MySQL and XXL-JOB.
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx1g"
RUN groupadd --system console && useradd --system --gid console --home /app console \
    && mkdir -p /app/data /app/logs && chown -R console:console /app
WORKDIR /app
COPY --from=server --chown=console:console /build/console-server/target/seatunnel-console.jar app.jar
USER console
# data: archived SeaTunnel job logs (console.data-dir); logs: XXL-JOB executor logs
VOLUME ["/app/data", "/app/logs"]
# 8088: web UI and API; 9999: XXL-JOB executor, called by xxl-job-admin
EXPOSE 8088 9999
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
