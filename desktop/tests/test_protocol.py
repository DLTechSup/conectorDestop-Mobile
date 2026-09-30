"""Teste ponta a ponta do protocolo (sem GUI, com captura simulada)."""
import asyncio
import hashlib
import json
import os
import socket
import ssl
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
os.environ["APPDATA"] = tempfile.mkdtemp()

import websockets  # noqa: E402

from conector import capture, config, server  # noqa: E402
from conector.notifications import friendly_app, parse_payload  # noqa: E402


class FakeCapturer:
    n = 0

    def reset(self): pass
    def close(self): pass

    async def frame(self, loop, monitor, maxw, q, view=(0, 0, 1, 1)):
        FakeCapturer.n += 1
        FakeCapturer.last_view = view
        return (b"\xff\xd8fakejpeg", view, (0.5, 0.25)) if FakeCapturer.n < 4 else None


def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p


def test_flow():
    capture.Capturer = FakeCapturer
    server.capture.Capturer = FakeCapturer
    server.capture.list_monitors = lambda: [{"i": 1, "w": 1920, "h": 1080}]
    cfg = config.Config()
    cfg["port"] = free_port()
    srv = server.RemoteServer(cfg, log=lambda *_: None)
    srv.start()
    assert srv.running, srv.error

    async def client():
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        url = f"wss://127.0.0.1:{cfg['port']}/"

        # chave errada
        async with websockets.connect(url, ssl=ctx) as ws:
            await ws.send(json.dumps({"t": "auth", "key": "errada"}))
            assert json.loads(await ws.recv())["t"] == "auth_fail"

        async with websockets.connect(url, ssl=ctx) as ws:
            der = ws.transport.get_extra_info("ssl_object").getpeercert(True)
            assert hashlib.sha256(der).hexdigest() == srv.fingerprint
            await ws.send(json.dumps({"t": "auth", "key": cfg["key"], "name": "teste"}))
            ok = json.loads(await ws.recv())
            assert ok["t"] == "auth_ok" and ok["monitors"][0]["w"] == 1920
            await ws.send(json.dumps({"t": "video", "on": True, "fps": 30,
                                      "vx": 0.25, "vy": 0.5, "vw": 0.5, "vh": 0.5}))
            frames = 0
            while frames < 3:
                m = await asyncio.wait_for(ws.recv(), 3)
                if isinstance(m, bytes):
                    assert m[0] == 2
                    import struct
                    assert struct.unpack(">ffffff", m[1:25]) == (0.25, 0.5, 0.5, 0.5, 0.5, 0.25)
                    assert m[25:].startswith(b"\xff\xd8")
                    frames += 1
            await ws.send(json.dumps({"t": "ping"}))
            srv.broadcast({"t": "notif", "app": "X", "title": "Oi", "body": ""})
            got = set()
            while len(got) < 2:
                m = await asyncio.wait_for(ws.recv(), 3)
                if isinstance(m, str):
                    got.add(json.loads(m)["t"])
            assert got == {"pong", "notif"}
            await ws.send(json.dumps({"t": "bye"}))

    asyncio.run(client())
    srv.stop()


def test_payload():
    xml = ('<toast><visual><binding template="ToastGeneric"><text>Maria</text>'
           '<text>Oi!</text><text>2</text></binding></visual></toast>')
    assert parse_payload(xml.encode("utf-16")) == ("Maria", "Oi!\n2")
    assert parse_payload(xml) == ("Maria", "Oi!\n2")
    assert friendly_app("5319275A.WhatsAppDesktop_cv1g1gvanyjgm!App") == "WhatsAppDesktop"
    assert friendly_app("Chrome") == "Chrome"


def test_watcher(tmp_path=None):
    import sqlite3
    from conector.notifications import NotificationWatcher
    d = tempfile.mkdtemp(); p = os.path.join(d, "wpndatabase.db")
    con = sqlite3.connect(p)
    con.execute("CREATE TABLE NotificationHandler(RecordId INTEGER PRIMARY KEY, PrimaryId TEXT)")
    con.execute("CREATE TABLE Notification(Id INTEGER PRIMARY KEY, HandlerId INT, Type TEXT, Payload BLOB)")
    con.execute("INSERT INTO NotificationHandler VALUES(1,'App.Zap_abc!App')")
    con.execute("INSERT INTO Notification VALUES(1,1,'toast','<toast><visual><binding><text>velha</text></binding></visual></toast>')")
    con.commit()
    out = []
    w = NotificationWatcher(out.append, db_path=p)
    w.poll()
    con.execute("INSERT INTO Notification VALUES(2,1,'toast','<toast><visual><binding><text>nova</text><text>corpo</text></binding></visual></toast>')")
    con.commit(); con.close()
    w.poll()
    assert [o["title"] for o in out] == ["nova"] and out[0]["app"] == "Zap"




def test_ui_api():
    import urllib.error
    import urllib.request
    from conector.controller import Controller
    from conector.uiserver import UIServer
    ctl = Controller()
    ui = UIServer(ctl)
    base = f"http://127.0.0.1:{ui.port}"

    def post(name, body=None, token=None):
        req = urllib.request.Request(f"{base}/api/{name}", json.dumps(body or {}).encode(),
                                     {"X-Token": token or "", "Content-Type": "application/json"})
        return urllib.request.urlopen(req)

    try:
        post("state", token="errado")
        assert False, "deveria recusar"
    except urllib.error.HTTPError as e:
        assert e.code == 403
    st = json.loads(post("state", token=ui.token).read())
    assert st["name"] == "DeskLink" and st["key"] == ctl.cfg["key"]
    post("set_option", {"key": "allow_control", "value": False}, ui.token)
    assert ctl.cfg["allow_control"] is False
    try:
        post("set_option", {"key": "port", "value": 1}, ui.token)
        assert False
    except urllib.error.HTTPError as e:
        assert e.code == 400
    assert b"<div id=\"root\">" in urllib.request.urlopen(base + "/").read()
    assert urllib.request.urlopen(base + "/../../etc/passwd").status == 200  # cai no index, nunca fora
    ctl.shutdown(); ui.close()


if __name__ == "__main__":
    test_payload(); test_watcher(); test_flow(); test_ui_api(); print("OK")
