FROM clojure:temurin-26-tools-deps-trixie-slim@sha256:adaac09e23c97ff19010a28a6b84e670f6bd4d8459d3a8b1979ebdf58c2b737e AS builder

WORKDIR /workspace

COPY deps.edn build.clj ./
RUN clojure -P -T:build

COPY src ./src
COPY resources ./resources
RUN clojure -T:build jar

FROM debian:trixie-slim@sha256:020c0d20b9880058cbe785a9db107156c3c75c2ac944a6aa7ab59f2add76a7bd AS runtime

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
