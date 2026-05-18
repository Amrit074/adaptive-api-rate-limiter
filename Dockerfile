FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre

WORKDIR /app
RUN addgroup --system app && adduser --system --ingroup app app

COPY --from=build /workspace/target/smart-rate-limiter-1.0.0.jar app.jar

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
