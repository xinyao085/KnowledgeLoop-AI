FROM m.daocloud.io/docker.io/library/maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B package

FROM m.daocloud.io/docker.io/library/eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get -o Acquire::Retries=5 update \
    && apt-get -o Acquire::Retries=5 install -y --no-install-recommends tesseract-ocr tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/target/server-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
