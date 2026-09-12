locals {
  state_bucket = var.state_bucket != "" ? var.state_bucket : "${var.project_id}-tfstate"

  registry = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.containers.repository_id}"
  image    = "${local.registry}/${var.service_name}:${var.image_tag}"
}
