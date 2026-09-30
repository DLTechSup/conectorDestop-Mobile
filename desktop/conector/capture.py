"""Captura de tela (mss) -> JPEG, com cursor desenhado e descarte de quadros idênticos."""
import ctypes
import io
import sys
import zlib
from concurrent.futures import ThreadPoolExecutor

import mss
from PIL import Image, ImageDraw

_CURSOR = [(0, 0), (0, 17), (4, 13), (7, 20), (10, 19), (7, 12), (12, 12)]


def cursor_pos():
    if sys.platform != "win32":
        return None

    class POINT(ctypes.Structure):
        _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]

    pt = POINT()
    if ctypes.windll.user32.GetCursorPos(ctypes.byref(pt)):
        return pt.x, pt.y
    return None


def list_monitors():
    with mss.mss() as sct:
        return [{"i": i, "w": m["width"], "h": m["height"]}
                for i, m in enumerate(sct.monitors) if i > 0]


def monitor_rect(index: int):
    """(left, top, width, height) do monitor `index` (1-based)."""
    with mss.mss() as sct:
        mons = sct.monitors
        m = mons[index] if 0 < index < len(mons) else mons[1]
        return m["left"], m["top"], m["width"], m["height"]


class Capturer:
    """Uma instância por cliente. O mss precisa ser usado sempre na mesma thread."""

    def __init__(self):
        self._ex = ThreadPoolExecutor(max_workers=1)
        self._sct = None
        self._last = None

    def reset(self):
        self._last = None

    async def frame(self, loop, monitor, maxw, quality):
        return await loop.run_in_executor(self._ex, self._grab, monitor, maxw, quality)

    def _grab(self, monitor, maxw, quality):
        if self._sct is None:
            self._sct = mss.mss()
        mons = self._sct.monitors
        m = mons[monitor] if 0 < monitor < len(mons) else mons[1]
        shot = self._sct.grab(m)
        img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
        scale = 1.0
        if img.width > maxw:
            scale = maxw / img.width
            img = img.resize((maxw, max(1, int(img.height * scale))), Image.BILINEAR)
        cur = cursor_pos()
        cpos = None
        if cur:
            cx = int((cur[0] - m["left"]) * scale)
            cy = int((cur[1] - m["top"]) * scale)
            if 0 <= cx < img.width and 0 <= cy < img.height:
                cpos = (cx, cy)
        digest = zlib.crc32(img.tobytes()) ^ hash(cpos)
        if digest == self._last:
            return None
        self._last = digest
        if cpos:
            d = ImageDraw.Draw(img)
            pts = [(cpos[0] + x, cpos[1] + y) for x, y in _CURSOR]
            d.polygon(pts, fill="white", outline="black")
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=quality)
        return buf.getvalue()

    def close(self):
        def _c():
            if self._sct:
                self._sct.close()
        self._ex.submit(_c)
        self._ex.shutdown(wait=False)
