import unittest

from scripts.staging.verify_service_digests import SERVICES, pinned_images


class StagingServiceDigestsTests(unittest.TestCase):
    def test_all_four_services_are_pinned_to_their_published_ghcr_repositories(self):
        images = pinned_images()
        self.assertEqual(tuple(images), SERVICES)
        self.assertEqual(set(images), {"specification", "formulation", "compliance", "label-workflow"})
        for service, image in images.items():
            with self.subTest(service=service):
                self.assertEqual(image["repository"], f"ghcr.io/hxj04121-lab/foodlabelflow-microservices/{service}")
                self.assertRegex(image["digest"], r"^sha256:[a-f0-9]{64}$")


if __name__ == "__main__":
    unittest.main()
