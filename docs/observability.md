# Monitoring

The local Docker Compose stack includes Prometheus, Loki, Grafana, and Alloy. Spring
Boot exposes Prometheus metrics on the app's private management port (`8081`); Compose
does not publish that port to the host. Alloy sends Docker container logs to Loki.

```sh
./mvnw spring-boot:build-image
docker compose up -d
```

- Grafana: <http://localhost:3000> (default login: `admin` / `admin`)
- Prometheus: <http://localhost:9090>
- Dashboard: **E-commerce → E-commerce API / Spring Boot**
- Request logs: the dashboard's bottom panel, or Grafana **Explore → Loki**
  with `{service_name=~".*backend-app.*"} |= "http_exchange" | json`

Grafana's Prometheus data source and dashboard are provisioned from `monitoring/grafana/`.
Metrics include HTTP request/error rates, JVM heap, and live threads. Request logs
emit one JSON `event=request` and one `event=response` per exchange, with the authenticated
account when available, and include JSON bodies up to 8 KiB in the Compose learning environment;
password/token/secret/contact/address fields are redacted and non-JSON or oversized
bodies are omitted. Outside Compose, body logging is off by default; set
`HTTP_LOG_BODIES=true` only when needed. Override Grafana's default credentials before
exposing the Grafana port beyond a local development machine.
