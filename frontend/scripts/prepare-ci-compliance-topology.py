"""Declare local test-only Compliance queues; M5 owns deployed topology."""
import base64
import json
import os
import urllib.parse
import urllib.request


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("This bootstrap is restricted to the workflow's disposable CI broker")
    # Fixed loopback broker started by m3-real-services; no staging endpoint/credentials accepted.
    authorization = "Basic " + base64.b64encode(b"spectrace:local-only-rabbitmq").decode()

    def put(path, data):
        request = urllib.request.Request("http://127.0.0.1:15672/api/" + path,
            data=json.dumps(data).encode(), method="POST" if path.startswith("bindings/") else "PUT",
            headers={"Authorization": authorization, "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=10) as response:
            assert response.status in (201, 204), "Local topology declaration failed"

    put("exchanges/%2F/spectrace.events", {"type": "topic", "durable": True, "auto_delete": False, "arguments": {}})
    for queue, routes in {
        "compliance.specification-published": ["specification.published.v1"],
        "compliance.formula-published": ["formula.published.v1"],
        "compliance.label-published": ["label.published.v1", "label.published.v2"],
    }.items():
        dead = "spectrace.events.dlx." + queue
        put("exchanges/%2F/" + dead, {"type": "fanout", "durable": True, "auto_delete": False, "arguments": {}})
        put("queues/%2F/" + queue, {"durable": True, "auto_delete": False, "arguments": {
            "x-queue-type": "quorum", "x-delivery-limit": 5, "x-dead-letter-exchange": dead}})
        put("queues/%2F/" + queue + ".dlq", {"durable": True, "auto_delete": False, "arguments": {"x-queue-type": "quorum"}})
        put("bindings/%2F/e/" + dead + "/q/" + queue + ".dlq", {"routing_key": "", "arguments": {}})
        for route in routes:
            put("bindings/%2F/e/spectrace.events/q/" + urllib.parse.quote(queue, safe=""), {"routing_key": route, "arguments": {}})
    print("Disposable CI Compliance queues declared; no deployed topology acceptance claimed")


if __name__ == "__main__":
    main()
