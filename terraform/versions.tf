terraform {
  required_version = ">= 1.9"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.2"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Shared state so local and CI runs agree. GCS locks natively via object generations.
  # Hardcoded rather than -backend-config: backends cannot take variables, and a committed
  # value stops a laptop and a workflow diverging.
  # The bucket must exist before `terraform init` — see `make tf-bootstrap`.
  backend "gcs" {
    bucket = "interview-prep-505511-tfstate"
    prefix = "search-api"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}
