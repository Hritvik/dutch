# Build one Spring Boot service from the shared multi-module Gradle build.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
ARG SERVICE
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew ":services:${SERVICE}:bootJar" --no-daemon -x test
RUN find "services/${SERVICE}/build/libs" -name '*.jar' ! -name '*-plain.jar' \
    -exec cp {} /workspace/app.jar \;

FROM eclipse-temurin:25-jre
ARG SERVICE
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="dutch-${SERVICE}" \
      org.opencontainers.image.source="https://github.com/dutch/dutch" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
RUN useradd --system --uid 10001 --no-create-home dutch
COPY --from=build /workspace/app.jar /app/app.jar
USER dutch
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
