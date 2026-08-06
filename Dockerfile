FROM clojure:temurin-26-tools-deps-trixie-slim@sha256:a01b3fc136f87eda8b00d86d1e0af98617be126d2c01500fd784059fc75d6a8d AS builder

WORKDIR /workspace

COPY deps.edn build.clj ./
RUN clojure -P -T:build

COPY src ./src
COPY resources ./resources
RUN clojure -T:build jar

FROM debian:trixie-slim@sha256:3a39a0592364683e6bab97937b72cad5a8fa6dcbbee90edb3bb48c7f8e94f258 AS runtime

ENV ALIDA_VECTOR_HOME=/opt/alida-vector \
    ALIDA_VECTOR_JAR=/opt/alida-vector/alida-vector.jar \
    CHROME_BIN=/usr/bin/chromium \
    CHROMEDRIVER_BIN=/usr/bin/chromedriver \
    JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
      ca-certificates \
      chromium \
      chromium-driver \
      dumb-init \
      fonts-liberation \
      openjdk-21-jre-headless \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 10001 alida \
    && useradd --uid 10001 --gid alida --home-dir "$ALIDA_VECTOR_HOME" \
      --create-home --shell /usr/sbin/nologin alida \
    && mkdir -p /config /var/cache/alida-vector /tmp/alida-vector \
    && chown -R alida:alida "$ALIDA_VECTOR_HOME" /config /var/cache/alida-vector /tmp/alida-vector

COPY --chmod=0755 bin/alida-vector /usr/local/bin/alida-vector
COPY --from=builder --chown=alida:alida /workspace/target/alida-vector.jar /opt/alida-vector/alida-vector.jar

USER alida
WORKDIR /opt/alida-vector

# This is a finite batch image, not a long-running network service. Kubernetes
# observes Job completion instead of polling an in-container health endpoint.
HEALTHCHECK NONE

ENTRYPOINT ["dumb-init", "--", "alida-vector"]
CMD ["crawl", "--config", "/config/alida.yml"]
