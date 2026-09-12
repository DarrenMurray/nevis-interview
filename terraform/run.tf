# Runtime identity for the service. Separate from the default compute service account,
# which is broadly privileged; this one starts with nothing and gains only what it needs.
resource "google_service_account" "runtime" {
  account_id   = "${var.service_name}-run"
  display_name = "Cloud Run runtime identity for ${var.service_name}"
}

# Granted ahead of the database so that turning on enable_cloud_sql does not also require
# an IAM change. Harmless while no instance exists.
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

  # Public API. A UI added later can call it directly; lock this down to
  # INGRESS_TRAFFIC_INTERNAL_LOAD_BALANCER once a load balancer fronts it.
  ingress = "INGRESS_TRAFFIC_ALL"

  template {
    service_account = google_service_account.runtime.email

    scaling {
      min_instance_count = var.min_instances
      max_instance_count = 4
    }

    # Direct VPC egress rather than a Serverless VPC Access connector: no connector
    # instances to pay for or scale, and it is what lets this service reach a
    # private-IP Cloud SQL instance and future internal services.
    vpc_access {
      egress = "PRIVATE_RANGES_ONLY"

      network_interfaces {
        network    = google_compute_network.main.id
        subnetwork = google_compute_subnetwork.run.id
      }
    }

    containers {
      # Placeholder on first apply only; see variable "initial_image". The real image
      # arrives via the push-triggered deployer, and ignore_changes below keeps it.
      image = var.initial_image

      ports {
        container_port = 8080
      }

      resources {
        limits = {
          cpu    = "1"
          memory = "1Gi"
        }
      }

      env {
        name  = "JAVA_TOOL_OPTIONS"
        value = "-XX:MaxRAMPercentage=75"
      }

      # TCP rather than HTTP deliberately. The app has no health endpoint yet, and the
      # only path that returns 2xx unconditionally is /search?q=<something> — whether
      # Cloud Run accepts a query string in a probe path is undocumented, so relying on
      # it would be a guess. Switch to an http_get on /actuator/health when actuator is
      # added; that is the right long-term answer.
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
    # After the first apply, the deployed image is owned by the push-triggered deployer.
    # Without this, every terraform apply would roll the service back to whatever digest
    # :latest pointed at when the plan was made.
    ignore_changes = [template[0].containers[0].image]
  }

  depends_on = [google_project_service.required]
}

# Public, unauthenticated. Fine for a demo API with no data; remove this to require IAM
# or an identity token on every call.
resource "google_cloud_run_v2_service_iam_member" "public" {
  name     = google_cloud_run_v2_service.api.name
  location = google_cloud_run_v2_service.api.location
  role     = "roles/run.invoker"
  member   = "allUsers"
}
