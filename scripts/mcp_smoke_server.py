#!/usr/bin/env python3
"""Local-only MCP fixture for iOS Simulator end-to-end checks; not a production server."""

import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


TOOLS = [
    {
        "name": "echo",
        "description": "Return the supplied text unchanged.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "report_error",
        "description": "Return an MCP tool-level error to test non-retry behavior.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "ask_name",
        "description": "Ask for a name through MCP input_required form elicitation.",
        "inputSchema": {"type": "object", "properties": {}},
    },
]


class McpSmokeServer(ThreadingHTTPServer):
    def __init__(self, address):
        super().__init__(address, McpSmokeHandler)
        self.counts = {tool["name"]: 0 for tool in TOOLS}
        self.counts_lock = threading.Lock()


class McpSmokeHandler(BaseHTTPRequestHandler):
    def _send(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path != "/stats":
            self._send(404, {"error": "not found"})
            return
        with self.server.counts_lock:
            self._send(200, dict(self.server.counts))

    def do_POST(self):
        if self.path != "/mcp":
            self._send(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._send(400, {"error": "invalid Content-Length"})
            return
        if not 0 < length <= 1_048_576:
            self._send(413, {"error": "request size is invalid"})
            return
        request = None
        try:
            request = json.loads(self.rfile.read(length))
            method = request["method"]
            params = request["params"]
            request_id = request["id"]
            if not isinstance(params, dict) or not isinstance(method, str):
                raise ValueError("invalid request")
            if self.headers.get("MCP-Protocol-Version") != "2026-07-28":
                raise ValueError("wrong protocol version")
            if self.headers.get("Mcp-Method") != method:
                raise ValueError("Mcp-Method does not match")
            if params.get("_meta", {}).get("io.modelcontextprotocol/protocolVersion") != "2026-07-28":
                raise ValueError("missing request metadata")
            if method == "server/discover":
                result = {
                    "resultType": "complete",
                    "supportedVersions": ["2026-07-28"],
                    "capabilities": {"tools": {}},
                }
            elif method == "tools/list":
                result = {"resultType": "complete", "tools": TOOLS}
            elif method == "tools/call":
                name = params.get("name")
                if self.headers.get("Mcp-Name") != name:
                    raise ValueError("Mcp-Name does not match")
                if name not in self.server.counts:
                    raise ValueError("unknown tool")
                with self.server.counts_lock:
                    self.server.counts[name] += 1
                result = self._call_tool(name, params)
            else:
                self._send(200, {"jsonrpc": "2.0", "id": request_id,
                                 "error": {"code": -32601, "message": "method not found"}})
                return
        except (AttributeError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
            self._send(400, {"jsonrpc": "2.0", "id": request.get("id") if isinstance(request, dict) else None,
                             "error": {"code": -32600, "message": str(error)}})
            return
        self._send(200, {"jsonrpc": "2.0", "id": request_id, "result": result})

    def _call_tool(self, name, params):
        arguments = params.get("arguments")
        if not isinstance(arguments, dict):
            raise ValueError("arguments must be an object")
        if name == "echo":
            text = arguments.get("text")
            if not isinstance(text, str):
                raise ValueError("echo.text must be a string")
            return {"resultType": "complete", "content": [{"type": "text", "text": text}]}
        if name == "report_error":
            return {"resultType": "complete", "isError": True,
                    "content": [{"type": "text", "text": "network timeout mentioned by tool; do not retry"}]}

        responses = params.get("inputResponses")
        if responses is None:
            return {
                "resultType": "input_required",
                "requestState": "ask-name-v1",
                "inputRequests": {
                    "name": {
                        "method": "elicitation/create",
                        "params": {
                            "mode": "form",
                            "message": "请输入用于验收的昵称（不要输入真实凭据）",
                            "requestedSchema": {
                                "type": "object",
                                "properties": {"name": {"type": "string", "title": "昵称"}},
                                "required": ["name"],
                            },
                        },
                    },
                },
            }
        if params.get("requestState") != "ask-name-v1":
            raise ValueError("requestState was not echoed")
        answer = responses.get("name") if isinstance(responses, dict) else None
        if not isinstance(answer, dict) or answer.get("action") != "accept":
            return {"resultType": "complete", "isError": True,
                    "content": [{"type": "text", "text": "user declined input"}]}
        person = answer.get("content", {}).get("name")
        if not isinstance(person, str) or not person:
            raise ValueError("name is required")
        return {"resultType": "complete", "content": [{"type": "text", "text": f"你好，{person}"}]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    server = McpSmokeServer(("127.0.0.1", args.port))
    print(f"MCP fixture: http://127.0.0.1:{server.server_port}/mcp", flush=True)
    print(f"Call counts: http://127.0.0.1:{server.server_port}/stats", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
