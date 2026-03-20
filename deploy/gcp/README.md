# GCP Deployment

This folder is reserved for Google Cloud specific provisioning and bootstrap
assets.

GCP responsibilities:
- VM selection guidance
- host bootstrap for Ubuntu on Compute Engine
- firewall guidance
- optional Terraform later

Why GCP is the current active path:
- signup already succeeded
- the goal is to learn the system design, not cloud-specific edge cases
- Compute Engine gives a plain Linux VM with stop/resume behavior
- the shared `../onebox/` deployment can run without provider-specific app
  logic

Recommended first VM:
- machine: `e2-standard-4`
- region/zone: `asia-south1-c`
- image: Ubuntu 24.04 LTS
- boot disk: `100 GB`
- external IPv4: ephemeral
- network tier: Standard
- firewall: allow `22`, `80`, `443`

Current live VM reported:
- name: `instance-20260320-052527`
- zone: `asia-south1-c`
- external IP: `35.207.199.142`

Bootstrap assets:
- `bootstrap.sh` installs Docker, Git, and base tooling on Ubuntu
- `../onebox/scripts/doctor.sh` validates host readiness after login

How to connect:

Browser SSH from the Compute Engine VM page is fine for the first login.

If you have the Google Cloud CLI authenticated locally:

```bash
gcloud compute ssh instance-20260320-052527 --zone asia-south1-c
```

If you already configured a local SSH key on the VM:

```bash
ssh <your-gcp-username>@35.207.199.142
```

First host-side sequence:

```bash
git clone <repo-url>
cd url-shortener
sudo bash ./deploy/gcp/bootstrap.sh
bash ./deploy/onebox/scripts/doctor.sh
```

After bootstrap:
1. copy `deploy/onebox/.env.example` to `deploy/onebox/.env`
2. for the first raw-IP deployment, set:
   - `URL_SHORTENER_PUBLIC_DOMAIN=35.207.199.142`
   - `URL_SHORTENER_PUBLIC_SCHEME=http`
   - `URL_SHORTENER_AUTH_GOOGLE_CLIENT_ID=<your-client-id>`
3. run `bash ./deploy/onebox/scripts/up.sh`
