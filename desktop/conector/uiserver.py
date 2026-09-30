"""Servidor HTTP local (127.0.0.1) que entrega a interface React e a API JSON.

A API exige o token secreto gerado a cada execução: outros programas ou sites
abertos no navegador não conseguem controlar o DeskLink.
"""
import json
import mimetypes
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from .config import resource_dir

API = {
    "state": lambda c, a: c.state(),
    "set_option": lambda c, a: c.set_option(a["key"], a["value"]),
    "set_autostart": lambda c, a: c.set_autostart(a["value"]),
    "regenerate_key": lambda c, a: c.regenerate_key(),
    "save_external": lambda c, a: c.save_external(a["host"]),
    "apply_port": lambda c, a: c.apply_port(a["port"]),
    "public_ip": lambda c, a: {"ip": c.public_ip()},
    "test_notification": lambda c, a: c.test_notification(),
    "disconnect_all": lambda c, a: c.disconnect_all(),
}


class UIServer:
    def __init__(self, controller, on_action=None, static_dir=None):
        self.token = secrets.token_urlsafe(24)
        self.controller = controller
        self.on_action = on_action or {}  # ex.: {"hide": fn, "quit": fn}
        self.static = Path(static_dir) if static_dir else resource_dir() / "ui" / "dist"
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _json(self, code, obj):
                body = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                path = self.path.split("?")[0].split("#")[0]
                rel = path.lstrip("/") or "index.html"
                f = (outer.static / rel).resolve()
                root = outer.static.resolve()
                if root not in f.parents and f != root or not f.is_file():
                    f = root / "index.html"
                if not f.is_file():
                    self._json(404, {"error": "interface não encontrada (rode npm run build em desktop/ui)"})
                    return
                data = f.read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", mimetypes.guess_type(f.name)[0] or "application/octet-stream")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_POST(self):
                if not secrets.compare_digest(self.headers.get("X-Token", ""), outer.token):
                    self._json(403, {"error": "token inválido"})
                    return
                name = self.path.rstrip("/").rsplit("/", 1)[-1]
                try:
                    n = int(self.headers.get("Content-Length") or 0)
                    args = json.loads(self.rfile.read(n) or b"{}")
                    if name in outer.on_action:
                        outer.on_action[name]()
                        self._json(200, {"ok": True})
                        return
                    if name not in API:
                        self._json(404, {"error": "método desconhecido"})
                        return
                    res = API[name](outer.controller, args)
                    self._json(200, res if isinstance(res, dict) else {"ok": True})
                except Exception as e:
                    self._json(400, {"error": str(e)})

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.port}/?t={self.token}"

    def close(self):
        self.httpd.shutdown()
