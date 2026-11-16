FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY docker/maven-settings.xml /build/docker/maven-settings.xml
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -s /build/docker/maven-settings.xml package
FROM eclipse-temurin:21-jre
RUN groupadd --gid 10001 limiter && useradd --uid 10001 --gid limiter --no-create-home limiter
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/distributed-rate-limiter-1.0.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-XX:+ExitOnOutOfMemoryError","-jar","app.jar"]
