# Request metrics and alerting.
#
# The application emits one structured line per request with severity, path, status and search
# term, so a log-based metric can count requests without any extra instrumentation.

# Counts request log lines, with labels so the metric can be split by path or status in
# Metrics Explorer rather than being a single opaque number.
resource "google_logging_metric" "requests" {
  name   = "${var.service_name}-requests"
  filter = <<-EOT
    resource.type="cloud_run_revision"
    resource.labels.service_name="${var.service_name}"
    jsonPayload.message=~"^Request (handled|rejected|failed)$"
  EOT

  metric_descriptor {
    metric_kind = "DELTA"
    value_type  = "INT64"
    unit        = "1"

    labels {
      key         = "http_path"
      value_type  = "STRING"
      description = "Request path"
    }
    labels {
      key         = "http_status"
      value_type  = "STRING"
      description = "Response status code"
    }
  }

  label_extractors = {
    "http_path"   = "EXTRACT(jsonPayload.http_path)"
    "http_status" = "EXTRACT(jsonPayload.http_status)"
  }

  depends_on = [google_project_service.required]
}

# Counts only failures, so an alert on errors does not have to filter a busy metric.
resource "google_logging_metric" "errors" {
  name   = "${var.service_name}-errors"
  filter = <<-EOT
    resource.type="cloud_run_revision"
    resource.labels.service_name="${var.service_name}"
    severity="ERROR"
  EOT

  metric_descriptor {
    metric_kind = "DELTA"
    value_type  = "INT64"
    unit        = "1"
  }

  depends_on = [google_project_service.required]
}

# --- Alerting -----------------------------------------------------------------------
# All of this is skipped when alert_email is unset, so the stack still applies without it.

resource "google_monitoring_notification_channel" "email" {
  count = var.alert_email == "" ? 0 : 1

  display_name = "Search API alerts"
  type         = "email"
  labels = {
    email_address = var.alert_email
  }

  depends_on = [google_project_service.required]
}

# Fires as soon as a single request is recorded in an alignment period.
#
# Cloud Monitoring alerts on incidents rather than events, so this does not send one email per
# request: it opens an incident the first time traffic appears and emails once. While traffic
# continues the incident stays open and stays quiet. auto_close is set to the minimum so a later
# burst opens a fresh incident and emails again.
resource "google_monitoring_alert_policy" "any_request" {
  count = var.alert_email == "" ? 0 : 1

  display_name = "${var.service_name}: traffic received"
  combiner     = "OR"
  severity     = "WARNING"

  documentation {
    content   = <<-EOT
      At least one request reached ${var.service_name}.

      This is an awareness alert on a personal-account demo, not a fault. The API is public and
      unauthenticated and is rate limited to ${var.rate_limit_requests_per_minute} requests per
      minute; this exists so unexpected traffic is noticed rather than discovered on the bill.

      Logs: https://console.cloud.google.com/logs/query;query=resource.labels.service_name%3D%22${var.service_name}%22?project=${var.project_id}
    EOT
    mime_type = "text/markdown"
  }

  conditions {
    display_name = "Any request in a 60s window"

    condition_threshold {
      filter          = "resource.type = \"cloud_run_revision\" AND metric.type = \"logging.googleapis.com/user/${google_logging_metric.requests.name}\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      # The shortest supported window: this is as close to per-request as alerting gets.
      duration = "0s"

      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_SUM"
        # Collapse the per-path series, or each path would raise its own incident.
        cross_series_reducer = "REDUCE_SUM"
      }

      trigger {
        count = 1
      }
    }
  }

  notification_channels = [google_monitoring_notification_channel.email[0].id]

  alert_strategy {
    auto_close = "1800s"
  }
}

# Separate from the traffic alert, which fires routinely and is therefore unsuitable for
# detecting faults.
resource "google_monitoring_alert_policy" "errors" {
  count = var.alert_email == "" ? 0 : 1

  display_name = "${var.service_name}: server errors"
  combiner     = "OR"
  severity     = "ERROR"

  conditions {
    display_name = "Any ERROR log line in a 60s window"

    condition_threshold {
      filter          = "resource.type = \"cloud_run_revision\" AND metric.type = \"logging.googleapis.com/user/${google_logging_metric.errors.name}\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_SUM"
      }
    }
  }

  notification_channels = [google_monitoring_notification_channel.email[0].id]

  alert_strategy {
    auto_close = "1800s"
  }
}
