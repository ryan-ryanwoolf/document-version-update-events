# Build with preinstalled Maven (avoids mvnw wget bootstrap; often fixes Alpine DNS issues in Docker)
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/target/document-version-update-events-*.jar app.jar

EXPOSE 3030

ENTRYPOINT ["java", "-jar", "app.jar"]
