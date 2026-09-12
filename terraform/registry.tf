resource "google_artifact_registry_repository" "containers" {
  location      = var.region
  repository_id = "containers"
  format        = "DOCKER"
  description   = "Container images for the Nevis search API"

  # Keep the registry from growing without bound. Untagged images are build detritus;
  # tagged ones are kept so a rollback target always exists.
  cleanup_policies {
    id     = "delete-untagged-after-7-days"
    action = "DELETE"

    condition {
      tag_state  = "UNTAGGED"
      older_than = "604800s" # 7 days
    }
  }

  cleanup_policies {
    id     = "keep-recent-tagged"
    action = "KEEP"

    most_recent_versions {
      keep_count = 20
    }
  }

  depends_on = [google_project_service.required]
}
