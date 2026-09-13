#!/usr/bin/env bash
# Creates the GCS bucket that holds Terraform state.
#
# This cannot be a Terraform resource: the backend must already exist when
# `terraform init` runs, so the config that uses the bucket cannot also create it.
# Running it twice is safe - an existing bucket is left alone and its settings re-applied.
set -euo pipefail

PROJECT="${1:-}"
BUCKET="${2:-}"
LOCATION="${3:-europe-west2}"

if [ -z "$PROJECT" ]; then
    echo "usage: $0 <project-id> [bucket-name] [location]" >&2
    echo "  bucket defaults to <project-id>-tfstate" >&2
    exit 1
fi
BUCKET="${BUCKET:-${PROJECT}-tfstate}"

echo "Project:  $PROJECT"
echo "Bucket:   gs://$BUCKET"
echo "Location: $LOCATION"
echo

if gcloud storage buckets describe "gs://$BUCKET" --project "$PROJECT" >/dev/null 2>&1; then
    echo "Bucket already exists - re-applying settings."
else
    # Uniform bucket-level access, so object ACLs cannot grant access that IAM denies.
    gcloud storage buckets create "gs://$BUCKET" \
        --project "$PROJECT" \
        --location "$LOCATION" \
        --uniform-bucket-level-access \
        --public-access-prevention
    echo "Created."
fi

# Versioning is the entire disaster-recovery story for Terraform state. A bad apply or a
# botched `state rm` is recoverable only if the previous generation still exists.
gcloud storage buckets update "gs://$BUCKET" --project "$PROJECT" --versioning

# Without a lifecycle rule, every state write accumulates a version forever.
LIFECYCLE="$(mktemp)"
trap 'rm -f "$LIFECYCLE"' EXIT
cat > "$LIFECYCLE" <<'JSON'
{
  "rule": [
    {
      "action": {"type": "Delete"},
      "condition": {"numNewerVersions": 20, "isLive": false}
    },
    {
      "action": {"type": "Delete"},
      "condition": {"daysSinceNoncurrentTime": 90, "isLive": false}
    }
  ]
}
JSON
gcloud storage buckets update "gs://$BUCKET" --project "$PROJECT" --lifecycle-file="$LIFECYCLE"

echo
echo "Done. Now set this in terraform/versions.tf:"
echo
echo "    backend \"gcs\" {"
echo "      bucket = \"$BUCKET\""
echo "      prefix = \"search-api\""
echo "    }"
echo
echo "then run: make tf-init"
