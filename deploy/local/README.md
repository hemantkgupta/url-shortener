# Local Deployment

Purpose:
- keep macOS development fast and predictable
- preserve the current local run loop
- provide a stable home for local-only scripts and env examples

This folder will become the canonical home for local orchestration.

Planned ownership:
- `run.sh`
- `stop.sh`
- `env.example`

Migration rule:
- the current root command `/run-local.sh` will remain as a compatibility
  wrapper
- the current scripts under `/infrastructure/scripts/` may remain as wrappers
  or low-level helpers during the migration

Current behavior that must be preserved:
- core infra started for local development
- schema initialization
- Spring Boot services on local app ports
- frontend Vite dev server
- logs written under `/logs`

Current commands:

```bash
bash ./deploy/local/run.sh
bash ./deploy/local/stop.sh
```

Compatibility wrappers remain:

```bash
bash ./run-local.sh
bash ./infrastructure/scripts/stop-all.sh
```

Local is intentionally separate from `../onebox/` because the remote deployment
model is different from the native macOS dev workflow.
