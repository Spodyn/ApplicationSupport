# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-noble@sha256:8599ea2d8f71696f00dcdf46213a4b65fa97ceeaa318671fccba6b0af2727792 AS build
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY apps/api/.mvn .mvn
COPY apps/api/mvnw apps/api/pom.xml ./
RUN chmod +x mvnw
COPY apps/api/src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw --batch-mode package -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre-noble@sha256:3c6d7b40c2e78d4a8f4fe00af8165c706b34faf672dc21ff0924e583559ec6c2
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 usi && useradd --uid 10001 --gid 10001 --no-create-home usi
WORKDIR /opt/usi
COPY --from=build --chown=10001:10001 /build/target/usi-api-0.0.1-SNAPSHOT.jar app.jar
COPY --chmod=755 infra/staging/api-entrypoint.sh /opt/usi/entrypoint.sh
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["/opt/usi/entrypoint.sh"]
