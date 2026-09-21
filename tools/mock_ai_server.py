#!/usr/bin/env python3
"""Tiny test server for verifying the Minecraft bridge without a real LLM.
This is NOT an AI model. It only echoes a canned response in Ollama's response shape.
"""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json

HOST = "127.0.0.1"
PORT = 11434

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        try:
            payload = json.loads(raw or b"{}")
            messages = payload.get("messages", [])
            last = messages[-1].get("content", "") if messages else ""
        except Exception:
            last = ""

        body = json.dumps({
            "message": {
                "role": "assistant",
                "content": "Bridge works! I received Minecraft context and your request. " + (last[-160:] if last else "")
            },
            "done": True
        }).encode("utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("[mock-ai] " + (fmt % args))

print(f"Mock LocalAI server listening at http://{HOST}:{PORT}")
HTTPServer((HOST, PORT), Handler).serve_forever()
