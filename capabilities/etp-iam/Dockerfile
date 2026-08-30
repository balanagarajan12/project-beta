# Build stage not required — artifact is produced by Maven in CI or locally.
# Example: mvn -q -DskipTests package && docker build -t etp-iam:local .

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S etp && adduser -S etp -G etp
USER etp

COPY target/etp-iam-*.jar /app/app.jar

EXPOSE 8081

ENV ETP_IAM_SERVER_PORT=8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
