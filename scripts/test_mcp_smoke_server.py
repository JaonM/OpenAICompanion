#!/usr/bin/env python3
"""Protocol smoke checks for the local iOS Simulator MCP fixture."""

import json
import threading
import unittest
from urllib.request import Request, urlopen

from mcp_smoke_server import McpSmokeServer


class McpSmokeServerTest(unittest.TestCase):
    def setUp(self):
        self.server = McpSmokeServer(("127.0.0.1", 0))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"
        self.next_id = 0

    def tearDown(self):
        self.server.shutdown()
        self.thread.join(timeout=5)
        self.server.server_close()

    def rpc(self, method, params=None):
        self.next_id += 1
        params = dict(params or {})
        params["_meta"] = {"io.modelcontextprotocol/protocolVersion": "2026-07-28"}
        request = Request(
            f"{self.base}/mcp",
            data=json.dumps({"jsonrpc": "2.0", "id": self.next_id,
                             "method": method, "params": params}).encode(),
            headers={"Content-Type": "application/json", "MCP-Protocol-Version": "2026-07-28",
                     "Mcp-Method": method,
                     **({"Mcp-Name": params["name"]} if method == "tools/call" else {})},
        )
        with urlopen(request, timeout=5) as response:
            reply = json.load(response)
        self.assertEqual(self.next_id, reply["id"])
        return reply["result"]

    def test_discover_list_echo_and_error(self):
        self.assertIn("2026-07-28", self.rpc("server/discover")["supportedVersions"])
        self.assertEqual(["echo", "report_error", "ask_name"],
                         [tool["name"] for tool in self.rpc("tools/list")["tools"]])
        result = self.rpc("tools/call", {"name": "echo", "arguments": {"text": "hello"}})
        self.assertEqual("hello", result["content"][0]["text"])
        error = self.rpc("tools/call", {"name": "report_error", "arguments": {}})
        self.assertTrue(error["isError"])
        self.assertIn("network timeout", error["content"][0]["text"])
        with urlopen(f"{self.base}/stats", timeout=5) as response:
            counts = json.load(response)
        self.assertEqual(1, counts["echo"])
        self.assertEqual(1, counts["report_error"])

    def test_input_required_form_round_trip(self):
        initial = self.rpc("tools/call", {"name": "ask_name", "arguments": {}})
        self.assertEqual("input_required", initial["resultType"])
        self.assertEqual("elicitation/create", initial["inputRequests"]["name"]["method"])
        completed = self.rpc("tools/call", {
            "name": "ask_name", "arguments": {},
            "requestState": initial["requestState"],
            "inputResponses": {"name": {"action": "accept", "content": {"name": "Tester"}}},
        })
        self.assertEqual("你好，Tester", completed["content"][0]["text"])


if __name__ == "__main__":
    unittest.main()
