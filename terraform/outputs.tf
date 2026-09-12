output "service_url" {
  description = "Public HTTPS URL of the API."
  value       = google_cloud_run_v2_service.api.uri
}

output "image" {
  description = "Image reference the deployer watches. Push this tag to trigger a rollout."
  value       = local.image
}

output "registry" {
  description = "Artifact Registry path, for `docker tag` and `gcloud auth configure-docker`."
  value       = local.registry
}

# --- Values the GitHub Actions workflow needs -------------------------------------
# Set these as repository secrets. Strictly none is sensitive — federation means there is
# no key to hide — but keeping them in one place beats splitting across secrets and
# variables, and it keeps them out of logs by default.

output "gha_workload_identity_provider" {
  description = "GitHub secret GCP_WORKLOAD_IDENTITY_PROVIDER"
  value       = google_iam_workload_identity_pool_provider.github.name
}

output "gha_service_account" {
  description = "GitHub secret GCP_SERVICE_ACCOUNT — used by publish-image"
  value       = google_service_account.github_publisher.email
}

output "gha_terraform_service_account" {
  description = "GitHub secret GCP_TERRAFORM_SERVICE_ACCOUNT — used by deploy"
  value       = google_service_account.terraform_ci.email
}

output "gha_project_id" {
  description = "GitHub secret GCP_PROJECT_ID"
  value       = var.project_id
}

output "gha_region" {
  description = "GitHub secret GCP_REGION"
  value       = var.region
}

output "database_host" {
  description = "Private IP of the Postgres instance, or null while enable_cloud_sql is false."
  value       = var.enable_cloud_sql ? google_sql_database_instance.postgres[0].private_ip_address : null
}
