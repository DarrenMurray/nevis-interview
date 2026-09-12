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

IMAGE ?= nevis/search-api
TAG   ?= dev
PORT  ?= 8080

# The Docker socket on this machine is root-owned and there is no `docker` group, so
# fall back to sudo when the daemon is not reachable as the current user.
# Override explicitly with e.g. `make DOCKER=docker docker-build`.
DOCKER ?= $(shell docker info >/dev/null 2>&1 && echo docker || echo sudo docker)

.DEFAULT_GOAL := test
.PHONY: test build package run clean docker-build docker-run docker-smoke docker-stop ci help check-jdk

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

## docker-build  Build the container image
docker-build:
	$(DOCKER) build -t $(IMAGE):$(TAG) .

## docker-run    Run the image, publishing the API port
docker-run:
	$(DOCKER) run --rm -p $(PORT):8080 --name search-api $(IMAGE):$(TAG)

## docker-smoke  Start the built image and assert it serves traffic
docker-smoke:
	DOCKER="$(DOCKER)" ./scripts/docker-smoke.sh $(IMAGE):$(TAG) $(PORT)

## ci            What CI runs: tests, image build, image smoke test
ci: test docker-build docker-smoke

## docker-stop   Stop the running container
docker-stop:
	-$(DOCKER) stop search-api

# Fail with an actionable message rather than a cryptic javac error.
check-jdk:
	@test -x "$(JDK)/bin/javac" || { echo "No JDK found at $(JDK). Java 25 is required; try: make JDK=/path/to/jdk-25 $(MAKECMDGOALS)"; exit 1; }
	@test "$$($(JDK)/bin/javac -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')" -ge 25 || { echo "JDK at $(JDK) is too old; this project targets Java 25. Try: make JDK=/path/to/jdk-25 $(MAKECMDGOALS)"; exit 1; }

## help          List available targets
help:
	@echo "Nevis search-api — available targets:"
	@grep -E '^## ' $(MAKEFILE_LIST) | sed -E 's/^## /  /'
	@echo ""
	@echo "Settings:  JDK=$(JDK)  IMAGE=$(IMAGE):$(TAG)  PORT=$(PORT)  DOCKER=$(DOCKER)"
