FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache ffmpeg

WORKDIR /app

# 多时区批 1（docs/renovation/MULTI_TIMEZONE_DESIGN.md S20/D5）：把容器与 JVM 的默认时区显式钉成 UTC。
# 探针（2026-09-18，kind）显示改动前 user.timezone 属性未设置、/etc/localtime 不存在，JVM 默认时区只是
# 「恰好」为 UTC；这里把环境巧合变成显式契约，避免基础镜像或 tzdata 变化后墙钟口径静默漂移。
ENV TZ=UTC

ARG JAR_PATH
ARG IMAGE_VERSION
ARG IMAGE_REVISION
ARG IMAGE_CREATED
ARG IMAGE_SOURCE

LABEL org.opencontainers.image.version=$IMAGE_VERSION \
      org.opencontainers.image.revision=$IMAGE_REVISION \
      org.opencontainers.image.created=$IMAGE_CREATED \
      org.opencontainers.image.source=$IMAGE_SOURCE

COPY ${JAR_PATH} app.jar

EXPOSE 3001 3002 3100 3200 3300 3400 3500

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]
