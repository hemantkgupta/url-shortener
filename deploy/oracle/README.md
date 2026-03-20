# Oracle Deployment

This folder is reserved for Oracle Cloud Infrastructure specific deployment
assets.

Oracle responsibilities:
- OCI account setup notes
- `VM.Standard.A1.Flex` bootstrap guidance
- cloud-init or host bootstrap files
- Terraform later, after the manual path is working

Planned approach:
1. create the OCI VM manually
2. validate ARM64 compatibility on the real host
3. deploy the app through `../onebox/`
4. add Terraform only after the manual path is stable

Why Oracle is separate:
- instance creation, network rules, and storage setup are provider-specific
- the application deployment on the VM should stay shared

Target host assumptions for the first pass:
- ARM64 VM
- Docker installed on the host
- public SSH access configured
- persistent data available under `/data/server/url-shortener`

Bootstrap assets:
- `cloud-init.yaml` installs Docker, Git, and base tooling on Ubuntu
- `../onebox/scripts/doctor.sh` validates host readiness after login

Recommended first VM:
- shape: `VM.Standard.A1.Flex`
- OCPU: `4`
- memory: `24 GB`
- image: Ubuntu
- boot volume: `100 GB`
- public IP: enabled
- SSH key: your local public key
- inbound ports: `22`, `80`, `443`

How to use `cloud-init.yaml`:
1. open the instance create screen in OCI
2. expand the advanced section for initialization / cloud-init user data
3. paste the contents of `/deploy/oracle/cloud-init.yaml`
4. create the instance

First host-side checks after the VM is reachable:

```bash
git clone <repo-url>
cd url-shortener
bash ./deploy/onebox/scripts/doctor.sh
```
