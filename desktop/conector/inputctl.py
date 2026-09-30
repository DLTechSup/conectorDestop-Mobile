"""Injeta mouse e teclado no Windows via pynput."""
from .capture import monitor_rect

_KEYS = None


def _keymap():
    global _KEYS
    if _KEYS is None:
        from pynput.keyboard import Key
        _KEYS = {
            "enter": Key.enter, "backspace": Key.backspace, "tab": Key.tab,
            "esc": Key.esc, "space": Key.space, "delete": Key.delete,
            "home": Key.home, "end": Key.end, "pageup": Key.page_up,
            "pagedown": Key.page_down, "up": Key.up, "down": Key.down,
            "left": Key.left, "right": Key.right, "win": Key.cmd,
            "ctrl": Key.ctrl, "alt": Key.alt, "shift": Key.shift,
            "insert": Key.insert, "printscreen": Key.print_screen,
            **{f"f{i}": getattr(Key, f"f{i}") for i in range(1, 13)},
        }
    return _KEYS


class InputController:
    def __init__(self):
        from pynput.keyboard import Controller as K
        from pynput.mouse import Controller as M
        self.mouse = M()
        self.kb = K()
        self._rects = {}

    def _rect(self, monitor):
        import time
        hit = self._rects.get(monitor)
        if not hit or time.time() - hit[0] > 5:
            hit = (time.time(), monitor_rect(monitor))
            self._rects[monitor] = hit
        return hit[1]

    def _pos(self, monitor, x, y):
        left, top, w, h = self._rect(monitor)
        x = min(max(float(x), 0.0), 1.0)
        y = min(max(float(y), 0.0), 1.0)
        return int(left + x * (w - 1)), int(top + y * (h - 1))

    def handle_mouse(self, msg):
        from pynput.mouse import Button
        a = msg.get("a")
        btn = Button.right if msg.get("b") == "right" else Button.left
        if a == "scroll":
            self.mouse.scroll(0, int(msg.get("dy", 0)))
            return
        if "x" in msg:
            self.mouse.position = self._pos(msg.get("m", 1), msg["x"], msg["y"])
        if a == "down":
            self.mouse.press(btn)
        elif a == "up":
            self.mouse.release(btn)
        elif a == "click":
            self.mouse.click(btn, 1)
        elif a == "dclick":
            self.mouse.click(Button.left, 2)
        elif a == "rclick":
            self.mouse.click(Button.right, 1)

    def handle_key(self, msg):
        keys = _keymap()
        name = str(msg.get("k", ""))
        key = keys.get(name.lower()) or (name if len(name) == 1 else None)
        if key is None:
            return
        mods = [keys[m] for m in msg.get("mods", []) if m in keys]
        for m in mods:
            self.kb.press(m)
        try:
            self.kb.press(key)
            self.kb.release(key)
        finally:
            for m in reversed(mods):
                self.kb.release(m)

    def handle_text(self, msg):
        s = str(msg.get("s", ""))[:2000]
        if s:
            self.kb.type(s)
