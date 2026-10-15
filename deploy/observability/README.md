# Grafana Alloy staging telemetry

Create the `grafana-cloud` Secret in `observability` from protected GitHub Environment values (`PROMETHEUS_URL`, `GRAFANA_CLOUD_METRICS_USER`, `LOKI_URL`, `GRAFANA_CLOUD_LOGS_USER`, `GRAFANA_CLOUD_API_KEY`) before applying `kubectl apply -k deploy/observability/alloy`. No endpoint credentials are checked in. The daemonset ships Kubernetes pod logs and scrapes the five service HTTP actuator endpoints, RabbitMQ's Prometheus endpoint and kube-state-metrics; Spring metrics appear when Actuator Prometheus is enabled by each service.

Import `dashboards/staging-overview.json` into the Grafana Cloud Prometheus datasource. The dashboard is versioned as code; its panels show scrape health, Gateway request rate, Ready nodes and RabbitMQ queue depth. Grafana Cloud retains its own data for its configured retention window after the collector is removed; this project creates no persistent in-cluster log service.
