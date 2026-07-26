# syntax=docker/dockerfile:1
FROM eclipse-temurin:21.0.11_10-jdk-jammy@sha256:9d8dcf999b0bce2453e913823595a5ff2a4e8e9e5d5241b45280d0ff069818ec AS builder

WORKDIR /workspace

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts gradle.properties settings.gradle.kts ./

RUN ./gradlew --no-daemon dependencies

COPY src ./src

RUN ./gradlew --no-daemon \
    -Dorg.gradle.jvmargs='-Xmx1g -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8' \
    bootJar

FROM builder AS djl-prefetch

ENV DJL_CACHE_DIR=/opt/p2a/djl-cache

WORKDIR /opt/p2a/prefetch

COPY docker/DjlNativePrefetch.java /tmp/DjlNativePrefetch.java

RUN set -eu \
    && mkdir -p "$DJL_CACHE_DIR" \
    && jar -xf /workspace/build/libs/*.jar BOOT-INF/lib \
    && javac -cp 'BOOT-INF/lib/*' /tmp/DjlNativePrefetch.java \
    && java -cp '/tmp:BOOT-INF/lib/*' DjlNativePrefetch \
    && find "$DJL_CACHE_DIR" -type f -print -quit | grep -q .

FROM eclipse-temurin:21.0.11_10-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13 AS runtime

ENV DJL_CACHE_DIR=/opt/p2a/djl-cache \
    DJL_OFFLINE=true \
    HOME=/opt/p2a/tmp \
    TMPDIR=/opt/p2a/tmp \
    JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/opt/p2a/tmp

RUN groupadd --gid 10001 p2a \
    && useradd --uid 10001 --gid 10001 --create-home --home-dir /opt/p2a/tmp --shell /usr/sbin/nologin p2a \
    && install -d --mode=0555 --owner=root --group=root /opt/p2a/model \
    && install -d --mode=0700 --owner=10001 --group=10001 /opt/p2a/tmp

WORKDIR /opt/p2a

COPY --from=djl-prefetch --chown=root:root /opt/p2a/djl-cache /opt/p2a/djl-cache
COPY --from=builder --chown=root:root /workspace/build/libs/*.jar /opt/p2a/app.jar
COPY --chown=root:root docker/entrypoint.sh /opt/p2a/entrypoint.sh

RUN chmod -R a+rX /opt/p2a/djl-cache \
    && chmod 0555 /opt/p2a/entrypoint.sh

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["/opt/p2a/entrypoint.sh"]
