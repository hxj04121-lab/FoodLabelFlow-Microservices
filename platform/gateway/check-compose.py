"""Validate the merged local identity bindings without starting containers."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
COMMAND = ["docker", "compose", "-p", "spectrace-m3-g1", "-f", "deploy/local/compose.yaml",
           "-f", "platform/gateway/compose.local.yaml", "config", "--format", "json"]


def render(env):
    return subprocess.run(COMMAND, cwd=ROOT, env=env, text=True, encoding="utf-8",
                          capture_output=True, timeout=30)


with tempfile.TemporaryDirectory(prefix="m3-compose-check-") as directory:
    # A config-only fixture; never run it as an IdP or call it an imported realm.
    fixture = Path(directory) / "config-only-realm.json"
    fixture.write_text("{}", encoding="utf-8")
    for port in (8080, 9080):
        env = {**os.environ, "G1_REALM_FILE": str(fixture), "G1_GATEWAY_HOST_PORT": str(port),
               "G1_FRONTEND_ORIGIN": "http://localhost:5173"}
        result = render(env)
        if result.returncode:
            raise RuntimeError("Compose configuration rejected: " + result.stderr)
        model = json.loads(result.stdout)
        services = model["services"]
        gateway = services["gateway"]["environment"]
        issuer = f"http://localhost:{port}/auth/realms/spectrace"
        jwks = "http://keycloak:8080/auth/realms/spectrace/protocol/openid-connect/certs"
        assert gateway["GATEWAY_ISSUER"] == issuer
        assert gateway["GATEWAY_JWK_SET_URI"] == jwks
        assert gateway["GATEWAY_AUDIENCE"] == "spectrace-api"
        for service, prefix in (("specification", "SPECIFICATION"), ("formulation", "FORMULATION")):
            assert services[service]["environment"]["OIDC_ISSUER_URI"] == issuer
            assert services[service]["environment"]["SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI"] == jwks
            assert gateway[f"GATEWAY_{prefix}"] == f"http://{service}:8080"
            assert gateway[f"GATEWAY_{prefix}_WRITE_ROLES"] == "ADMIN_DATA_MAINTENANCE"
        keycloak = services["keycloak"]
        assert keycloak["environment"]["KC_HOSTNAME"] == f"http://localhost:{port}/auth"
        assert keycloak["environment"]["KC_HTTP_RELATIVE_PATH"] == "/auth"
        assert keycloak["environment"]["KC_HTTP_MANAGEMENT_RELATIVE_PATH"] == "/"
        assert "--import-realm" in keycloak["command"]
        assert keycloak["volumes"][0]["read_only"] is True
        # Compose versions may omit the normalized model's default false field.
        assert keycloak["volumes"][0].get("bind", {}).get("create_host_path", False) is False
        assert Path(services["gateway"]["build"]["context"]).resolve() == ROOT / "platform" / "gateway"
        assert model["name"] == "spectrace-m3-g1"
        print(f"PASS Compose identity/JWKS/roles/import/build bindings at Gateway port {port}")
    missing = {**os.environ}
    missing.pop("G1_REALM_FILE", None)
    assert render(missing).returncode != 0, "Missing provider realm must fail closed"
    print("PASS missing provider realm rejected; no containers started")
