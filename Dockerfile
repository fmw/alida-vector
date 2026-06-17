FROM clojure:temurin-26-tools-deps-trixie-slim@sha256:b12a02d0f5b08e5bb53be9634ba4d20cf291ed1b85b802f3c12ec638024d792d AS builder

WORKDIR /workspace

COPY deps.edn build.clj ./
RUN clojure -P -T:build

COPY src ./src
COPY resources ./resources
RUN clojure -T:build jar

FROM debian:trixie-slim@sha256:4e401d95de7083948053197a9c3913343cd06b706bf15eb6a0c3ccd26f436a0e AS runtime

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

RUN groupadd --system alida \
    && useradd --system --gid alida --home-dir "$ALIDA_VECTOR_HOME" --create-home alida \
    && mkdir -p /config /var/cache/alida-vector /tmp/alida-vector \
    && chown -R alida:alida "$ALIDA_VECTOR_HOME" /config /var/cache/alida-vector /tmp/alida-vector

COPY --chmod=0755 bin/alida-vector /usr/local/bin/alida-vector
COPY --from=builder --chown=alida:alida /workspace/target/alida-vector.jar /opt/alida-vector/alida-vector.jar

USER alida
WORKDIR /opt/alida-vector

ENTRYPOINT ["dumb-init", "--", "alida-vector"]
CMD ["crawl", "--config", "/config/alida.yml"]
