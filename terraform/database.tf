# Postgres for the search API. On by default: the app runs Flyway at startup and will not
# boot without a database.
#
# pgvector, pg_trgm and citext are Cloud SQL allowlisted extensions, created by the first
# migration. Bills hourly from creation.

resource "google_sql_database_instance" "postgres" {
  count = var.enable_cloud_sql ? 1 : 0

  name                = "${var.service_name}-db"
  region              = var.region
  database_version    = "POSTGRES_16"
  deletion_protection = false

  settings {
    # ENTERPRISE explicitly: new instances can default to ENTERPRISE_PLUS, which rejects
    # shared-core tiers and forces db-perf-optimized-N-* (2+ vCPU, far more expensive).
    edition           = "ENTERPRISE"
    tier              = var.cloud_sql_tier
    availability_type = "ZONAL"
    disk_size         = 10
    disk_autoresize   = true

    ip_configuration {
      # No public IP: reachable only from the VPC.
      ipv4_enabled    = false
      private_network = google_compute_network.main.id
    }

    backup_configuration {
      enabled = true
    }
  }

  depends_on = [google_service_networking_connection.private_services]
}

resource "google_sql_database" "app" {
  count = var.enable_cloud_sql ? 1 : 0

  name     = var.db_name
  instance = google_sql_database_instance.postgres[0].name
}

resource "random_password" "db" {
  count   = var.enable_cloud_sql ? 1 : 0
  length  = 32
  special = false
}

resource "google_sql_user" "app" {
  count = var.enable_cloud_sql ? 1 : 0

  name     = var.db_user
  instance = google_sql_database_instance.postgres[0].name
  password = random_password.db[0].result
}

# Via Secret Manager rather than a plain env var, so it is not readable from the Cloud Run
# service definition.
resource "google_secret_manager_secret" "db_password" {
  count = var.enable_cloud_sql ? 1 : 0

  secret_id = "${var.service_name}-db-password"

  replication {
    auto {}
  }

  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_version" "db_password" {
  count = var.enable_cloud_sql ? 1 : 0

  secret      = google_secret_manager_secret.db_password[0].id
  secret_data = random_password.db[0].result
}
