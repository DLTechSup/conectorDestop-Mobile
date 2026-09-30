import ctypes
import sys


def main():
    if sys.platform == "win32":
        try:  # coordenadas físicas: captura e mouse precisam usar a mesma escala
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except Exception:
            ctypes.windll.user32.SetProcessDPIAware()
    from conector.app import run
    run(start_minimized="--minimized" in sys.argv)


if __name__ == "__main__":
    main()
