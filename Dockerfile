FROM maven:3.10.0-eclipse-temurin-21-noble AS build
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system balanceplatform \
    && useradd --system --gid balanceplatform --home-dir /app balanceplatform \
    && mkdir /app/data
COPY python-src/ ./python-src/
COPY --from=build /workspace/target/BalancePlatform-0.0.1-SNAPSHOT.jar ./app.jar

USER balanceplatform
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
