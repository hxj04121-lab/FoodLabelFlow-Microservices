"""Record only a digest actually reported by Docker after registry publication."""
import json
import os
from pathlib import Path
import re
import subprocess

image = os.environ["GATEWAY_IMAGE"]
commit = os.environ["GITHUB_SHA"]
digests = json.loads(subprocess.run(["docker", "image", "inspect", image, "--format", "{{json .RepoDigests}}"],
                    text=True, capture_output=True, check=True).stdout)
repository = image.rsplit(":", 1)[0]
reference = next((value for value in digests if value.startswith(repository + "@sha256:")), None)
assert reference and re.fullmatch(r"sha256:[0-9a-f]{64}", reference.split("@", 1)[1]), "No registry digest was recorded"
record = {"commit": commit, "image": image, "digestReference": reference,
          "port": 8080, "readiness": "/actuator/health/readiness", "liveness": "/actuator/health/liveness"}
Path("gateway-release.json").write_text(json.dumps(record, indent=2) + "\n")
with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
    summary.write(f"## Gateway release\n\nCommit: `{commit}`\n\nImage: `{image}`\n\nDeploy by digest: `{reference}`\n")
print("Published Gateway image and actual digest recorded")
