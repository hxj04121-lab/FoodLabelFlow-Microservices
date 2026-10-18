"""Validate the merged local identity bindings without starting containers."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
COMMAND = ["docker", "compose", "-p", "spectrace-m3-g1", "-f", "deploy/local/compose.yaml",
           "-f", "platform/gateway/compose.local.yaml", "config", "--format", "json"]
LEGACY_IDENTITY = {"OIDC_ISSUER_URI", "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI"}


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
            assert gateway[f"GATEWAY_{prefix}"] == f"http://{service}:8080"
            assert gateway[f"GATEWAY_{prefix}_WRITE_ROLES"] == "ADMIN_DATA_MAINTENANCE"
        for service in ("specification", "formulation", "compliance", "label-workflow"):
            identity = services[service]["environment"]
            assert not LEGACY_IDENTITY & identity.keys(), "Services must use only the shared starter identity properties"
            assert not {"SPECTRACE_SECURITY_ISSUER_URI", "SPECTRACE_SECURITY_JWK_SET_URI"} & identity.keys(), \
                "Duplicate starter aliases can retain a different direct-IdP issuer"
            assert identity["SPECTRACE_SECURITY_ISSUERURI"] == issuer
            assert identity["SPECTRACE_SECURITY_JWKSETURI"] == jwks
            assert identity["SPECTRACE_SECURITY_AUDIENCE"] == gateway["GATEWAY_AUDIENCE"]
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
    default = {**os.environ}
    default.pop("G1_REALM_FILE", None)
    result = render(default)
    assert result.returncode == 0, result.stderr
    mounted = json.loads(result.stdout)["services"]["keycloak"]["volumes"][0]
    assert Path(mounted["source"]).resolve() == ROOT / "deploy/keycloak/realm/spectrace-realm.json"
    assert mounted["read_only"] and not mounted.get("bind", {}).get("create_host_path", False)
    realm = json.loads(Path(mounted["source"]).read_text(encoding="utf-8"))
    assert any(mapper["protocolMapper"] == "oidc-sub-mapper" for scope in realm["clientScopes"]
               for mapper in scope["protocolMappers"]), "Accepted realm must mint native sub"
    base = subprocess.run(["docker", "compose", "-f", "deploy/local/compose.yaml", "config", "--format", "json"],
                          cwd=ROOT, env=default, text=True, encoding="utf-8", capture_output=True, timeout=30)
    assert base.returncode == 0, base.stderr
    direct = json.loads(base.stdout)["services"]
    assert direct["keycloak"]["environment"]["KC_HTTP_RELATIVE_PATH"] == "/auth"
    assert direct["keycloak"]["environment"]["KC_HTTP_MANAGEMENT_RELATIVE_PATH"] == "/"
    for service in ("specification", "formulation", "compliance", "label-workflow"):
        assert not LEGACY_IDENTITY & direct[service]["environment"].keys()
        assert not {"SPECTRACE_SECURITY_ISSUER_URI", "SPECTRACE_SECURITY_JWK_SET_URI"} & direct[service]["environment"].keys()
        assert direct[service]["environment"]["SPECTRACE_SECURITY_ISSUERURI"] == default.get(
            "OIDC_ISSUER_URI", "http://localhost:8180/auth/realms/spectrace")
        assert direct[service]["environment"]["SPECTRACE_SECURITY_JWKSETURI"] == jwks
        assert direct[service]["environment"]["SPECTRACE_SECURITY_AUDIENCE"] == "spectrace-api"
    print("PASS accepted default realm and direct/Gateway /auth bindings; no containers started")
    workflow = (ROOT / ".github/workflows/swe5001-m3.yml").read_text(encoding="utf-8")
    assert all(key + "=" not in workflow for key in LEGACY_IDENTITY), "Real-service CI must use the same starter identity properties"
    print("PASS real-service CI has no legacy identity environment variables")
