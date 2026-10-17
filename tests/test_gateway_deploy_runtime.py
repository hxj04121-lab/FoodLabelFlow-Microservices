import runpy
import unittest
from pathlib import Path

build_values = runpy.run_path(str(Path(__file__).parents[1] / "platform/gateway/deploy-runtime-values.py"))["build_values"]


def config():
    return {
        "GATEWAY_ISSUER": "https://id.example.test/auth/realms/spectrace",
    }


class GatewayDeployRuntimeTests(unittest.TestCase):
    def test_requires_every_runtime_uri_and_produces_only_env_values(self):
        values = build_values(config())
        values = {row["name"]: row["value"] for row in values["env"]}
        self.assertEqual(values, {
            "GATEWAY_REALM": "spectrace",
            "GATEWAY_AUDIENCE": "spectrace-api",
            "GATEWAY_ALLOWED_ORIGINS": "https://hxj04121-lab.github.io,https://id.example.test",
            "GATEWAY_SPECIFICATION_WRITE_ROLES": "ADMIN_DATA_MAINTENANCE",
            "GATEWAY_FORMULATION_WRITE_ROLES": "ADMIN_DATA_MAINTENANCE",
            "GATEWAY_COMPLIANCE_WRITE_ROLES": "CHANGE_MANAGER",
            "GATEWAY_LABEL_WRITE_ROLES": "LABEL_OFFICER,APPROVER,PUBLISHER",
            "GATEWAY_ISSUER": config()["GATEWAY_ISSUER"],
            "GATEWAY_JWK_SET_URI": "http://spectrace-keycloak-service.keycloak.svc.cluster.local:8080/auth/realms/spectrace/protocol/openid-connect/certs",
            "GATEWAY_KEYCLOAK": "http://spectrace-keycloak-service.keycloak.svc.cluster.local:8080",
            "GATEWAY_SPECIFICATION": "http://specification-specification.specification.svc.cluster.local:8080",
            "GATEWAY_FORMULATION": "http://formulation-formulation.formulation.svc.cluster.local:8080",
            "GATEWAY_COMPLIANCE": "http://compliance-compliance.compliance.svc.cluster.local:8080",
            "GATEWAY_LABEL_WORKFLOW": "http://label-workflow-label-workflow.label-workflow.svc.cluster.local:8080",
        })

    def test_rejects_missing_uri(self):
        bad = config()
        bad["GATEWAY_ISSUER"] = ""
        with self.assertRaises(ValueError):
            build_values(bad)

    def test_rejects_wrong_realm(self):
        bad = config()
        bad["GATEWAY_ISSUER"] = "https://id.example.test/realms/other"
        with self.assertRaises(ValueError):
            build_values(bad)

    def test_rejects_url_credentials_query_and_fragment(self):
        for suffix in ("?token=x", "#fragment", "@user:password"):
            with self.subTest(suffix=suffix):
                bad = config()
                bad["GATEWAY_ISSUER"] = f"https://user:password@id.example.test/auth/realms/spectrace{suffix}"
                with self.assertRaises(ValueError):
                    build_values(bad)


if __name__ == "__main__":
    unittest.main()
