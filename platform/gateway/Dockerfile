FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source="https://github.com/hxj04121-lab/FoodLabelFlow-Microservices"
WORKDIR /app
COPY --from=build /build/target/spectrace-gateway-0.1.0-SNAPSHOT.jar /app/gateway.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]
