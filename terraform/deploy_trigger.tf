# Redeploy on image push:
#
#   docker push -> Artifact Registry -> Pub/Sub "gcr" -> Cloud Build -> gcloud run deploy
#
# Cloud Run pins an image digest at deploy time, so moving :latest changes nothing until
# something calls deploy. Push and deploy stay decoupled, so a laptop push rolls out too.

# Artifact Registry publishes to a topic named exactly "gcr" and does not create it.
resource "google_pubsub_topic" "gcr" {
  name       = "gcr"
  depends_on = [google_project_service.required]
}

# Separate identity from the CI publisher: pushing an image and changing what runs are
# separate privileges.
resource "google_service_account" "deployer" {
  account_id   = "${var.service_name}-deployer"
  display_name = "Redeploys ${var.service_name} when a new image is pushed"
}

resource "google_project_iam_member" "deployer_run" {
  project = var.project_id
  role    = "roles/run.developer"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

# Setting a revision's identity requires impersonating the runtime account. Scoped to
# that one account.
resource "google_service_account_iam_member" "deployer_acts_as_runtime" {
  service_account_id = google_service_account.runtime.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deployer.email}"
}

# Deploying validates that the image can be pulled, so the deployer needs registry read
# as well as Cloud Run write. Without it: "downloadArtifacts denied" mid-deploy.
resource "google_artifact_registry_repository_iam_member" "deployer_reader" {
  location   = google_artifact_registry_repository.containers.location
  repository = google_artifact_registry_repository.containers.name
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.deployer.email}"
}

# Cold starts pull the image. In-project pulls normally use the Cloud Run service agent;
# granting this explicitly removes that dependency.
resource "google_artifact_registry_repository_iam_member" "runtime_reader" {
  location   = google_artifact_registry_repository.containers.location
  repository = google_artifact_registry_repository.containers.name
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.runtime.email}"
}

# A custom build service account has no implicit log destination; without this the build
# fails before running a step.
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

  # Artifact Registry sends action (INSERT/DELETE), digest and tag, where tag is the full
  # image reference. Cloud Build decodes the base64 payload into substitutions.
  substitutions = {
    _ACTION = "$(body.message.data.action)"
    _TAG    = "$(body.message.data.tag)"
  }

  # Without a filter, every push, tag and delete in the project would deploy. The publish
  # workflow also pushes a sha- tag, so match only :latest.
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
