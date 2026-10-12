# Build context: repository root (docker build -f services/specification/Dockerfile .)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY platform/starter-test platform/starter-test
RUN mvn -B -ntp -q -f platform/starter-test/pom.xml -DskipTests install
COPY platform/starter/pom.xml platform/starter/pom.xml
COPY platform/starter/src platform/starter/src
RUN mvn -B -ntp -q -f platform/starter/pom.xml -DskipTests install
COPY services/specification/pom.xml services/specification/pom.xml
RUN mvn -B -ntp -q -f services/specification/pom.xml dependency:go-offline
COPY services/specification/src services/specification/src
RUN mvn -B -ntp -q -f services/specification/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 spectrace
COPY --from=build /workspace/services/specification/target/app.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
