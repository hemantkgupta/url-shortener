COMPOSE_FILE := infrastructure/compose/docker-compose.dev.yml

.DEFAULT_GOAL := help

.PHONY: help \
        infra-up infra-down infra-logs \
        init-db \
        dev \
        services-start services-stop \
        build test integration-test perf-test \
        frontend-install frontend-dev frontend-build \
        docker-build \
        clean \
        signoz kafka-ui redis-ui

# -----------------------------------------------------------------------------
# Help
# -----------------------------------------------------------------------------
help: ## Print all targets with descriptions
	@echo ""
	@echo "URL Shortener - Available Make Targets"
	@echo "======================================="
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z_-]+:.*##/ { printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@echo ""

# -----------------------------------------------------------------------------
# Infrastructure
# -----------------------------------------------------------------------------
infra-up: ## Start all infrastructure containers (ScyllaDB, Redis, etcd, Kafka, ClickHouse, SigNoz)
	docker compose -f $(COMPOSE_FILE) up -d

infra-down: ## Stop all infrastructure containers
	docker compose -f $(COMPOSE_FILE) down

infra-logs: ## Tail logs from all infrastructure containers
	docker compose -f $(COMPOSE_FILE) logs -f

init-db: ## Initialise ScyllaDB and ClickHouse schemas
	bash infrastructure/scripts/init-scylla.sh
	bash infrastructure/scripts/init-clickhouse.sh

# -----------------------------------------------------------------------------
# Full Dev Environment
# -----------------------------------------------------------------------------
dev: infra-up init-db services-start ## Start infra + init schemas + start all Spring Boot services
	@echo "Dev environment is up. Frontend: cd frontend && npm run dev"

# -----------------------------------------------------------------------------
# Spring Boot Services
# -----------------------------------------------------------------------------
services-start: ## Start all Spring Boot services via script
	bash infrastructure/scripts/start-services.sh

services-stop: ## Stop all Spring Boot services via script
	bash infrastructure/scripts/stop-all.sh

# -----------------------------------------------------------------------------
# Build & Test
# -----------------------------------------------------------------------------
build: ## Build all Gradle modules
	./gradlew build

test: ## Run unit tests for all modules
	./gradlew test

integration-test: ## Run integration tests (requires Docker)
	./gradlew integrationTest

perf-test: ## Run Gatling performance tests
	bash infrastructure/scripts/run-perf-tests.sh

# -----------------------------------------------------------------------------
# Frontend
# -----------------------------------------------------------------------------
frontend-install: ## Install frontend npm dependencies
	cd frontend && npm install

frontend-dev: ## Start frontend Vite dev server (port 5173)
	cd frontend && npm run dev

frontend-build: ## Build frontend for production
	cd frontend && npm run build

# -----------------------------------------------------------------------------
# Docker Images
# -----------------------------------------------------------------------------
docker-build: ## Build all service Docker images
	docker build -f infrastructure/docker/Dockerfile.key-generation-service  -t url-shortener/key-generation-service:latest  key-generation-service
	docker build -f infrastructure/docker/Dockerfile.write-service            -t url-shortener/write-service:latest            write-service
	docker build -f infrastructure/docker/Dockerfile.redirect-service         -t url-shortener/redirect-service:latest         redirect-service
	docker build -f infrastructure/docker/Dockerfile.analytics-service        -t url-shortener/analytics-service:latest        analytics-service

# -----------------------------------------------------------------------------
# Cleanup
# -----------------------------------------------------------------------------
clean: ## Clean Gradle build artifacts and tear down Docker volumes
	./gradlew clean
	docker compose -f $(COMPOSE_FILE) down -v

# -----------------------------------------------------------------------------
# UI Shortcuts
# -----------------------------------------------------------------------------
signoz: ## Print SigNoz UI URL
	@echo "SigNoz UI -> http://localhost:3301"

kafka-ui: ## Print Kafka UI URL
	@echo "Kafka UI  -> http://localhost:9080"

redis-ui: ## Print RedisInsight URL
	@echo "RedisInsight -> http://localhost:8001"
