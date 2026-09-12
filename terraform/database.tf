# Postgres for the search API, off by default.
#
# The assignment needs pgvector for semantic document search, which Cloud SQL for
# PostgreSQL supports as an extension. The instance bills hourly from creation, so it is
# gated: the networking it depends on already exists in network.tf, making this a flag
# flip rather than a redesign.

resource "google_sql_database_instance" "postgres" {
  count = var.enable_cloud_sql ? 1 : 0

  name                = "${var.service_name}-db"
  region              = var.region
  database_version    = "POSTGRES_17"
  deletion_protection = false

  settings {
    tier              = var.cloud_sql_tier
    availability_type = "ZONAL"
    disk_size         = 10
    disk_autoresize   = true

    ip_configuration {
      # No public IP: reachable only from the VPC, which Cloud Run joins via direct
      # VPC egress.
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

  name     = "search"
  instance = google_sql_database_instance.postgres[0].name
}

resource "random_password" "db" {
  count   = var.enable_cloud_sql ? 1 : 0
  length  = 32
  special = false
}

resource "google_sql_user" "app" {
  count = var.enable_cloud_sql ? 1 : 0

  name     = "search_api"
  instance = google_sql_database_instance.postgres[0].name
  password = random_password.db[0].result
}

# The password reaches the container through Secret Manager rather than an environment
# variable in the service definition, so it is not readable from the Cloud Run config.
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
