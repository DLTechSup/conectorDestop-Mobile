"""Servidor WebSocket (TLS) que transmite a tela e recebe mouse/teclado."""
import asyncio
import hmac
import json
import socket
import ssl
import struct
import threading
import time

import websockets

from . import capture
from .audio import AudioCapture
from .config import Config, ensure_certificate

AUTH_TIMEOUT = 10
MAX_FAILS = 5
BLOCK_SECONDS = 60


class Client:
    def __init__(self, ws):
        self.ws = ws
        self.name = "?"
        self.ip = ws.remote_address[0] if ws.remote_address else "?"
        self.video = False
        self.maxw, self.fps, self.quality, self.monitor = 1280, 15, 55, 1
        self.view = (0.0, 0.0, 1.0, 1.0)  # zoom: região visível (x, y, w, h)
        self.evt = asyncio.Event()
        self.capturer = capture.Capturer()
        self.since = time.time()
        self.audio = False
        self.audio_fmt = (32000, 2)
        self.audio_q = None  # criada dentro do loop asyncio


class RemoteServer:
    def __init__(self, cfg: Config, log=print):
        self.cfg = cfg
        self.log = log
        self.clients: list[Client] = []
        self.fingerprint = ""
        self.running = False
        self.error = ""
        self._loop = None
        self._halt = None
        self._thread = None
        self._fails = {}  # ip -> (count, blocked_until)
        self._input = None
        self.audio_capture = AudioCapture(self._on_audio_chunk, log)

    # ---------------- ciclo de vida ----------------
    def start(self):
        cert, key, self.fingerprint = ensure_certificate()
        self._ssl = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self._ssl.minimum_version = ssl.TLSVersion.TLSv1_2
        self._ssl.load_cert_chain(cert, key)
        ready = threading.Event()
        self._thread = threading.Thread(target=self._run, args=(ready,), daemon=True)
        self._thread.start()
        ready.wait(5)

    def stop(self):
        if self._loop and self._halt and not self._halt.done():
            self._loop.call_soon_threadsafe(
                lambda: self._halt.done() or self._halt.set_result(True))
        if self._thread:
            self._thread.join(3)

    def _run(self, ready):
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        self._loop.run_until_complete(self._main(ready))

    async def _main(self, ready):
        self._halt = self._loop.create_future()
        try:
            async with websockets.serve(self._handler, "0.0.0.0", self.cfg["port"],
                                        ssl=self._ssl, max_size=1 << 20,
                                        ping_interval=15, ping_timeout=20):
                self.running, self.error = True, ""
                self.log(f"Servidor ouvindo na porta {self.cfg['port']}")
                ready.set()
                await self._halt
        except Exception as e:
            self.error = str(e)
            self.log(f"Falha ao iniciar servidor: {e}")
            ready.set()
        finally:
            self.running = False

    # ---------------- API usada pela GUI / notificações ----------------
    def broadcast(self, message: dict):
        if not (self._loop and self.running):
            return
        data = json.dumps(message)

        async def _send():
            for c in list(self.clients):
                try:
                    await c.ws.send(data)
                except Exception:
                    pass
        asyncio.run_coroutine_threadsafe(_send(), self._loop)

    def disconnect_all(self):
        if not (self._loop and self.running):
            return

        async def _close():
            for c in list(self.clients):
                await c.ws.close(1000, "desconectado pelo PC")
        asyncio.run_coroutine_threadsafe(_close(), self._loop)

    # ---------------- conexão ----------------
    def _blocked(self, ip):
        n, until = self._fails.get(ip, (0, 0))
        return n >= MAX_FAILS and time.time() < until

    def _fail(self, ip):
        n, _ = self._fails.get(ip, (0, 0))
        self._fails[ip] = (n + 1, time.time() + BLOCK_SECONDS)

    async def _handler(self, ws):
        client = Client(ws)
        if self._blocked(client.ip):
            await ws.close(1008, "bloqueado temporariamente")
            return
        try:
            raw = await asyncio.wait_for(ws.recv(), AUTH_TIMEOUT)
            msg = json.loads(raw)
            ok = (msg.get("t") == "auth" and
                  hmac.compare_digest(str(msg.get("key", "")).encode(),
                                      self.cfg["key"].encode()))
        except Exception:
            ok = False
        if not ok:
            self._fail(client.ip)
            try:
                await ws.send(json.dumps({"t": "auth_fail"}))
                await ws.close(1008, "chave inválida")
            except Exception:
                pass
            return
        self._fails.pop(client.ip, None)
        client.name = str(msg.get("name", "celular"))[:40]
        self.clients.append(client)
        self.log(f"Conectado: {client.name} ({client.ip})")
        await ws.send(json.dumps({
            "t": "auth_ok", "host": socket.gethostname(),
            "monitors": capture.list_monitors(),
            "control": bool(self.cfg["allow_control"]),
            "audio": bool(self.cfg["send_audio"]),
        }))
        client.audio_q = asyncio.Queue(maxsize=24)  # ~0,7 s; descarta o mais antigo se lotar
        streamer = asyncio.ensure_future(self._stream(client))
        audio_sender = asyncio.ensure_future(self._audio_sender(client))
        try:
            async for raw in ws:
                if isinstance(raw, bytes):
                    continue
                try:
                    await self._on_message(client, json.loads(raw))
                except websockets.ConnectionClosed:
                    raise
                except Exception as e:
                    self.log(f"msg inválida de {client.ip}: {e}")
        except websockets.ConnectionClosed:
            pass
        finally:
            streamer.cancel()
            audio_sender.cancel()
            client.audio = False
            self._update_audio()
            client.capturer.close()
            if client in self.clients:
                self.clients.remove(client)
            self.log(f"Desconectado: {client.name} ({client.ip})")

    async def _on_message(self, c: Client, m: dict):
        t = m.get("t")
        if t == "video":
            c.video = bool(m.get("on"))
            c.maxw = min(max(int(m.get("maxw", c.maxw)), 320), 3840)
            c.fps = min(max(int(m.get("fps", c.fps)), 1), 30)
            c.quality = min(max(int(m.get("q", c.quality)), 10), 95)
            c.monitor = int(m.get("monitor", c.monitor))
            c.view = tuple(float(m.get(k, d)) for k, d in
                           (("vx", 0.0), ("vy", 0.0), ("vw", 1.0), ("vh", 1.0)))
            c.capturer.reset()
            (c.evt.set if c.video else c.evt.clear)()
        elif t == "audio":
            c.audio = bool(m.get("on")) and bool(self.cfg["send_audio"])
            c.audio_fmt = (min(max(int(m.get("rate", 32000)), 8000), 48000),
                           2 if int(m.get("ch", 2)) >= 2 else 1)
            self._update_audio(c.audio_fmt if c.audio else None)
        elif t == "ping":
            await c.ws.send('{"t":"pong"}')
        elif t == "bye":
            await c.ws.close(1000, "bye")
        elif t in ("mouse", "key", "text") and self.cfg["allow_control"]:
            if self._input is None:
                from .inputctl import InputController
                self._input = InputController()
            if t == "mouse":
                m.setdefault("m", c.monitor)
                self._input.handle_mouse(m)
            elif t == "key":
                self._input.handle_key(m)
            else:
                self._input.handle_text(m)

    # ---------------- áudio ----------------
    def _update_audio(self, fmt=None):
        want = any(c.audio for c in self.clients)
        self.audio_capture.set(want, *(fmt or (None, None)))

    def _on_audio_chunk(self, rate, ch, pcm):
        """Thread de captura -> fila de cada celular que pediu áudio."""
        if not self._loop:
            return
        packet = b"\x03" + struct.pack(">IB", rate, ch) + pcm

        def put():
            for c in self.clients:
                if c.audio and c.audio_q is not None and c.audio_fmt == (rate, ch):
                    if c.audio_q.full():
                        c.audio_q.get_nowait()
                    c.audio_q.put_nowait(packet)
        self._loop.call_soon_threadsafe(put)

    async def _audio_sender(self, c: Client):
        try:
            while True:
                await c.ws.send(await c.audio_q.get())
        except (asyncio.CancelledError, websockets.ConnectionClosed):
            pass
        except Exception as e:
            self.log(f"erro no áudio: {e}")

    async def _stream(self, c: Client):
        loop = asyncio.get_running_loop()
        try:
            while True:
                await c.evt.wait()
                t0 = time.time()
                res = await c.capturer.frame(loop, c.monitor, c.maxw, c.quality, c.view)
                if res:
                    jpg, used, cur = res
                    # 0x02 + região (4 floats) + cursor (2 floats, -1 = desconhecido) + JPEG
                    await c.ws.send(b"\x02" + struct.pack(">ffffff", *used, *cur) + jpg)
                await asyncio.sleep(max(0.005, 1.0 / c.fps - (time.time() - t0)))
        except (asyncio.CancelledError, websockets.ConnectionClosed):
            pass
        except Exception as e:
            self.log(f"erro no streaming: {e}")
