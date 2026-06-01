# grader/Dockerfile — Spring Boot backend (multi-stage)
# ============================================================================
#  Stage 1: build .jar bằng Maven (cache dependency để build lại nhanh)
#  Stage 2: runtime JRE gọn + docker CLI (để build/run ảnh chấm qua docker.sock)
# ============================================================================

# ---- Build stage ----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# Cache dependencies trước (chỉ chạy lại khi pom.xml đổi)
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- Runtime stage --------------------------------------------------------
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# docker CLI: backend cần gọi `docker build` / `docker run` qua docker.sock mount từ host
RUN apt-get update \
    && apt-get install -y --no-install-recommends docker.io \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
