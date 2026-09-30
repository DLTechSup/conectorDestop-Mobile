import ctypes
import sys


def main():
    if sys.platform == "win32":
        try:  # coordenadas físicas: captura e mouse precisam usar a mesma escala
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except Exception:
            ctypes.windll.user32.SetProcessDPIAware()
    from conector.gui import App
    App(start_minimized="--minimized" in sys.argv).run()


if __name__ == "__main__":
    main()
