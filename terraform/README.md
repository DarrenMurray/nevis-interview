# Infrastructure

Cloud Run service, Artifact Registry, and a push-triggered redeploy, on GCP.

**Nothing here has been applied.** The configuration is `terraform validate`-clean against
provider `hashicorp/google ~> 8.2`, but no resource has been created and no cost incurred.

## The deploy loop

```
GitHub merge to main  ─┐
manual "Run workflow"  ├─►  publish-image.yml  ─►  Artifact Registry
                      ─┘     (builds, pushes            │
                              :latest + :sha-xxxx)       │ publishes to
                                                         ▼
                                                  Pub/Sub topic "gcr"
                                                         │ filtered: action=INSERT
                                                         │           tag=…:latest
                                                         ▼
                                                  Cloud Build trigger
                                                         │ gcloud run deploy
                                                         ▼
                                                    Cloud Run service
```

The push and the deploy are deliberately decoupled. An image pushed from a laptop rolls
out exactly like one pushed by CI, because the registry — not the workflow — is what
triggers the rollout.

Worth knowing: **Cloud Run pins an image digest at deploy time.** Pushing a new `:latest`
does not change a running service on its own. The Cloud Build trigger is what makes
"push and it updates" true; without it, `:latest` moves and the service keeps serving the
old digest.

## State

State lives in a GCS bucket, not on a laptop, so local runs and CI operate on the same
object. GCS provides locking natively through object generations — two concurrent applies
block rather than corrupt state, and there is no separate lock table to provision.

The bucket cannot be a resource in this config: `terraform init` needs the backend to
exist before it can read or write anything, so the config that uses the bucket cannot
also create it. Create it once:

```sh
make tf-bootstrap GCP_PROJECT=<your-project>     # creates gs://<project>-tfstate
```

That script is idempotent, and sets the three things that matter:

- **Versioning** — the entire recovery story for state. A bad apply or a botched
  `state rm` is only reversible if the previous generation still exists.
- **Uniform bucket-level access** and **public access prevention** — state contains
  generated passwords and every resource ID; ACLs and IAM disagreeing about who can read
  it is a bad way to discover it was readable.
- **Lifecycle rules** — keep 20 noncurrent versions, expire them after 90 days, so
  history does not grow without bound.

Then put the bucket name in the `backend` block in `versions.tf` (it ships as
`REPLACE_ME-tfstate`) and run `make tf-init`.

The name is committed rather than supplied per-machine via `-backend-config`. Terraform
backends cannot take variables, and a committed literal is what guarantees a laptop and a
workflow resolve to the same state object — a local `backend.hcl` would let them diverge
silently, which is the exact failure this is meant to prevent.

### Running Terraform from CI

The `deploy` workflow runs against this same state. Its identity is the `terraform-ci`
service account, which holds `roles/storage.objectAdmin` on the state bucket (scoped to
the bucket, not project-wide) plus the enumerated project roles in
`iam_terraform_ci.tf`. `github-publisher` cannot read or write state.

## Prerequisites

- `terraform` >= 1.9 — not installed by the project; grab it from
  [releases.hashicorp.com](https://releases.hashicorp.com/terraform/)
- `gcloud`, authenticated: `gcloud auth login && gcloud auth application-default login`
- **A dedicated GCP project with billing enabled.** Create it first; Terraform does not
  create the project or attach billing.

### Provider cache (recommended)

The google provider is ~145MB. Without a shared cache every project vendors its own copy
into `.terraform/providers`. Configure it once in `~/.terraformrc`:

```hcl
plugin_cache_dir = "$HOME/.terraform.d/plugin-cache"
plugin_cache_may_break_dependency_lock_file = false
```

`.terraform/` then holds symlinks — 40K instead of 145M per project.

## Apply

```sh
cp .env.example .env                           # then set GCP_PROJECT_ID
make tf-bootstrap                              # once: creates the state bucket
# put the bucket name in the backend block in versions.tf

make tf-init
make tf-plan
make tf-apply
```

`make tf-config` prints the project, region and state bucket Terraform will actually use —
worth a glance before an apply.

`make tf-validate` checks the config without touching the backend or any cloud resource,
so it works before the bucket exists and without credentials.

### Variables come from .env

There is no `terraform.tfvars`. `.env` holds Terraform's own `TF_VAR_*` names, so the
same file works everywhere with no mapping layer:

```sh
make tf-plan                    # the Makefile exports them for you
set -a; . ./.env; set +a        # ...or export them into your shell
terraform plan                  #    and bare terraform then works too
```

| `.env` key | Used for |
|---|---|
| `TF_VAR_project_id` | `var.project_id`, and the default project for `make tf-bootstrap` |
| `TF_VAR_region` | `var.region`, and the state bucket's location |

CI sets the same two names from repository secrets, so local and CI resolve variables
identically.

Each is exported only when non-empty. An exported-but-empty `TF_VAR_region` would
override the variable's default with `""` instead of leaving it unset — a confusing way
to deploy into a nonexistent region.

**If bare `terraform plan` prompts for `project_id`, your shell has not got the variables**
— either source `.env` as above or use `make tf-plan`.

**Do not add a `terraform.tfvars`.** It takes precedence over `TF_VAR_*`, so a stale value
there would silently beat `.env` and could target the wrong project. (`*.tfvars` is
gitignored anyway.)

First apply enables ~9 APIs, which is the slow part. If it fails on a service not yet
active, re-run `apply` — enablement is eventually consistent.

## Wire up GitHub

The workflows authenticate with Workload Identity Federation, so there is **no service
account key** to create, store or rotate. After the first local apply, read the values:

```sh
cd terraform
terraform output -raw gha_workload_identity_provider
terraform output -raw gha_service_account
terraform output -raw gha_terraform_service_account
terraform output -raw gha_project_id
terraform output -raw gha_region
```

Create these five as repository **secrets** (Settings → Secrets and variables → Actions →
Secrets → New repository secret):

| Secret | From | Used by |
|---|---|---|
| `GCP_PROJECT_ID` | `gha_project_id` | both — becomes `TF_VAR_project_id` |
| `GCP_REGION` | `gha_region` | both — becomes `TF_VAR_region` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `gha_workload_identity_provider` | both |
| `GCP_SERVICE_ACCOUNT` | `gha_service_account` | `publish-image` |
| `GCP_TERRAFORM_SERVICE_ACCOUNT` | `gha_terraform_service_account` | `deploy` |

Strictly, none of these is sensitive — that is the point of federation, and they would
work equally well as repository variables. They are secrets because that is one place to
look rather than two, and because it keeps them out of logs by default.

The `deploy` workflow **fails fast if any of them is unset**: an unset secret arrives as
an empty string, and an empty `project_id` would otherwise surface as a baffling mid-apply
API error rather than an obvious misconfiguration.

Only `DarrenMurray/nevis-interview` can exchange a token, enforced by the provider's
`attribute_condition`. Without that condition any repository on the internet could
authenticate — it is the security boundary, not a nicety.

### The first apply must be local

`deploy` authenticates as the `terraform-ci` service account, which this config creates.
So it cannot exist until Terraform has run at least once, and that first run has to be a
human with their own credentials:

```sh
make tf-init && make tf-plan && make tf-apply
```

After that, CI can take over. This is inherent to bootstrapping an identity with the tool
that the identity is meant to run, not a limitation of the setup.

### What the deploy workflow does

| Trigger | Operation |
|---|---|
| pull request touching `terraform/**` | `plan`, posted to the run summary |
| merge to `main` touching `terraform/**` | `apply` |
| manual dispatch | `plan` or `apply`, your choice |

`apply` runs the **saved plan file**, not a fresh plan, so what was reviewed is what runs.
Concurrency is pinned to a single `terraform-state` group with cancellation disabled —
state is shared with local runs, and killing a job mid-apply would leave the lock held.

## Identities

Four service accounts, deliberately separate:

| Account | Can | Cannot |
|---|---|---|
| `github-publisher` | write images to this one repository | deploy, touch state, read secrets |
| `terraform-ci` | apply this config; read/write state | change billing, delete the project |
| `search-api-deployer` | deploy Cloud Run revisions, act as the runtime account | push images |
| `search-api-run` | read secrets, connect to Cloud SQL | deploy, push |

The image publisher cannot change what is running, so a leaked publish token cannot
deploy arbitrary code. `terraform-ci` is necessarily broad — the config manages IAM and
service accounts — but its roles are enumerated rather than `roles/owner`, so the list
doubles as documentation of what the config touches.

## Adding the database

The VPC, the reserved private-services range and the service-networking peering already
exist, and the runtime account already holds `cloudsql.client` and
`secretmanager.secretAccessor`. Turning Postgres on is therefore a flag, not a redesign:

```hcl
enable_cloud_sql = true
```

That creates a private-IP `POSTGRES_17` instance, a database, a user, and the password in
Secret Manager. Cloud Run reaches it over direct VPC egress — the instance has no public
IP. Still to do when the app needs it: mount the secret into the service and point Spring
at the private IP (`terraform output database_host`), and
`CREATE EXTENSION vector;` for pgvector.

It is off by default because it bills hourly from creation whether or not anything
connects.

## Adding the UI

Each Cloud Run service gets its own HTTPS URL, so the simplest path is a second service
(or a bucket for a static build) calling this API directly — needing only CORS. To serve
both from one hostname, put a global external Application Load Balancer in front with
serverless NEGs and path routing (`/api/*` here, `/*` to the UI), then tighten this
service's `ingress` to `INGRESS_TRAFFIC_INTERNAL_LOAD_BALANCER`. The load balancer is
**not** created here: a forwarding rule costs roughly $18/month standing, which is not
worth paying before a UI exists.

## Cost

Idle cost is close to zero by design: Cloud Run scales to zero (`min_instances = 0`), and
a VPC, subnet, reserved range and peering are all free. What does cost money: Artifact
Registry storage beyond the free tier, Cloud Build minutes per deploy, and Cloud SQL the
moment `enable_cloud_sql` is true. Set `min_instances = 1` to avoid JVM cold starts and
you pay for that instance continuously.

## Teardown

```sh
terraform destroy
```

`deletion_protection` is off on both Cloud Run and Cloud SQL so this succeeds without
manual steps. Deleting the whole project is the more thorough option.
