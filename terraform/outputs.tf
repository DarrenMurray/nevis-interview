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
# Set these as repository secrets. Strictly none is sensitive - federation means there is
# no key to hide - but keeping them in one place beats splitting across secrets and
# variables, and it keeps them out of logs by default.

output "gha_workload_identity_provider" {
  description = "GitHub secret GCP_WORKLOAD_IDENTITY_PROVIDER"
  value       = google_iam_workload_identity_pool_provider.github.name
}

output "gha_service_account" {
  description = "GitHub secret GCP_SERVICE_ACCOUNT - used by publish-image"
  value       = google_service_account.github_publisher.email
}

output "gha_terraform_service_account" {
  description = "GitHub secret GCP_TERRAFORM_SERVICE_ACCOUNT - used by deploy"
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

output "alerting" {
  description = "Whether traffic and error alerts were created."
  # Reports only whether alerting exists, never the address. The deploy workflow publishes
  # outputs to the job summary, and on a public repository those logs are world-readable.
  # nonsensitive() because the derived string carries no more than on/off - without it
  # Terraform propagates the taint from alert_email and refuses the output entirely.
  value = nonsensitive(var.alert_email == "" ? "disabled (set TF_VAR_alert_email to enable)" : "enabled")
}

output "logs_url" {
  description = "Logs Explorer, filtered to this service."
  value       = "https://console.cloud.google.com/logs/query;query=resource.labels.service_name%3D%22${var.service_name}%22?project=${var.project_id}"
}
