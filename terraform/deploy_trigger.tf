# Redeploy on image push.
#
# Nothing in GCP watches a registry on its own. Cloud Run resolves an image to a digest
# when it is deployed and keeps serving that digest, so pushing a new :latest changes
# nothing until something calls deploy. This is that something:
#
#   docker push -> Artifact Registry -> Pub/Sub "gcr" -> Cloud Build -> gcloud run deploy
#
# The push and the deploy stay decoupled, so an image pushed from a laptop rolls out the
# same way one pushed by CI does.

# Artifact Registry publishes to a topic named exactly "gcr" and does not create it.
resource "google_pubsub_topic" "gcr" {
  name       = "gcr"
  depends_on = [google_project_service.required]
}

# The deployer is deliberately a different identity from the CI publisher: pushing an
# image and changing what runs are separate privileges.
resource "google_service_account" "deployer" {
  account_id   = "${var.service_name}-deployer"
  display_name = "Redeploys ${var.service_name} when a new image is pushed"
}

resource "google_project_iam_member" "deployer_run" {
  project = var.project_id
  role    = "roles/run.developer"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

# Deploying a revision means setting its identity, which requires impersonating the
# runtime service account. Scoped to that one account rather than project-wide.
resource "google_service_account_iam_member" "deployer_acts_as_runtime" {
  service_account_id = google_service_account.runtime.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deployer.email}"
}

# Deploying a revision means validating that the image can actually be pulled, so the
# deployer needs read access to the registry — not just permission to change Cloud Run.
# Without it the deploy fails with
# "artifactregistry.repositories.downloadArtifacts denied", after the build has already
# started, which reads like a Cloud Run problem rather than an IAM one.
resource "google_artifact_registry_repository_iam_member" "deployer_reader" {
  location   = google_artifact_registry_repository.containers.location
  repository = google_artifact_registry_repository.containers.name
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.deployer.email}"
}

# The runtime identity pulls the image on every cold start. In-project pulls normally go
# through the Cloud Run service agent, but granting this explicitly removes a dependency
# on that default and costs nothing.
resource "google_artifact_registry_repository_iam_member" "runtime_reader" {
  location   = google_artifact_registry_repository.containers.location
  repository = google_artifact_registry_repository.containers.name
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.runtime.email}"
}

# A custom build service account has no implicit log destination, so without this the
# build fails before running a step.
resource "google_project_iam_member" "deployer_logs" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_cloudbuild_trigger" "deploy_on_push" {
  count = var.deploy_on_push ? 1 : 0

  name            = "${var.service_name}-deploy-on-push"
  location        = var.region
  description     = "Deploys ${var.service_name} when :${var.image_tag} is pushed"
  service_account = google_service_account.deployer.id

  pubsub_config {
    topic = google_pubsub_topic.gcr.id
  }

  # Pub/Sub delivers the payload base64-encoded; Cloud Build decodes it and exposes the
  # fields as substitutions. Artifact Registry sends action (INSERT/DELETE), digest and
  # tag, where tag is the full image reference.
  substitutions = {
    _ACTION = "$(body.message.data.action)"
    _TAG    = "$(body.message.data.tag)"
  }

  # Without this filter every push, tag and delete across the whole project would trigger
  # a deploy. The publish workflow also pushes a sha- tag, so matching only :latest keeps
  # one push to one rollout.
  filter = "_ACTION == \"INSERT\" && _TAG.matches(\"^${local.registry}/${var.service_name}:${var.image_tag}$\")"

  build {
    timeout = "600s"

    options {
      logging = "CLOUD_LOGGING_ONLY"
    }

    step {
      id   = "deploy"
      name = "gcr.io/google.com/cloudsdktool/cloud-sdk:slim"
      args = [
        "gcloud", "run", "deploy", var.service_name,
        "--image", "$_TAG",
        "--region", var.region,
        "--service-account", google_service_account.runtime.email,
        "--quiet",
      ]
    }
  }

  depends_on = [google_project_service.required]
}
