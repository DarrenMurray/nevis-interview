# Nevis search-api
#
# Default target runs the full test suite:  make
# Every target is also callable directly:   make test

# Java 25 is required. It is installed at ~/.jdks/current but is NOT on PATH (`java`
# resolves to 17 here), so Maven is always invoked with an explicit JAVA_HOME. This
# variable is deliberately not named JAVA_HOME: an inherited JAVA_HOME pointing at an
# older JDK would win over `?=` and break the build in a confusing way.
JDK   ?= $(HOME)/.jdks/current
MVN   := JAVA_HOME=$(JDK) ./mvnw -B

# Local, uncommitted values (project id, region). Optional — the leading dash means a
# missing .env is not an error, so a fresh clone still builds and tests.
-include .env

# Terraform lives in terraform/ and keeps state in GCS (see terraform/versions.tf), so
# local and CI runs share one state object.
TF        ?= terraform
TF_DIR    ?= terraform
TF_LOCATION ?= $(if $(strip $(TF_VAR_region)),$(TF_VAR_region),europe-west2)

# Default the bootstrap target's project from .env so it needs no argument.
GCP_PROJECT ?= $(TF_VAR_project_id)

# .env uses Terraform's own TF_VAR_* names, so these are re-exported as-is rather than
# mapped from differently-named variables. That way the same file works for `make`, for a
# plain `set -a; . ./.env; set +a` shell, and for CI — one set of names, no translation
# layer to drift.
#
# Exported only when non-empty: an exported but empty TF_VAR_region would override the
# variable's default with "" instead of leaving it unset, which is a confusing way to
# deploy into a nonexistent region.
ifneq ($(strip $(TF_VAR_project_id)),)
export TF_VAR_project_id
endif
ifneq ($(strip $(TF_VAR_region)),)
export TF_VAR_region
endif

IMAGE ?= nevis/search-api
TAG   ?= dev
PORT  ?= 8080

# Artifact Registry path, derived from .env so it cannot drift from what Terraform built.
REGISTRY     ?= $(TF_VAR_region)-docker.pkg.dev/$(TF_VAR_project_id)/containers
REMOTE_IMAGE ?= $(REGISTRY)/search-api

# --progress=plain streams each step's output. BuildKit's default TTY view collapses
# long-running steps to a single spinner line, which makes a slow dependency download
# indistinguishable from a hang.
DOCKER_BUILD_FLAGS ?= --progress=plain

# Plain `docker`, deliberately. An earlier version auto-detected with
# `$(shell docker info ... || echo sudo docker)`, which silently turned every docker
# target into a sudo target — and make suppresses the recipe echo, so the sudo password
# prompt appeared with no visible command and looked exactly like a hang.
#
# If the daemon is not reachable, check-docker below says so immediately. To use sudo
# anyway: `make docker-push DOCKER="sudo docker"`.
DOCKER ?= docker

.DEFAULT_GOAL := test
.PHONY: check-docker test build package run clean docker-build docker-run docker-smoke docker-login docker-push docker-stop ci help check-jdk \
        tf-bootstrap tf-init tf-validate tf-fmt tf-plan tf-apply tf-config check-tf

## test          Run all tests
test: check-jdk
	$(MVN) test

## build         Compile and package the jar, running tests
build: check-jdk
	$(MVN) clean package

## package       Package the jar without running tests
package: check-jdk
	$(MVN) clean package -DskipTests

## run           Start the API locally
run: check-jdk
	$(MVN) spring-boot:run

## clean         Delete build output
clean: check-jdk
	$(MVN) clean

# Fail immediately with the fix, rather than hanging on a hidden prompt or emitting a
# bare "permission denied".
check-docker:
	@# stdout only. NEVER redirect stderr here: with DOCKER="sudo docker" the password
	@# prompt is written to stderr, and discarding it leaves sudo waiting on an
	@# invisible prompt — silence that looks like nothing happening at all.
	@$(DOCKER) info >/dev/null || { \
		echo "Cannot reach the Docker daemon as '$(DOCKER)'."; \
		echo ""; \
		echo "This machine runs snap Docker started with --group docker, but that group"; \
		echo "does not exist, so the socket is root-owned. Create it once:"; \
		echo ""; \
		echo "  sudo addgroup --system docker"; \
		echo "  sudo adduser $$USER docker"; \
		echo "  sudo snap disable docker && sudo snap enable docker"; \
		echo ""; \
		echo "Then open a new shell. Or run this target as: make $(MAKECMDGOALS) DOCKER=\"sudo docker\""; \
		exit 1; }

## docker-build  Build the container image
docker-build: check-docker
	$(DOCKER) build $(DOCKER_BUILD_FLAGS) -t $(IMAGE):$(TAG) .

## docker-run    Run the image, publishing the API port
docker-run: check-docker
	$(DOCKER) run --rm -p $(PORT):8080 --name search-api $(IMAGE):$(TAG)

## docker-smoke  Start the built image and assert it serves traffic
docker-smoke: check-docker
	DOCKER="$(DOCKER)" ./scripts/docker-smoke.sh $(IMAGE):$(TAG) $(PORT)

## ci            What CI runs: tests, image build, image smoke test
ci: test docker-build docker-smoke

## docker-login  Authenticate Docker against Artifact Registry
docker-login: check-docker
	@test -n "$(TF_VAR_project_id)" || { echo "TF_VAR_project_id not set — is .env present?"; exit 1; }
	@# An access token piped into `docker login` rather than `gcloud auth configure-docker`:
	@# the credential helper reads $$HOME/.docker/config.json, and under `sudo docker` that is
	@# root's config, not yours — so the helper silently has no credentials.
	gcloud auth print-access-token | $(DOCKER) login -u oauth2accesstoken --password-stdin https://$(TF_VAR_region)-docker.pkg.dev

## docker-push   Build and push :latest to Artifact Registry (this fires the deploy)
docker-push: docker-login
	$(DOCKER) build $(DOCKER_BUILD_FLAGS) -t $(REMOTE_IMAGE):latest .
	$(DOCKER) push $(REMOTE_IMAGE):latest
	@echo
	@echo "Pushed $(REMOTE_IMAGE):latest — the Cloud Build trigger should now deploy it."
	@echo "Watch:  gcloud builds list --region $(TF_VAR_region) --limit 3"

## docker-stop   Stop the running container
docker-stop:
	-$(DOCKER) stop search-api

## tf-bootstrap  Create the GCS bucket that holds Terraform state (run once)
tf-bootstrap:
	@test -n "$(GCP_PROJECT)" || { echo "Set GCP_PROJECT, e.g. make tf-bootstrap GCP_PROJECT=my-project"; exit 1; }
	./scripts/bootstrap-tfstate.sh "$(GCP_PROJECT)" "" "$(TF_LOCATION)"

## tf-config     Show the project and region Terraform will use
tf-config:
	@echo "TF_VAR_project_id = $${TF_VAR_project_id:-(unset - terraform will prompt)}"
	@echo "TF_VAR_region     = $${TF_VAR_region:-(unset - defaults to europe-west2)}"
	@sed -n 's/.*bucket *= *"\(.*\)".*/state bucket      = \1/p' $(TF_DIR)/versions.tf

## tf-init       Initialise Terraform against the GCS backend
tf-init: check-tf
	cd $(TF_DIR) && $(TF) init

## tf-validate   Check the config without touching the backend or any cloud resource
tf-validate: check-tf
	cd $(TF_DIR) && $(TF) init -backend=false -input=false >/dev/null && $(TF) validate

## tf-fmt        Format the Terraform files in place
tf-fmt: check-tf
	cd $(TF_DIR) && $(TF) fmt -recursive

## tf-plan       Show what would change
tf-plan: check-tf
	cd $(TF_DIR) && $(TF) plan

## tf-apply      Apply the infrastructure (prompts for confirmation)
tf-apply: check-tf
	cd $(TF_DIR) && $(TF) apply

check-tf:
	@command -v $(TF) >/dev/null 2>&1 || { echo "terraform not found on PATH. Install it (https://releases.hashicorp.com/terraform/) or set TF=/path/to/terraform"; exit 1; }

# Fail with an actionable message rather than a cryptic javac error.
check-jdk:
	@test -x "$(JDK)/bin/javac" || { echo "No JDK found at $(JDK). Java 25 is required; try: make JDK=/path/to/jdk-25 $(MAKECMDGOALS)"; exit 1; }
	@test "$$($(JDK)/bin/javac -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')" -ge 25 || { echo "JDK at $(JDK) is too old; this project targets Java 25. Try: make JDK=/path/to/jdk-25 $(MAKECMDGOALS)"; exit 1; }

## help          List available targets
help:
	@echo "Nevis search-api — available targets:"
	@# -h suppresses filename prefixes: MAKEFILE_LIST includes .env, so grep sees >1 file.
	@grep -hE '^## ' $(MAKEFILE_LIST) | sed -E 's/^## /  /'
	@echo ""
	@echo "Settings:  JDK=$(JDK)  IMAGE=$(IMAGE):$(TAG)  PORT=$(PORT)  DOCKER=$(DOCKER)"
