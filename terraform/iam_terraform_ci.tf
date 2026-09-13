# Identity for running Terraform from CI. Separate from github-publisher and far more
# privileged, so a workflow that only builds images cannot alter infrastructure.
#
# Created by Terraform itself, so the first apply must be run locally.

resource "google_service_account" "terraform_ci" {
  account_id   = "terraform-ci"
  display_name = "Terraform runner (GitHub Actions)"
  description  = "Applies terraform/ from CI"
}

resource "google_service_account_iam_member" "terraform_ci_wif" {
  service_account_id = google_service_account.terraform_ci.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repository}"
}

# State read/write and locking. Bucket-scoped, not project-wide.
resource "google_storage_bucket_iam_member" "terraform_ci_state" {
  bucket = local.state_bucket
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.terraform_ci.email}"
}

# Enumerated rather than roles/owner: broad, but cannot change billing or delete the
# project, and the list documents what the config touches.
resource "google_project_iam_member" "terraform_ci" {
  for_each = toset([
    "roles/serviceusage.serviceUsageAdmin",  # enable APIs
    "roles/resourcemanager.projectIamAdmin", # project-level IAM bindings
    "roles/iam.serviceAccountAdmin",         # create the runtime/deployer accounts
    "roles/iam.serviceAccountUser",          # let Cloud Run revisions use them
    "roles/iam.workloadIdentityPoolAdmin",   # manage the WIF pool and provider
    "roles/run.admin",                       # Cloud Run service
    "roles/artifactregistry.admin",          # registry and its IAM
    "roles/compute.networkAdmin",            # VPC, subnet, reserved range
    "roles/servicenetworking.networksAdmin", # private services peering
    "roles/pubsub.admin",                    # the gcr topic
    "roles/cloudbuild.builds.editor",        # the deploy trigger
    "roles/secretmanager.admin",             # database password secret
    "roles/cloudsql.admin",                  # only used when enable_cloud_sql = true
    "roles/logging.configWriter",            # log-based metrics
    "roles/monitoring.editor",               # alert policies and notification channels
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.terraform_ci.email}"
}
