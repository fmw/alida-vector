FROM clojure:temurin-26-tools-deps-trixie-slim@sha256:48d6c9f03413f4564cbc5ecdf7994b319524e6924df84bd657763356ec85a68e AS builder

WORKDIR /workspace

COPY deps.edn build.clj ./
RUN clojure -P -T:build

COPY src ./src
COPY resources ./resources
RUN clojure -T:build jar

FROM debian:trixie-slim@sha256:d7e12182ce18b85b93007c1dedf31f2d29e01ccf3182cc4017c709b6259bc132 AS runtime

ENV ALIDA_VECTOR_HOME=/opt/alida-vector \
    ALIDA_VECTOR_JAR=/opt/alida-vector/alida-vector.jar \
    ALIDA_CHROME_NO_SANDBOX=true \
    CHROME_BIN=/usr/bin/chromium \
    CHROMEDRIVER_BIN=/usr/bin/chromedriver \
    JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0

RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends \
      ca-certificates \
      chromium \
      chromium-driver \
      dumb-init \
      fonts-liberation \
      openjdk-21-jre-headless \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 10001 alida \
    && useradd --uid 10001 --gid alida --home-dir /tmp/alida-vector \
      --no-create-home --shell /usr/sbin/nologin alida \
    && mkdir -p "$ALIDA_VECTOR_HOME" /config /var/cache/alida-vector /tmp/alida-vector \
    && chown -R alida:alida "$ALIDA_VECTOR_HOME" /config /var/cache/alida-vector /tmp/alida-vector

# Set these only after the directory exists so image-build commands keep their
# normal root home and temporary directory.
ENV HOME=/tmp/alida-vector \
    TMPDIR=/tmp/alida-vector

COPY --chmod=0755 bin/alida-vector /usr/local/bin/alida-vector
COPY --from=builder --chown=alida:alida /workspace/target/alida-vector.jar /opt/alida-vector/alida-vector.jar

USER alida
WORKDIR /opt/alida-vector

# This is a finite batch image, not a long-running network service. Kubernetes
# observes Job completion instead of polling an in-container health endpoint.
HEALTHCHECK NONE

ENTRYPOINT ["dumb-init", "--", "alida-vector"]
CMD ["crawl", "--config", "/config/alida.yml"]
