# AWS Deployment

This folder is reserved for AWS-specific provisioning assets.

AWS responsibilities:
- EC2 bootstrap guidance
- security group rules
- EBS sizing notes
- optional cloud-init
- Terraform later

The application itself should still be deployed through `../onebox/`.

This folder exists so Hetzner, Oracle, and AWS follow the same repository
shape even though Hetzner is the current primary next target.
