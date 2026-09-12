# Runtime identity. The default compute service account is broadly privileged; this one
# starts with nothing.
resource "google_service_account" "runtime" {
  account_id   = "${var.service_name}-run"
  display_name = "Cloud Run runtime identity for ${var.service_name}"
}

# Granted ahead of the database so enabling it needs no IAM change.
resource "google_project_iam_member" "runtime_sql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_project_iam_member" "runtime_secret_accessor" {
  project = var.project_id
  role    = "roles/secretmanager.secretAccessor"
  member  = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_cloud_run_v2_service" "api" {
  name                = var.service_name
  location            = var.region
  deletion_protection = false

  # Tighten to INGRESS_TRAFFIC_INTERNAL_LOAD_BALANCER once a load balancer fronts it.
  ingress = "INGRESS_TRAFFIC_ALL"

  template {
    service_account = google_service_account.runtime.email

    scaling {
      min_instance_count = var.min_instances
      max_instance_count = 4
    }

    # Direct VPC egress rather than a Serverless VPC Access connector: no connector
    # instances to pay for, and it reaches private-IP Cloud SQL.
    vpc_access {
      egress = "PRIVATE_RANGES_ONLY"

      network_interfaces {
        network    = google_compute_network.main.id
        subnetwork = google_compute_subnetwork.run.id
      }
    }

    containers {
      # Placeholder on first apply only; see variable "initial_image".
      image = var.initial_image

      ports {
        container_port = 8080
      }

      resources {
        # 2 GiB: the JVM shares the instance with ONNX Runtime and the embedding weights.
        # At 1 GiB the container starts fine and is OOM-killed on the first embed.
        limits = {
          cpu    = "1"
          memory = "2Gi"
        }
      }

      env {
        name = "JAVA_TOOL_OPTIONS"
        # Container memory is not the host's; size the heap from the cgroup limit.
        value = "-XX:MaxRAMPercentage=75"
      }

      # --- Database -----------------------------------------------------------------
      # Private IP over direct VPC egress. Dynamic so the config still plans with
      # enable_cloud_sql = false, though the app will not boot in that state.
      dynamic "env" {
        for_each = var.enable_cloud_sql ? [1] : []
        content {
          name  = "DB_URL"
          value = "jdbc:postgresql://${google_sql_database_instance.postgres[0].private_ip_address}:5432/${var.db_name}"
        }
      }

      dynamic "env" {
        for_each = var.enable_cloud_sql ? [1] : []
        content {
          name  = "DB_USER"
          value = var.db_user
        }
      }

      dynamic "env" {
        for_each = var.enable_cloud_sql ? [1] : []
        content {
          name = "DB_PASSWORD"
          # From Secret Manager, so it is not readable via `gcloud run services describe`.
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.db_password[0].secret_id
              version = "latest"
            }
          }
        }
      }

      # TCP because there is no health endpoint yet, and whether Cloud Run accepts a
      # query string in a probe path is undocumented. Switch to http_get on
      # /actuator/health when actuator is added.
      startup_probe {
        tcp_socket {
          port = 8080
        }
        initial_delay_seconds = 5
        period_seconds        = 3
        failure_threshold     = 20
      }
    }
  }

  lifecycle {
    # The push-triggered deployer owns the running image after the first apply; without
    # this, every apply would roll the service back to the digest seen at plan time.
    ignore_changes = [template[0].containers[0].image]
  }

  depends_on = [
    google_project_service.required,
    google_sql_database_instance.postgres,
    google_secret_manager_secret_version.db_password,
  ]
}

# Public and unauthenticated. Remove to require IAM or an identity token.
resource "google_cloud_run_v2_service_iam_member" "public" {
  name     = google_cloud_run_v2_service.api.name
  location = google_cloud_run_v2_service.api.location
  role     = "roles/run.invoker"
  member   = "allUsers"
}
