# Deployment

Alida Vector is designed to run as a scheduled batch job. The Docker image contains the application jar, the `alida-vector` wrapper, a Java 21 runtime, Chromium, and Chromedriver for browser-backed source connectors.

## Build the image

Build the jar first, then build the image:

```bash
bb build
docker build -t alida-vector:local .
```

Use your registry tag when building for deployment:

```bash
docker build -t registry.example.com/alida-vector:2026-06-11 .
```

The image runs `crawl --config /config/alida.yml` by default. Pass explicit command arguments to override the default.

## Runtime Environment

Secrets should come from environment variables or Kubernetes Secrets, never from committed YAML.

Common environment variables:

- `ALIDA_DATABASE_URL`
- `ALIDA_DATABASE_USER`
- `ALIDA_DATABASE_PASSWORD`
- `OPENAI_API_KEY`
- `AZURE_OPENAI_*`
- `GOOGLE_APPLICATION_CREDENTIALS`
- `ALIDA_SLACK_WEBHOOK_URL`

For file-source credential details, including S3 IAM roles and GCS service
accounts, see [File Sources](file-sources.md).

Container-specific environment variables:

- `ALIDA_VECTOR_JAR`: path to the jar used by the wrapper. Defaults to `/opt/alida-vector/alida-vector.jar` in the image.
- `JAVA_TOOL_OPTIONS`: JVM options, such as heap limits.
- `CHROME_BIN`: Chromium binary path.
- `CHROMEDRIVER_BIN`: Chromedriver binary path.

## Kubernetes CronJob Example

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: alida-vector
spec:
  schedule: "17 * * * *"
  concurrencyPolicy: Forbid
  jobTemplate:
    spec:
      backoffLimit: 2
      template:
        spec:
          restartPolicy: Never
          containers:
            - name: alida-vector
              image: registry.example.com/alida-vector:2026-06-11
              imagePullPolicy: IfNotPresent
              env:
                - name: JAVA_TOOL_OPTIONS
                  value: "-XX:MaxRAMPercentage=75"
                - name: ALIDA_DATABASE_URL
                  valueFrom:
                    secretKeyRef:
                      name: alida-vector
                      key: database-url
                - name: ALIDA_DATABASE_USER
                  valueFrom:
                    secretKeyRef:
                      name: alida-vector
                      key: database-user
                - name: ALIDA_DATABASE_PASSWORD
                  valueFrom:
                    secretKeyRef:
                      name: alida-vector
                      key: database-password
                - name: OPENAI_API_KEY
                  valueFrom:
                    secretKeyRef:
                      name: alida-vector
                      key: openai-api-key
                - name: ALIDA_SLACK_WEBHOOK_URL
                  valueFrom:
                    secretKeyRef:
                      name: alida-vector
                      key: slack-webhook-url
              volumeMounts:
                - name: config
                  mountPath: /config
                  readOnly: true
                - name: tmp
                  mountPath: /tmp
          volumes:
            - name: config
              configMap:
                name: alida-vector-config
            - name: tmp
              emptyDir: {}
```

Use `concurrencyPolicy: Forbid` so the scheduler does not start overlapping crawls. Alida also takes per-index PostgreSQL advisory locks, which protects against manual or multi-cluster overlap.
