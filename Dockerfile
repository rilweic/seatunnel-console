# Build context: the seatunnel-console directory.
# docker build -t seatunnel-console:dev .

FROM node:22-alpine AS ui
WORKDIR /build/console-ui
COPY console-ui/package.json console-ui/package-lock.json ./
RUN npm ci
COPY console-ui/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS server
WORKDIR /build
COPY pom.xml ./
COPY console-common/ console-common/
COPY console-engine/ console-engine/
COPY console-datasource/ console-datasource/
COPY console-server/ console-server/
COPY --from=ui /build/console-ui/dist/ console-server/src/main/resources/static/
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=server /build/console-server/target/seatunnel-console.jar app.jar
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
