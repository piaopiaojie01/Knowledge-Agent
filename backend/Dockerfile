# Spring Boot 后端 —— 多阶段构建（构建镜像 → JRE 运行镜像）

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 构建代理（部署时通过 --build-arg 或 compose args 注入，解决容器内访问 Maven 中央仓库不通）
ARG HTTP_PROXY=""
ARG HTTPS_PROXY=""
ARG NO_PROXY=""
ENV http_proxy=${HTTP_PROXY} https_proxy=${HTTPS_PROXY} no_proxy=${NO_PROXY}

# 先拷贝 pom 以复用依赖层缓存
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B package -DskipTests

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 ka \
    && mkdir -p /app/charts /app/icons \
    && chown -R ka:ka /app

COPY --from=build /build/target/knowledge-agent-backend-1.0.0.jar /app/app.jar

USER ka
EXPOSE 8080

# 生产默认 profile；compose 可用 SPRING_PROFILES_ACTIVE 覆盖
ENV SPRING_PROFILES_ACTIVE=prod

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD ["curl", "-fsS", "http://127.0.0.1:8080/api/health", "-o", "/dev/null"] || true

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
