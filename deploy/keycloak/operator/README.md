# Importing the realm with the Keycloak operator (G1-M4.3)

`keycloak-realm-import.yaml` is a `KeycloakRealmImport` (`k8s.keycloak.org/v2alpha1`) generated
from `../realm/spectrace-realm.json`. The realm file stays the source of truth:

```bash
python3 deploy/keycloak/scripts/generate-realm-import.py           # regenerate after a realm change
python3 deploy/keycloak/scripts/generate-realm-import.py --check   # verify the committed copy
```

`KeycloakOperatorRealmImportContractTest` (backend job of CI) fails when the committed resource no
longer matches the realm file, so a realm change cannot silently miss the cluster import.

## What the operator does with it

Applying the resource makes the operator create the realm, its clients, client scopes, mappers and
demo users in the Keycloak instance named by `spec.keycloakCRName`. The operator reports the result
on the resource (`Done` or `HasErrors`) and runs a `keycloak-realm-import` Job; read the condition
before continuing.

## Credentials

No password is committed. Every demo user's password is a `${DEMO_PASSWORD_<USER>}` placeholder
resolved from the `spectrace-demo-credentials` Secret in the same namespace (one key per username,
see `spec.placeholders`). The pipeline creates that Secret from the environment's secret store when
the staging window opens; staging and production must rotate or disable these accounts before real
traffic.

## Applying it (staging window)

The Keycloak operator and the `Keycloak` custom resource belong to M5 (G1-M5); `metadata.namespace`
and `spec.keycloakCRName` must match that install before applying.

```bash
kubectl -n spectrace apply -f deploy/keycloak/operator/keycloak-realm-import.yaml
kubectl -n spectrace wait --for=condition=Done keycloakrealmimport/spectrace-realm --timeout=300s
kubectl -n spectrace logs job/spectrace-realm   # the import log when the condition is HasErrors
```

Then verify the running issuer exactly like the local profile:

```bash
python3 deploy/keycloak/scripts/verify-token-claims.py --base-url <externally visible URL>
```

## Not verified here

CI has no cluster and the operator is M5's, so this file is a specification plus a generator plus a
drift test until the first window. Applying it and reading the condition is the G1-M4.3 evidence;
`docs/swe5001/evidence/G1-M4-evidence.md` records what has been measured and what has not.
