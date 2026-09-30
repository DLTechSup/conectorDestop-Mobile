"""Lê as notificações do Windows (toasts) a partir do banco wpndatabase.db.

O Windows grava cada notificação exibida nesse banco SQLite. Lemos uma cópia
(o arquivo original fica travado / em modo WAL) a cada ~1,5 s e enviamos apenas
as novas. Não exige permissão especial nem app empacotado.
"""
import os
import re
import shutil
import sqlite3
import tempfile
import threading
import xml.etree.ElementTree as ET

DB_DIR = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Microsoft", "Windows", "Notifications")
DB_PATH = os.path.join(DB_DIR, "wpndatabase.db")

_SKIP_APPS = ("Windows.SystemToast.SecurityAndMaintenance",)


def friendly_app(primary_id: str) -> str:
    """'5319275A.WhatsAppDesktop_cv1g1gvanyjgm!App' -> 'WhatsAppDesktop'."""
    s = (primary_id or "").split("!")[0].split("_")[0]
    if s.lower().endswith(".exe"):
        s = s[:-4]
    elif "." in s:
        s = s.split(".")[-1]
    return s or "Windows"


def parse_payload(payload):
    if payload is None:
        return None
    if isinstance(payload, bytes):
        for enc in ("utf-8", "utf-16"):
            try:
                payload = payload.decode(enc)
                break
            except Exception:
                continue
        else:
            return None
    payload = payload.strip("\x00 ﻿")
    try:
        root = ET.fromstring(payload)
    except ET.ParseError:
        return None
    texts = [(t.text or "").strip() for t in root.iter("text")]
    texts = [t for t in texts if t]
    if not texts:
        return None
    return texts[0], "\n".join(texts[1:])


class NotificationWatcher(threading.Thread):
    def __init__(self, on_notification, enabled=lambda: True, log=print, db_path=DB_PATH):
        super().__init__(daemon=True)
        self.on_notification = on_notification
        self.enabled = enabled
        self.log = log
        self.db_path = db_path
        self._halt = threading.Event()
        self._last_id = None

    def stop(self):
        self._halt.set()

    def _read(self, after_id):
        tmp = tempfile.mkdtemp(prefix="cdwpn")
        try:
            for suffix in ("", "-wal", "-shm"):
                src = self.db_path + suffix
                if os.path.exists(src):
                    shutil.copy2(src, os.path.join(tmp, "wpn.db" + suffix))
            con = sqlite3.connect(os.path.join(tmp, "wpn.db"))
            try:
                if after_id is None:
                    row = con.execute("SELECT MAX(Id) FROM Notification").fetchone()
                    return [], (row[0] or 0)
                rows = con.execute(
                    "SELECT n.Id, n.Payload, h.PrimaryId FROM Notification n "
                    "LEFT JOIN NotificationHandler h ON n.HandlerId = h.RecordId "
                    "WHERE n.Type = 'toast' AND n.Id > ? ORDER BY n.Id",
                    (after_id,)).fetchall()
                return rows, None
            finally:
                con.close()
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def poll(self):
        """Uma rodada de leitura. Separado de run() para facilitar testes."""
        if self._last_id is None:
            _, self._last_id = self._read(None)  # ignora o histórico
            return
        rows, _ = self._read(self._last_id)
        for nid, payload, primary in rows:
            self._last_id = max(self._last_id, nid)
            if not self.enabled():
                continue
            if primary and any(s in primary for s in _SKIP_APPS):
                continue
            parsed = parse_payload(payload)
            if parsed:
                self.on_notification({
                    "t": "notif", "id": nid,
                    "app": friendly_app(primary or ""),
                    "title": parsed[0], "body": parsed[1],
                })

    def run(self):
        if not os.path.exists(self.db_path):
            self.log("Banco de notificações do Windows não encontrado.")
            return
        while not self._halt.is_set():
            try:
                self.poll()
            except Exception as e:  # banco travado, etc. — tenta de novo
                self.log(f"notificações: {e}")
            self._halt.wait(1.5)
