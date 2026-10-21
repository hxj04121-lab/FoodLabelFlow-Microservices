# Staging Terraform State object observation — 2026-10-18

Read-only `head-object` evidence provided from AWS CloudShell using the staging deploy identity:

| Field | Observed value |
| --- | --- |
| Region | `ap-southeast-1` |
| S3 object | `spectrace/staging/terraform.tfstate` |
| VersionId | `tIRsj1wl1CWHRWeAa2C1G.1QujMl.0dk` |
| LastModified | `2026-10-11T15:36:03+00:00` |
| Size | `121021` bytes |

This result matches the previously recorded object version. It establishes that this same S3 object version was observed; it does **not** prove that AWS has no drift, identify Terraform resource addresses or owners, confirm the active Terraform workspace, or verify external Helm/Kubernetes ownership. The protected Plan must compare a fresh VersionId, select the existing `staging` workspace, inspect required state addresses, and show the planned resource actions. No State contents were read or changed for this observation.
