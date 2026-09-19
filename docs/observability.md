# Monitoring

The local Docker Compose stack includes Prometheus and Grafana. Spring Boot exposes
Prometheus metrics on the app's private management port (`8081`); Compose does not
publish that port to the host.

```sh
./mvnw spring-boot:build-image
docker compose up -d
```

- Grafana: <http://localhost:3000> (default login: `admin` / `admin`)
- Prometheus: <http://localhost:9090>
- Dashboard: **E-commerce → E-commerce API / Spring Boot**

Grafana's Prometheus data source and dashboard are provisioned from `monitoring/grafana/`.
Metrics include HTTP request/error rates, JVM heap, and live threads. The dashboard
shows data after the app has received requests. Override Grafana's default credentials
before exposing the Grafana port beyond a local development machine.
