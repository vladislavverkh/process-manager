FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY process-manager-core process-manager-core
COPY process-manager-postgres process-manager-postgres
COPY process-manager-rest process-manager-rest
COPY process-manager-spring-boot-starter process-manager-spring-boot-starter
COPY process-manager-testkit process-manager-testkit
COPY process-manager-sample-app process-manager-sample-app

RUN chmod +x gradlew \
    && ./gradlew :process-manager-sample-app:bootJar --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /workspace/process-manager-sample-app/build/libs/process-manager-sample-app-*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
