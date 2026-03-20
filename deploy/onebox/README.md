# One-Box Deployment

This folder will hold the shared application deployment for a single remote VM.

It is intended to be reused by:
- Google Cloud
- Hetzner Cloud
- Oracle Cloud
- AWS
- any future single-host environment

Responsibilities:
- start the public gateway
- run all backend services privately
- manage persistent data mounts
- provide operator commands such as `up`, `down`, `logs`, and `doctor`

Current executable asset:

```bash
bash ./deploy/onebox/scripts/doctor.sh
bash ./deploy/onebox/scripts/up.sh
bash ./deploy/onebox/scripts/logs.sh gateway
bash ./deploy/onebox/scripts/down.sh
```

Non-responsibilities:
- creating the VM
- configuring a provider firewall
- attaching a public IP
- defining Terraform resources

Datastore policy:
- default to ScyllaDB first
- allow Cassandra as a documented fallback for ARM64 environments if ScyllaDB
  proves impractical

Data root:
- the deployment will use a configurable persistent root such as
  `/data/server/url-shortener`

Current pack status:
- `compose.yml` is implemented for the Scylla-first one-box path
- only the gateway is published on the host
- `/app` is reserved for the UI so the domain root stays available for short
  links
- TLS termination is still expected to happen outside this compose stack for the
  first pass

Public surface:
- gateway only
- UI at `/app`
- write API at `/api/write`
- analytics API at `/api/analytics`
- redirects at `/{shortKey}`
