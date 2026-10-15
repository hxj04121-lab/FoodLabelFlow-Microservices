import re
import unittest
from pathlib import Path


CONFIG = Path(__file__).resolve().parents[1] / "deploy/observability/alloy/config.yaml"


class RabbitMQDlqScrapeTest(unittest.TestCase):
    def test_scrapes_the_detailed_queue_family(self):
        config = CONFIG.read_text(encoding="utf-8")
        block = config.split('discovery.relabel "rabbitmq" {', 1)[1].split(
            'prometheus.scrape "rabbitmq" {', 1
        )[0]
        rules = re.findall(r"\brule\s*\{([^{}]*)\}", block, re.DOTALL)
        targets = {}
        for rule in rules:
            label = re.search(r'target_label\s*=\s*"([^"]+)"', rule)
            replacement = re.search(r'replacement\s*=\s*"([^"]+)"', rule)
            if label and replacement:
                targets[label.group(1)] = replacement.group(1)

        self.assertEqual(targets.get("__address__"), "$1:15692")
        self.assertEqual(targets.get("__metrics_path__"), "/metrics/detailed")
        self.assertEqual(targets.get("__param_family"), "queue_coarse_metrics")


if __name__ == "__main__":
    unittest.main()
