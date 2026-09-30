"""Lógica do aplicativo (sem interface): servidor remoto, notificações e configurações."""
import socket
import time
import urllib.request
from collections import deque

from . import APP_NAME, VERSION, system
from .config import Config, local_addresses, pair_url
from .notifications import NotificationWatcher
from .server import RemoteServer


class Controller:
    def __init__(self):
        self.cfg = Config()
        self._logs = deque(maxlen=80)
        self.server = RemoteServer(self.cfg, log=self.log)
        self.server.start()
        self.watcher = NotificationWatcher(
            self._forward, enabled=lambda: self.cfg["send_notifications"], log=self.log)
        self.watcher.start()

    # -- infra
    def log(self, msg):
        self._logs.append({"t": time.strftime("%H:%M:%S"), "msg": str(msg)})

    def _forward(self, n):
        self.server.broadcast(n)

    def shutdown(self):
        self.watcher.stop()
        self.server.stop()

    # -- API usada pela interface
    def state(self):
        s = self.server
        return {
            "name": APP_NAME, "version": VERSION, "hostname": socket.gethostname(),
            "running": s.running, "error": s.error, "port": self.cfg["port"],
            "key": self.cfg["key"], "external_host": self.cfg["external_host"],
            "allow_control": self.cfg["allow_control"],
            "send_notifications": self.cfg["send_notifications"],
            "autostart": system.get_autostart(),
            "autostart_supported": system.autostart_supported(),
            "addresses": [{"label": l, "ip": ip} for l, ip in local_addresses()],
            "pair_url": pair_url(self.cfg, s.fingerprint) if s.fingerprint else "",
            "clients": [{"name": c.name, "ip": c.ip, "since": int(c.since)} for c in s.clients],
            "logs": list(self._logs)[-30:],
        }

    def set_option(self, key, value):
        if key not in ("allow_control", "send_notifications"):
            raise ValueError("opção inválida")
        self.cfg[key] = bool(value)

    def set_autostart(self, value):
        if not system.set_autostart(bool(value)):
            raise ValueError("Disponível apenas no .exe para Windows")

    def regenerate_key(self):
        self.cfg.regenerate_key()
        self.server.disconnect_all()

    def save_external(self, host):
        self.cfg["external_host"] = str(host).strip()[:253]

    def apply_port(self, port):
        port = int(port)
        if not 1024 <= port <= 65535:
            raise ValueError("Porta inválida (use 1024–65535)")
        self.cfg["port"] = port
        self.server.stop()
        self.server = RemoteServer(self.cfg, log=self.log)
        self.server.start()
        self.watcher.on_notification = self._forward
        if not self.server.running:
            raise ValueError(self.server.error or "Não foi possível abrir a porta")

    def public_ip(self):
        return urllib.request.urlopen("https://api.ipify.org", timeout=6).read().decode().strip()

    def test_notification(self):
        self.server.broadcast({"t": "notif", "id": 0, "app": APP_NAME,
                               "title": "Notificação de teste",
                               "body": "Se você ouviu o som, está funcionando!"})

    def disconnect_all(self):
        self.server.disconnect_all()
