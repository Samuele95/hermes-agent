"""Small OpenAI-compatible servers for the app's endpoint tests: one that lists models (optionally behind a key),
one that refuses every request (403, as Ollama does for a host name it does not know)."""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class FakeEndpoint:
    def __init__(self, models: list[str] | None = None, key: str | None = None, status: int | None = None):
        self.models, self.key, self.status, self.seen_auth = models or [], key, status, []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # http.server's name
                owner.seen_auth.append(self.headers.get("Authorization"))
                if owner.status:
                    return self._answer(owner.status, {"error": "refused"})
                if owner.key and self.headers.get("Authorization") != f"Bearer {owner.key}":
                    return self._answer(401, {"error": "unauthorized"})
                if self.path.rstrip("/") in ("/v1/models", "/models"):
                    return self._answer(200, {"object": "list", "data": [{"id": m, "object": "model"} for m in owner.models]})
                return self._answer(404, {"error": "not found"})

            def _answer(self, code, body):
                data = json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *_args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def closed_port() -> int:
    """A port nothing listens on (bound, then released)."""
    import socket
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]
