import json
import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
RENDERER = ROOT / "deploy/keycloak/operator/render-server.py"


class KeycloakOperatorRuntimeTest(unittest.TestCase):
    def render(self, **overrides):
        env = dict(os.environ, MYSQL_HOST="staging-mysql.internal", GATEWAY_ISSUER="https://staging.52zz.top/auth/realms/spectrace")
        env.update(overrides)
        return subprocess.run(["python3", str(RENDERER)], env=env, capture_output=True, text=True)

    def test_server_cr_reuses_public_issuer_and_keycloak_database_secret(self):
        result = self.render()
        self.assertEqual(result.returncode, 0, result.stderr)
        resource = json.loads(result.stdout)
        self.assertEqual((resource["apiVersion"], resource["kind"]), ("k8s.keycloak.org/v2alpha1", "Keycloak"))
        self.assertEqual(resource["metadata"]["namespace"], "keycloak")
        self.assertEqual(resource["metadata"]["labels"], {"app": "keycloak"})
        self.assertEqual(resource["spec"]["db"], {
            "vendor": "mysql",
            "host": "staging-mysql.internal",
            "database": "keycloak",
            "usernameSecret": {"name": "keycloak-db", "key": "username"},
            "passwordSecret": {"name": "keycloak-db", "key": "password"},
        })
        self.assertEqual(resource["spec"]["hostname"]["hostname"], "https://staging.52zz.top/auth")
        self.assertEqual(resource["spec"]["proxy"]["headers"], "xforwarded")
        self.assertEqual(resource["spec"]["additionalOptions"], [{"name": "http-relative-path", "value": "/auth"}])
        self.assertFalse(resource["spec"]["ingress"]["enabled"])
        self.assertNotIn('"password":', result.stdout)

    def test_rejects_invalid_public_issuer_and_non_dns_database_host(self):
        for env in (
            {"GATEWAY_ISSUER": "http://staging.52zz.top/auth/realms/spectrace"},
            {"GATEWAY_ISSUER": "https://staging.52zz.top/realms/other"},
            {"MYSQL_HOST": "staging..internal"},
        ):
            with self.subTest(env=env):
                result = self.render(**env)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
