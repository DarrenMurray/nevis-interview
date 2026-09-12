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

  # Remote state in GCS so local runs and CI operate on the same state. GCS provides
  # locking natively (via object generations), so two concurrent applies cannot corrupt
  # state — there is no separate lock table to provision.
  #
  # The bucket name is hardcoded rather than passed via -backend-config on purpose:
  # backends cannot take variables, and a value committed here is the single source of
  # truth that keeps a laptop and a workflow pointed at the same object. A per-machine
  # backend.hcl would let them silently diverge, which is the failure this is meant to
  # prevent.
  #
  # The bucket must exist before `terraform init`: create it with `make tf-bootstrap`.
  backend "gcs" {
    bucket = "interview-prep-505511-tfstate"
    prefix = "search-api"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}
