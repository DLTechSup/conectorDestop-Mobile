"""Ponto de entrada: janela (pywebview + React), bandeja e servidor."""
import sys
import threading
import webbrowser

from . import APP_NAME
from .config import resource_dir
from .controller import Controller
from .uiserver import UIServer


def _tray_image():
    from PIL import Image
    for base in (resource_dir(), resource_dir().parent):
        ico = base / "assets" / "icon.ico"
        if ico.exists():
            return Image.open(ico)
    return Image.new("RGB", (64, 64), "#4F46E5")


def run(start_minimized=False):
    ctl = Controller()
    window = {"w": None}
    tray = {"i": None}

    def show():
        if window["w"]:
            try:
                window["w"].show()
            except Exception:
                pass
        else:
            webbrowser.open(ui.url)

    def hide():
        if window["w"]:
            window["w"].hide()

    def quit_app():
        try:
            if tray["i"]:
                tray["i"].stop()
        except Exception:
            pass
        ctl.shutdown()
        if window["w"]:
            window["w"].destroy()
        else:
            import os
            os._exit(0)

    ui = UIServer(ctl, on_action={"hide": hide, "quit": quit_app})

    try:
        import pystray
        menu = pystray.Menu(
            pystray.MenuItem("Abrir " + APP_NAME, lambda: show(), default=True),
            pystray.MenuItem("Sair", lambda: quit_app()))
        tray["i"] = pystray.Icon("desklink", _tray_image(), APP_NAME, menu)
        tray["i"].run_detached()
    except Exception as e:  # bandeja é opcional
        ctl.log(f"bandeja indisponível: {e}")

    try:
        import webview
    except Exception:
        webview = None

    if webview:
        try:
            w = webview.create_window(
                APP_NAME, ui.url, width=1040, height=720, min_size=(880, 620),
                background_color="#0B1020", hidden=start_minimized)
            window["w"] = w

            def on_closing():
                if tray["i"]:
                    w.hide()
                    return False  # cancela o fechamento: vai para a bandeja
                return True
            w.events.closing += on_closing
            webview.start()
            return
        except Exception as e:
            ctl.log(f"janela nativa indisponível ({e}); abrindo no navegador")
            window["w"] = None

    # Fallback: abre no navegador padrão e mantém o processo vivo.
    if not start_minimized:
        webbrowser.open(ui.url)
    threading.Event().wait()
