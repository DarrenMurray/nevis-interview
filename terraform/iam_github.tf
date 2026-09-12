# Keyless auth for GitHub Actions: GitHub's OIDC token is exchanged for a short-lived GCP
# token, scoped to the repository below. No service-account key exists to leak.

resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "github"
  display_name              = "GitHub Actions"
  description               = "Federated identities for GitHub Actions workflows"

  depends_on = [google_project_service.required]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  display_name                       = "GitHub OIDC"

  attribute_mapping = {
    "google.subject"             = "assertion.sub"
    "attribute.repository"       = "assertion.repository"
    "attribute.repository_owner" = "assertion.repository_owner"
    "attribute.ref"              = "assertion.ref"
  }

  # Security boundary: without this, any GitHub repository could exchange a token here.
  attribute_condition = "assertion.repository == ${jsonencode(var.github_repository)}"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

# Identity the workflow acts as; can only write images.
resource "google_service_account" "github_publisher" {
  account_id   = "github-publisher"
  display_name = "GitHub Actions image publisher"
  description  = "Pushes container images to Artifact Registry from CI"
}

resource "google_service_account_iam_member" "github_publisher_wif" {
  service_account_id = google_service_account.github_publisher.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repository}"
}

# Repository-scoped, push-only. Deploying is the trigger's job, so a leaked workflow token
# cannot change what is running.
resource "google_artifact_registry_repository_iam_member" "github_publisher_writer" {
  location   = google_artifact_registry_repository.containers.location
  repository = google_artifact_registry_repository.containers.name
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.github_publisher.email}"
}
