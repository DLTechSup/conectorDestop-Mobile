"""Captura de tela (mss) -> JPEG, com posição do cursor e descarte de quadros idênticos."""
import ctypes
import io
import sys
import zlib
from concurrent.futures import ThreadPoolExecutor

import mss
from PIL import Image


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

    async def frame(self, loop, monitor, maxw, quality, view=(0.0, 0.0, 1.0, 1.0)):
        """Retorna (jpeg, view_usada, cursor_normalizado) ou None se nada mudou.

        `view` = região (x, y, w, h) normalizada 0..1 da tela — é o zoom: o recorte
        é feito na resolução nativa, então a imagem ampliada continua nítida.
        """
        return await loop.run_in_executor(self._ex, self._grab, monitor, maxw, quality, view)

    def _grab(self, monitor, maxw, quality, view):
        if self._sct is None:
            self._sct = mss.mss()
        mons = self._sct.monitors
        m = mons[monitor] if 0 < monitor < len(mons) else mons[1]
        shot = self._sct.grab(m)
        img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
        vx, vy, vw, vh = view
        vw = min(max(vw, 0.05), 1.0)
        vh = min(max(vh, 0.05), 1.0)
        vx = min(max(vx, 0.0), 1.0 - vw)
        vy = min(max(vy, 0.0), 1.0 - vh)
        W, H = img.size
        box = (int(vx * W), int(vy * H), max(int(vx * W) + 1, int((vx + vw) * W)),
               max(int(vy * H) + 1, int((vy + vh) * H)))
        if box != (0, 0, W, H):
            img = img.crop(box)
        used = (box[0] / W, box[1] / H, (box[2] - box[0]) / W, (box[3] - box[1]) / H)
        scale = 1.0
        if img.width > maxw:
            scale = maxw / img.width
            img = img.resize((maxw, max(1, int(img.height * scale))), Image.BILINEAR)
        # O cursor NÃO é desenhado na imagem: o celular o desenha (sem atraso) usando esta posição
        # normalizada (0..1 sobre a tela inteira do monitor).
        cur = cursor_pos()
        cn = (-1.0, -1.0)
        if cur:
            cn = ((cur[0] - m["left"]) / m["width"], (cur[1] - m["top"]) / m["height"])
        digest = zlib.crc32(img.tobytes()) ^ hash((round(cn[0], 4), round(cn[1], 4), used))
        if digest == self._last:
            return None
        self._last = digest
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=quality)
        return buf.getvalue(), used, cn

    def close(self):
        def _c():
            if self._sct:
                self._sct.close()
        self._ex.submit(_c)
        self._ex.shutdown(wait=False)
