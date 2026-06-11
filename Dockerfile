FROM debian:trixie-slim

ENV ALIDA_VECTOR_HOME=/opt/alida-vector \
    ALIDA_VECTOR_JAR=/opt/alida-vector/alida-vector.jar \
    CHROME_BIN=/usr/bin/chromium \
    CHROMEDRIVER_BIN=/usr/bin/chromedriver

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
COPY --chown=alida:alida target/alida-vector.jar /opt/alida-vector/alida-vector.jar

USER alida
WORKDIR /opt/alida-vector

ENTRYPOINT ["dumb-init", "--", "alida-vector"]
CMD ["crawl", "--config", "/config/alida.yml"]
