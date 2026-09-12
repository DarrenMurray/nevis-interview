variable "project_id" {
  description = "Target GCP project ID. Create a dedicated project for this stack."
  type        = string
}

variable "region" {
  description = "Region for Cloud Run, Artifact Registry, Cloud Build and the VPC subnet."
  type        = string
  default     = "europe-west2"
}

variable "service_name" {
  description = "Cloud Run service name, also used as the container image name."
  type        = string
  default     = "search-api"
}

variable "github_repository" {
  description = <<-EOT
    The GitHub repository allowed to push images, as "owner/name". Only this repository
    can mint tokens through Workload Identity Federation.
  EOT
  type        = string
  default     = "DarrenMurray/nevis-interview"

  validation {
    condition     = can(regex("^[^/]+/[^/]+$", var.github_repository))
    error_message = "Must be in owner/name form, e.g. DarrenMurray/nevis-interview."
  }
}

variable "image_tag" {
  description = <<-EOT
    Tag the deployer watches and deploys. The publish workflow always pushes this tag,
    so a push to it is what triggers a rollout.
  EOT
  type        = string
  default     = "latest"
}

variable "deploy_on_push" {
  description = <<-EOT
    Redeploy Cloud Run automatically when a matching image is pushed to Artifact Registry.
    Disable to make rollouts manual without tearing down the rest of the stack.
  EOT
  type        = bool
  default     = true
}

variable "enable_cloud_sql" {
  description = <<-EOT
    Create the Cloud SQL Postgres instance.

    On by default: the application runs Flyway at startup and exits if it cannot reach a
    database, so with this disabled the Cloud Run service will crash-loop rather than
    serve a degraded version. Set it to false only when deliberately tearing the database
    down, and expect the API to be down with it.
  EOT
  type        = bool
  default     = true
}

variable "cloud_sql_tier" {
  description = "Cloud SQL machine type. db-f1-micro is the cheapest usable option."
  type        = string
  default     = "db-f1-micro"
}

variable "min_instances" {
  description = "Cloud Run minimum instances. 0 scales to zero; raise to avoid cold starts."
  type        = number
  default     = 0
}

variable "state_bucket" {
  description = <<-EOT
    GCS bucket holding Terraform state. Only used to grant the CI Terraform identity
    access to it - the backend block cannot read variables, so the bucket name is also
    hardcoded in versions.tf. Defaults to "<project_id>-tfstate", matching
    scripts/bootstrap-tfstate.sh.
  EOT
  type        = string
  default     = ""
}

variable "db_name" {
  description = "Database name inside the instance."
  type        = string
  default     = "search"
}

variable "db_user" {
  description = "Application database user."
  type        = string
  default     = "search_api"
}

variable "initial_image" {
  description = <<-EOT
    Image the service is first created with. Cloud Run validates that an image is
    pullable at deploy time, and on the very first apply the registry this config creates
    is still empty - pointing at our own :latest would fail the apply. Google's public
    hello container stands in until the first real push, after which the push-triggered
    deployer replaces it and `ignore_changes` keeps Terraform from reverting it.
  EOT
  type        = string
  default     = "us-docker.pkg.dev/cloudrun/container/hello"
}

variable "alert_email" {
  description = <<-EOT
    Address to email when the service receives traffic or logs an error. Leave empty to skip
    creating the notification channel and alert policies entirely.
  EOT
  type        = string
  default     = ""
}

variable "rate_limit_requests_per_minute" {
  description = "Global request ceiling, passed to the container and quoted in alert text."
  type        = number
  default     = 60
}
