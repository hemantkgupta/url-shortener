# Hetzner Deployment

This folder is reserved for Hetzner-specific provisioning assets.

Hetzner responsibilities:
- server selection guidance
- cloud-init / user-data bootstrap
- firewall and networking notes
- optional Terraform later

Why Hetzner is the current recommended host:
- the deployment goal is to learn the system design, not a specific cloud
- Hetzner avoids the Oracle A1 capacity bottleneck
- x86 reduces ARM-specific image risk
- the shared `../onebox/` deployment can run with minimal provider-specific work

Recommended first server:
- type: `CX43` shared x86
- vCPU / RAM / disk: `8 vCPU / 16 GB / 160 GB`
- image: Ubuntu 24.04 if available, otherwise Ubuntu 22.04
- public IPv4: enabled
- SSH key: your local public key
- firewall: allow inbound `22`, `80`, `443`

Why `CX43`:
- enough RAM for a first-pass one-box deployment
- lower friction than ARM-specific experimentation
- low monthly cap and hourly billing until deletion

Billing note:
- Hetzner bills hourly with a monthly cap
- powered-off servers still bill
- billing stops only when you delete the server

Bootstrap assets:
- `cloud-init.yaml` installs Docker, Git, and base tooling on Ubuntu
- `../onebox/scripts/doctor.sh` validates host readiness after login

How to create the server:
1. open the Hetzner Cloud console
2. create a server
3. choose `CX43`
4. choose Ubuntu 24.04 or 22.04
5. add your SSH key
6. paste `/deploy/hetzner/cloud-init.yaml` into the cloud-init / user-data box
7. create the server

First host-side checks after the VM is reachable:

```bash
ssh root@<server-ip>
git clone <repo-url>
cd url-shortener
bash ./deploy/onebox/scripts/doctor.sh
```
