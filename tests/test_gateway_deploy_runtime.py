import runpy
import unittest
from pathlib import Path

build_values = runpy.run_path(str(Path(__file__).parents[1] / "platform/gateway/deploy-runtime-values.py"))["build_values"]


def config():
    return {
        "GATEWAY_ISSUER": "https://id.example.test/auth/realms/spectrace",
        "GATEWAY_JWK_SET_URI": "http://keycloak.keycloak.svc.cluster.local:8080/auth/realms/spectrace/protocol/openid-connect/certs",
        "GATEWAY_KEYCLOAK": "http://keycloak.keycloak.svc.cluster.local:8080",
        "GATEWAY_SPECIFICATION": "http://specification-specification.specification.svc.cluster.local:8080",
        "GATEWAY_FORMULATION": "http://formulation-formulation.formulation.svc.cluster.local:8080",
        "GATEWAY_COMPLIANCE": "http://compliance-compliance.compliance.svc.cluster.local:8080",
        "GATEWAY_LABEL_WORKFLOW": "http://label-workflow-label-workflow.label-workflow.svc.cluster.local:8080",
    }


class GatewayDeployRuntimeTests(unittest.TestCase):
    def test_requires_every_runtime_uri_and_produces_only_env_values(self):
        values = build_values(config())
        self.assertEqual({row["name"] for row in values["env"]}, set(config()))
        self.assertEqual(values["env"][0]["value"], config()["GATEWAY_ISSUER"])

    def test_rejects_missing_uri(self):
        bad = config()
        bad["GATEWAY_ISSUER"] = ""
        with self.assertRaises(ValueError):
            build_values(bad)

    def test_rejects_wrong_realm_or_jwks_path(self):
        bad = config()
        bad["GATEWAY_ISSUER"] = "https://id.example.test/realms/other"
        with self.assertRaises(ValueError):
            build_values(bad)
        bad = config()
        bad["GATEWAY_JWK_SET_URI"] = "https://id.example.test/not-jwks"
        with self.assertRaises(ValueError):
            build_values(bad)

    def test_rejects_business_uri_outside_the_approved_namespace_or_port(self):
        for value in (
            "http://specification-service.wrong.svc.cluster.local:8080",
            "http://specification-specification.specification.svc.cluster.local:80",
            "https://specification.example.test",
        ):
            with self.subTest(value=value):
                bad = config()
                bad["GATEWAY_SPECIFICATION"] = value
                with self.assertRaises(ValueError):
                    build_values(bad)

    def test_rejects_url_credentials_query_and_fragment(self):
        for suffix in ("?token=x", "#fragment", "@user:password"):
            with self.subTest(suffix=suffix):
                bad = config()
                bad["GATEWAY_FORMULATION"] = f"http://formulation{suffix}.formulation.svc.cluster.local:8080"
                with self.assertRaises(ValueError):
                    build_values(bad)


if __name__ == "__main__":
    unittest.main()
