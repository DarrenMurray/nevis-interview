# Services the stack needs. Kept in one place so a fresh project bootstraps in a single
# apply; disable_on_destroy is false so a `terraform destroy` does not disable APIs that
# other things in the project might rely on.
resource "google_project_service" "required" {
  for_each = toset([
    "artifactregistry.googleapis.com",
    "run.googleapis.com",
    "cloudbuild.googleapis.com",
    "pubsub.googleapis.com",
    "iamcredentials.googleapis.com",
    "secretmanager.googleapis.com",
    "compute.googleapis.com",
    "servicenetworking.googleapis.com",
    "sqladmin.googleapis.com",
    "monitoring.googleapis.com",
  ])

  service            = each.value
  disable_on_destroy = false
}
