# Deploy

This directory is the long-term home for deployment entrypoints and platform
documentation.

Planned ownership:

- `local/`
  - developer-oriented orchestration for macOS
  - canonical home for local run/stop scripts
- `onebox/`
  - shared single-VM application deployment
  - reused by GCP, Hetzner, Oracle, and AWS
- `gcp/`
  - Google Cloud specific provisioning and bootstrap
- `hetzner/`
  - Hetzner-specific provisioning and bootstrap
- `oracle/`
  - OCI-specific provisioning and bootstrap
- `aws/`
  - AWS-specific provisioning and bootstrap

Design rule:
- cloud providers may differ in how the VM is created
- the app deployment on that VM should be shared through `onebox/`

See `/docs/deployment-plan.md` for the detailed plan and migration sequence.
