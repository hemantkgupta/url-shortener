# Deployment Plan

This document defines the target deployment structure for the URL shortener so
that:

- local development on macOS stays fast and reliable
- single-VM deployment is reusable across GCP, Hetzner, Oracle, and AWS
- cloud-specific work stays isolated from app orchestration
- ARM64 risk is contained to infrastructure choices, not the application shape

## Goals

- Keep local development as a first-class workflow.
- Support one-box deployment from a checked-out repo on a remote machine.
- Avoid duplicating the app deployment logic for GCP, Hetzner, Oracle, and AWS.
- Keep the public surface limited to a single gateway entrypoint.
- Allow a Cassandra fallback if ScyllaDB proves impractical on ARM64.

## Non-Goals

- Do not introduce Kubernetes for the first cloud deployment.
- Do not require CI/CD before the first GCP deployment.
- Do not couple app deployment to Terraform.

## Target Layout

The repository will move toward this structure:

```text
deploy/
  README.md
  local/
    README.md
    run.sh
    stop.sh
    env.example
  onebox/
    README.md
    compose.yml
    .env.example
    scripts/
      doctor.sh
      up.sh
      down.sh
      logs.sh
  gcp/
    README.md
    bootstrap.sh
    terraform/
  hetzner/
    README.md
    cloud-init.yaml
    terraform/
  oracle/
    README.md
    cloud-init.yaml
    terraform/
  aws/
    README.md
    cloud-init.yaml
    terraform/
```

## Deployment Model

### 1. Local

Purpose:
- developer workflow on macOS
- fast restarts
- frontend dev server
- native logs under `logs/`

Canonical local entrypoints will move under `deploy/local/`, but compatibility
wrappers will remain at the current paths during the migration.

Current entrypoints:
- `/run-local.sh`
- `/infrastructure/scripts/start-infra.sh`
- `/infrastructure/scripts/init-scylla.sh`
- `/infrastructure/scripts/start-services.sh`
- `/infrastructure/scripts/stop-all.sh`

Planned compatibility rule:
- the current root and `infrastructure/scripts/` commands remain usable
- once `deploy/local/` becomes canonical, those commands become thin wrappers

### 2. Shared One-Box Deployment

Purpose:
- deploy the full app stack on a single remote VM
- use the same app deployment flow on GCP, Hetzner, Oracle, or AWS
- expose only one gateway publicly

Responsibilities:
- build or start application containers
- run the frontend/gateway
- run private backend services
- mount persistent data under a single configurable data root

This layer is cloud-agnostic. GCP, Hetzner, Oracle, and AWS should all call into it.

### 3. GCP Provisioning

Purpose:
- define how the GCP VM is created and prepared
- document Compute Engine specific bootstrap steps

Responsibilities:
- machine type
- image selection
- external IP and network settings
- firewall rules
- optional startup/bootstrap assets
- optional Terraform later

The GCP layer must not contain app-specific orchestration that duplicates
`deploy/onebox/`.

### 4. Hetzner Provisioning

Purpose:
- define how the Hetzner server is created and prepared
- document Hetzner-specific bootstrap steps

Responsibilities:
- server type
- image selection
- public IP and SSH access
- firewall rules
- optional cloud-init
- optional Terraform later

The Hetzner layer must not contain app-specific orchestration that duplicates
`deploy/onebox/`.

### 5. Oracle Provisioning

Purpose:
- define how the Oracle VM is created and prepared
- document OCI-specific constraints and bootstrap steps

Responsibilities:
- instance shape
- boot volume and optional block volume
- public IP and SSH access
- firewall/security list rules
- optional cloud-init
- optional Terraform later

The Oracle layer must not contain app-specific orchestration that duplicates
`deploy/onebox/`.

### 6. AWS Provisioning

Purpose:
- same pattern as Oracle, but for EC2

Responsibilities:
- EC2 instance
- security group
- EBS sizing
- optional Elastic IP
- optional Terraform later

Again, AWS-specific code should stop at host provisioning and should reuse
`deploy/onebox/` for the application.

## Why Local Gets Its Own Folder

Local development is intentionally different from remote deployment:

- local uses host ports like `13000` and `18080-18083`
- local starts the frontend with the Vite dev server
- local favors direct logs and developer-oriented stop/start flows
- remote deployment should run behind a single gateway with private backend
  ports

Because of that, local should not be forced into the same folder as the cloud
deployment stack. It should have its own `deploy/local/` home.

## Migration Plan

### Phase 1. Document and Scaffold

- create the `deploy/` tree
- write READMEs that define ownership and intent
- keep current scripts untouched except for documentation pointers

### Phase 2. Local Canonicalization

- move the local orchestration scripts under `deploy/local/`
- keep `/run-local.sh` as a wrapper to `deploy/local/run.sh`
- keep `infrastructure/scripts/*.sh` as wrappers or low-level helpers until the
  move is complete
- provide `deploy/local/env.example` as the canonical env reference

### Phase 3. Shared One-Box Pack

- create a cloud-neutral `deploy/onebox/compose.yml`
- expose only the gateway on `80/443`
- keep internal services private on the Docker network
- make the persistent data root configurable

### Phase 4. GCP First Deployment

- add GCP documentation and bootstrap files under `deploy/gcp/`
- start with manual Compute Engine VM creation
- use shell scripts for app deployment
- add Terraform only after the manual path is working
- validate the host with `deploy/onebox/scripts/doctor.sh`

### Phase 5. Hetzner Deployment

- add Hetzner documentation and bootstrap files under `deploy/hetzner/`
- start with manual Hetzner server creation
- use shell scripts for app deployment
- add Terraform only after the manual path is working
- validate the host with `deploy/onebox/scripts/doctor.sh`

### Phase 6. Oracle Provisioning

- keep Oracle documentation available as an optional alternative
- treat Oracle A1 as experimental until capacity is actually available
- reuse the same `deploy/onebox/` application deployment flow

### Phase 7. AWS Provisioning

- add the AWS host bootstrap and Terraform structure
- reuse the same `deploy/onebox/` application deployment flow

## Datastore Strategy

Default plan:
- try ScyllaDB first in the one-box deployment

Fallback plan:
- switch the one-box deployment to Cassandra if ScyllaDB is unstable or
  unsupported on the target host

Why Cassandra is a realistic fallback:
- the services already use the DataStax Cassandra driver
- integration tests already run against Cassandra containers
- the application code is already written in a ScyllaDB/Cassandra-compatible
  style

Rule:
- keep the datastore choice as a deployment concern where possible
- do not fork the application into separate Scylla and Cassandra codepaths
  unless forced by a real incompatibility

## GCP First Rollout

Initial GCP target:
- one `e2-standard-4` VM
- `4 vCPU / 16 GB RAM`
- Ubuntu 24.04 preferred, Ubuntu 22.04 acceptable
- `100 GB` persistent disk
- region `asia-south1`, zone `asia-south1-c`
- use a single data root such as `/data/server/url-shortener`

First operational sequence:
1. create the Compute Engine VM manually
2. install Docker and host prerequisites
3. clone the repo on the VM
4. run the shared one-box deployment flow from the checked-out repo
5. validate the one-box compose on x86
6. keep Hetzner and Oracle as optional alternatives, not the primary path

## Public Exposure Rule

For the first cloud deployment, expose only the gateway:

- UI under `/app`
- write API under `/api/write`
- analytics API under `/api/analytics`
- redirects at `/{shortKey}`

Do not expose backend service ports publicly.

## Acceptance Criteria

The structure is successful when:

- local development still works from the repo root
- the repo has a clear `deploy/local/` home
- GCP, Hetzner, Oracle, and AWS do not duplicate app orchestration logic
- one-box deployment can run from a checked-out repo on a remote VM
- the datastore choice is explicit and documented
- the next implementation step is obvious from the docs alone
