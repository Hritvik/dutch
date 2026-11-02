# Build one Spring Boot service from the shared multi-module Gradle build.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
ARG SERVICE
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew ":services:${SERVICE}:bootJar" --no-daemon -x test
RUN find "services/${SERVICE}/build/libs" -name '*.jar' ! -name '*-plain.jar' \
    -exec cp {} /workspace/app.jar \;

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home dutch
COPY --from=build /workspace/app.jar /app/app.jar
USER dutch
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
