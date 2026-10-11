# Keycloak realm design — G0-M4

Status: design baseline for G0-M4. This is a design only; realm export and
deployment are G1-M4 work.

## Authority and boundaries

The design follows Architecture Source of Truth v3 AWS §§5, 11.2 and BR-11.
Keycloak owns authentication, organisation attributes, roles and clients. It
does not own Label Workflow business state. Services validate JWTs and enforce
permissions and organisation visibility themselves; gateway role checks are
only a first line.

The architecture specifies the Web client ID spectrace-web, Authorization
Code Flow with PKCE, a partner client, and org_id, org_type and roles token
claims. It does not specify a realm slug or the partner client's OAuth
client type/grant. Those deployment values are intentionally left to G1/M3
review instead of inventing a partner authentication model.

## Clients

### Web reference client

- Client ID: spectrace-web.
- Type: public browser client. A browser cannot keep a client secret; do not
  configure a secret for this client.
- Flow: Authorization Code Flow with PKCE (S256); implicit and direct access
  grant flows are disabled.
- Redirect URIs and web origins: exact allowlist entries only. Production
  entries must match the GitHub Pages origin selected by G0-M3; local entries
  are restricted to the local profile. Wildcard production redirects are not
  allowed. The architecture does not include the final Pages hostname, so it
  must be supplied by the gateway/UI owner before G1 import.
- Access token: audience and issuer are checked by every service. The token
  carries the organisation and role claims below. Refresh/session policy is
  realm configuration and must not be used to change organisation membership.

### Partner client

The architecture names a partner client as an OIDC client but does not specify
whether it is public or confidential, its grant, or a partner provisioning
protocol. G0 records the client boundary only. G1 must use the contract
reviewed partner flow; it must not introduce a new authentication model by
guessing those unspecified settings.

## Organisation and claims

BR-11 requires a user to belong to exactly one organisation whose type is
SUPPLIER or MANUFACTURER. These are organisation types, not realm roles.

| Token claim | Source and rule |
|---|---|
| org_id | A single-valued org_id user attribute assigned by controlled user provisioning. It identifies the user's one organisation. Clients cannot submit or override it in label API requests. |
| org_type | A single-valued org_type user attribute set from that organisation's type; allowed values are exactly SUPPLIER and MANUFACTURER. It must be provisioned with org_id, not inferred from a role. |
| roles | Array of assigned realm role codes. Services map these role codes to the permission model and never treat an organisation type as a role. |

The current baseline has no organisation tables or identity attributes, so the
authoritative organisation directory/provisioning source must be supplied by
the later organisation-model work. Until then, user provisioning is the
controlled boundary: create/activate an account only after validating one
organisation ID and one allowed type. Do not enable self-registration.

Token issuance and service authorization fail closed:

- No organisation: do not activate/provision the account; no usable access
  token may be issued. A missing org_id or org_type is an authentication
  failure at the service boundary.
- Multiple organisations: BR-11 does not support multi-organisation
  users. Provisioning rejects multiple values/memberships; do not select the
  first value or emit an array. Resolve the membership before activation.
- Invalid org_type: reject provisioning and do not issue a usable token.
  Services accept only the two BR-11 values and return authorization denial
  when a token is malformed or outside the supported organisation scope.

Keycloak's G1 realm import and token-claim tests must verify these rules. If
the selected Keycloak user-profile/provisioning configuration cannot enforce
single-valued membership and the enum, G1 must add the smallest provisioning
validator or required-action guard; a mapper alone is not evidence of
cardinality enforcement.

## Roles and permissions

Use the existing seed role and permission vocabulary from
backend/src/main/resources/db/migration/V3__baseline_seed.sql as the source
for realm-role names and service authorization mapping. Do not create
SUPPLIER or MANUFACTURER roles.

| Existing role code | Existing permission codes |
|---|---|
| LABEL_OFFICER | LABEL.CREATE, LABEL.VALIDATE, LABEL.SUBMIT_REVIEW |
| APPROVER | LABEL.APPROVE, LABEL.REQUEST_CHANGES, LABEL.REJECT |
| PUBLISHER | LABEL.PUBLISH |
| AUDITOR | AUDIT.READ |
| ADMIN_DATA_MAINTENANCE | FORMULA.RELEASE, RULE_SET.MAINTAIN, DATA.MAINTAIN |
| CHANGE_MANAGER | CHANGE_REQUEST.CREATE, IMPACT.RUN |

The token roles claim carries the assigned role codes. The service starter
maps those role codes to the existing permission codes and checks the
operation-specific permission on every request. It also compares org_id
with the organisation owning the resource: suppliers may write only their own
materials/specifications; manufacturers may see only their own formulas,
labels, validations and impact findings. Released specifications remain
readable by manufacturers as BR-11 states. Gateway checks do not replace
service checks.

## Open items for G1

- The architecture does not select a realm slug; set it with the G1 import and
  use it consistently in issuer/JWKS configuration.
- The GitHub Pages production redirect origin comes from G0-M3.
- The partner client's type and flow remain subject to its reviewed public
  contract; no flow is assumed in this design.
- G1 must prove missing, multiple and invalid organisation claims are rejected
  with token-claim tests before enabling the realm.
