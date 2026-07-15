# ===== 多阶段构建 =====

# 阶段1: 构建前端
FROM node:20-alpine AS frontend-build

WORKDIR /app/vue-code

# 设置 npm 镜像源
RUN npm config set registry https://registry.npmmirror.com

# 先复制依赖文件，利用缓存
COPY vue-code/package.json vue-code/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

# 复制前端源码并构建
COPY vue-code/ ./
RUN npm run type-check && npm run build:spring

# 阶段2: 构建后端 JAR
FROM eclipse-temurin:21-jdk-jammy AS backend-build

WORKDIR /app

# 先复制 Maven 配置和 pom.xml，利用缓存
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd pom.xml ./
RUN chmod +x mvnw

# 复制前端构建产物到 static 目录
COPY --from=frontend-build /app/vue-code/../src/main/resources/static src/main/resources/static/

# 复制后端源码
COPY src/ src/

# 构建 JAR并执行测试
RUN --mount=type=cache,target=/root/.m2/repository ./mvnw clean package

# 预置 Playwright Chromium，保证容器内的 Cookie 与 Token 维护功能可用
RUN --mount=type=cache,target=/root/.m2/repository ./mvnw dependency:build-classpath -Dmdep.outputFile=target/classpath.txt \
    && PLAYWRIGHT_BROWSERS_PATH=/ms-playwright java -cp "target/classes:$(cat target/classpath.txt)" com.microsoft.playwright.CLI install chromium

# 阶段3: 运行时镜像
FROM eclipse-temurin:21-jre-jammy

LABEL org.opencontainers.image.title="XianYuSmart"
LABEL org.opencontainers.image.description="单商家私有化闲鱼虚拟商品经营助手"
LABEL org.opencontainers.image.version="1.0.0"
LABEL org.opencontainers.image.licenses="PolyForm-Noncommercial-1.0.0"

WORKDIR /app

# Chromium 仅在刷新凭证时按需启动，运行库不会产生常驻进程
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates fonts-liberation libasound2 libatk-bridge2.0-0 libatk1.0-0 \
        libatspi2.0-0 libcairo2 libcups2 libdbus-1-3 libdrm2 libfontconfig1 \
        libgbm1 libglib2.0-0 libgtk-3-0 libnspr4 libnss3 libpango-1.0-0 \
        libx11-6 libxcb1 libxcomposite1 libxdamage1 libxext6 libxfixes3 \
        libxkbcommon0 libxrandr2 libxshmfence1 wget \
    && rm -rf /var/lib/apt/lists/*

# 创建低权限运行用户和数据目录
RUN groupadd --system xianyusmart && useradd --system --gid xianyusmart --home-dir /app xianyusmart \
    && mkdir -p /app/data /app/logs \
    && chown -R xianyusmart:xianyusmart /app

# 从构建阶段复制 JAR
COPY --from=backend-build --chown=xianyusmart:xianyusmart /app/target/xianyusmart-1.0.0.jar app.jar
COPY --from=backend-build --chown=xianyusmart:xianyusmart /ms-playwright /app/ms-playwright

# 暴露端口
EXPOSE 12400

# 环境变量
ENV JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:InitialRAMPercentage=20 -XX:+ExitOnOutOfMemoryError"
ENV SERVER_PORT=12400
ENV PLAYWRIGHT_BROWSERS_PATH=/app/ms-playwright

USER xianyusmart

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:12400/actuator/health || exit 1

# 启动命令
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Dserver.port=${SERVER_PORT} -jar app.jar"]
