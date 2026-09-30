"""Janela principal (tkinter) + ícone na bandeja."""
import threading
import tkinter as tk
import urllib.request
from tkinter import messagebox, ttk

from . import APP_NAME, VERSION
from .config import Config, local_addresses, pair_url
from .notifications import NotificationWatcher
from .server import RemoteServer

try:
    import pystray
    from PIL import Image, ImageDraw
except Exception:  # bandeja é opcional
    pystray = None


def _autostart_cmd():
    import sys
    return f'"{sys.executable}"' if getattr(sys, "frozen", False) else None


def set_autostart(enabled: bool) -> bool:
    import sys
    if sys.platform != "win32":
        return False
    import winreg
    cmd = _autostart_cmd()
    if enabled and not cmd:
        return False  # só faz sentido no .exe
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                        r"Software\Microsoft\Windows\CurrentVersion\Run", 0,
                        winreg.KEY_SET_VALUE) as k:
        if enabled:
            winreg.SetValueEx(k, "ConectorDesktop", 0, winreg.REG_SZ, cmd + " --minimized")
        else:
            try:
                winreg.DeleteValue(k, "ConectorDesktop")
            except FileNotFoundError:
                pass
    return True


class App:
    def __init__(self, start_minimized=False):
        self.cfg = Config()
        self.logs = []
        self.server = RemoteServer(self.cfg, log=self.log)
        self.server.start()
        self.watcher = NotificationWatcher(
            self.server.broadcast, enabled=lambda: self.cfg["send_notifications"], log=self.log)
        self.watcher.start()

        self.root = tk.Tk()
        self.root.title(f"{APP_NAME} {VERSION}")
        self.root.geometry("560x760")
        self.root.protocol("WM_DELETE_WINDOW", self.hide)
        self.tray = None
        self._qr_img = None
        self._build()
        self._refresh()
        if pystray:
            self._start_tray()
        if start_minimized and self.tray:
            self.root.withdraw()

    # ------------------------------------------------------------------ UI
    def _build(self):
        pad = {"padx": 12, "pady": 4}
        r = self.root
        self.status = ttk.Label(r, font=("Segoe UI", 11, "bold"))
        self.status.pack(anchor="w", **pad)
        self.clients = ttk.Label(r)
        self.clients.pack(anchor="w", **pad)

        f = ttk.LabelFrame(r, text="Pareamento com o celular")
        f.pack(fill="x", **pad)
        self.qr_label = ttk.Label(f)
        self.qr_label.pack(pady=6)
        self.addrs = ttk.Label(f, justify="left")
        self.addrs.pack(anchor="w", padx=8)
        kr = ttk.Frame(f)
        kr.pack(fill="x", padx=8, pady=6)
        ttk.Label(kr, text="Chave:").pack(side="left")
        self.key_var = tk.StringVar(value=self.cfg["key"])
        ttk.Entry(kr, textvariable=self.key_var, state="readonly",
                  font=("Consolas", 11), width=26).pack(side="left", padx=6)
        ttk.Button(kr, text="Copiar", command=self._copy_key).pack(side="left")
        ttk.Button(kr, text="Gerar nova", command=self._new_key).pack(side="left", padx=4)

        f2 = ttk.LabelFrame(r, text="Acesso pela internet (fora de casa)")
        f2.pack(fill="x", **pad)
        row = ttk.Frame(f2); row.pack(fill="x", padx=8, pady=4)
        ttk.Label(row, text="Endereço externo:").pack(side="left")
        self.ext_var = tk.StringVar(value=self.cfg["external_host"])
        ttk.Entry(row, textvariable=self.ext_var, width=26).pack(side="left", padx=6)
        ttk.Button(row, text="Salvar", command=self._save_ext).pack(side="left")
        row = ttk.Frame(f2); row.pack(fill="x", padx=8, pady=4)
        ttk.Label(row, text="Porta:").pack(side="left")
        self.port_var = tk.StringVar(value=str(self.cfg["port"]))
        ttk.Entry(row, textvariable=self.port_var, width=8).pack(side="left", padx=6)
        ttk.Button(row, text="Aplicar porta", command=self._apply_port).pack(side="left")
        ttk.Button(row, text="Descobrir meu IP público", command=self._public_ip).pack(side="left", padx=6)
        ttk.Label(f2, wraplength=500, foreground="#555", text=(
            "Sem servidor: instale o Tailscale (grátis) no PC e no celular — o IP 100.x aparece "
            "acima automaticamente. Ou libere a porta no roteador (encaminhamento) e informe "
            "aqui seu IP público / DDNS.")).pack(anchor="w", padx=8, pady=(0, 6))

        f3 = ttk.LabelFrame(r, text="Opções")
        f3.pack(fill="x", **pad)
        self.ctl_var = tk.BooleanVar(value=self.cfg["allow_control"])
        self.not_var = tk.BooleanVar(value=self.cfg["send_notifications"])
        self.auto_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(f3, text="Permitir controlar mouse e teclado", variable=self.ctl_var,
                        command=lambda: self._set("allow_control", self.ctl_var.get())).pack(anchor="w", padx=8)
        ttk.Checkbutton(f3, text="Enviar notificações do PC para o celular", variable=self.not_var,
                        command=lambda: self._set("send_notifications", self.not_var.get())).pack(anchor="w", padx=8)
        ttk.Checkbutton(f3, text="Iniciar com o Windows (minimizado)", variable=self.auto_var,
                        command=self._toggle_auto).pack(anchor="w", padx=8)

        b = ttk.Frame(r); b.pack(fill="x", **pad)
        ttk.Button(b, text="Enviar notificação de teste", command=self._test_notif).pack(side="left")
        ttk.Button(b, text="Desconectar celulares", command=self.server.disconnect_all).pack(side="left", padx=6)
        ttk.Button(b, text="Sair do programa", command=self.quit).pack(side="right")

        self.log_box = tk.Text(r, height=7, state="disabled", font=("Consolas", 9))
        self.log_box.pack(fill="both", expand=True, **pad)

    # ------------------------------------------------------------ actions
    def log(self, text):
        self.logs.append(text)

    def _set(self, k, v):
        self.cfg[k] = bool(v)

    def _copy_key(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(self.cfg["key"])

    def _new_key(self):
        if messagebox.askyesno(APP_NAME, "Gerar nova chave desconecta e invalida os celulares já pareados. Continuar?"):
            self.key_var.set(self.cfg.regenerate_key())
            self.server.disconnect_all()
            self._draw_qr()

    def _save_ext(self):
        self.cfg["external_host"] = self.ext_var.get().strip()
        self._draw_qr()

    def _apply_port(self):
        try:
            port = int(self.port_var.get())
            assert 1024 <= port <= 65535
        except Exception:
            messagebox.showerror(APP_NAME, "Porta inválida (use 1024–65535).")
            return
        self.cfg["port"] = port
        self.server.stop()
        self.server = RemoteServer(self.cfg, log=self.log)
        self.server.start()
        self.watcher.on_notification = self.server.broadcast
        self._draw_qr()

    def _public_ip(self):
        def work():
            try:
                ip = urllib.request.urlopen("https://api.ipify.org", timeout=6).read().decode()
                self.root.after(0, lambda: self.ext_var.set(ip))
            except Exception as e:
                self.log(f"Não foi possível obter o IP público: {e}")
        threading.Thread(target=work, daemon=True).start()

    def _toggle_auto(self):
        try:
            if not set_autostart(self.auto_var.get()):
                messagebox.showinfo(APP_NAME, "Disponível apenas no .exe gerado pelo build.bat (Windows).")
                self.auto_var.set(False)
        except Exception as e:
            messagebox.showerror(APP_NAME, str(e))
            self.auto_var.set(False)

    def _test_notif(self):
        self.server.broadcast({"t": "notif", "id": 0, "app": "Conector Desktop",
                               "title": "Notificação de teste", "body": "Se você ouviu o som, está funcionando!"})

    # -------------------------------------------------------------- refresh
    def _draw_qr(self):
        import qrcode
        from PIL import ImageTk
        img = qrcode.make(pair_url(self.cfg, self.server.fingerprint)).resize((220, 220))
        self._qr_img = ImageTk.PhotoImage(img)
        self.qr_label.configure(image=self._qr_img)
        addrs = local_addresses()
        lines = [f"{lbl}: {ip}:{self.cfg['port']}" for lbl, ip in addrs]
        if self.cfg["external_host"]:
            lines.append(f"Externo: {self.cfg['external_host']}:{self.cfg['port']}")
        self.addrs.configure(text="\n".join(lines) or "Nenhuma rede detectada")

    def _refresh(self):
        s = self.server
        if s.running:
            self.status.configure(text=f"● Servidor ativo (porta {self.cfg['port']})", foreground="#0a7d2c")
        else:
            self.status.configure(text=f"● Servidor parado: {s.error}", foreground="#b00020")
        names = ", ".join(f"{c.name} ({c.ip})" for c in s.clients)
        self.clients.configure(text=f"Celulares conectados: {names or 'nenhum'}")
        if self._qr_img is None and s.fingerprint:
            self._draw_qr()
        if self.logs:
            self.log_box.configure(state="normal")
            for line in self.logs:
                self.log_box.insert("end", line + "\n")
            self.logs.clear()
            self.log_box.see("end")
            self.log_box.configure(state="disabled")
        self.root.after(1000, self._refresh)

    # ----------------------------------------------------------------- tray
    def _start_tray(self):
        img = Image.new("RGB", (64, 64), "#1565c0")
        d = ImageDraw.Draw(img)
        d.rectangle((10, 14, 54, 42), outline="white", width=4)
        d.rectangle((26, 44, 38, 50), fill="white")
        d.rectangle((18, 50, 46, 54), fill="white")
        menu = pystray.Menu(
            pystray.MenuItem("Abrir", lambda: self.root.after(0, self.show), default=True),
            pystray.MenuItem("Sair", lambda: self.root.after(0, self.quit)))
        self.tray = pystray.Icon("conector", img, APP_NAME, menu)
        threading.Thread(target=self.tray.run, daemon=True).start()

    def show(self):
        self.root.deiconify()
        self.root.lift()

    def hide(self):
        if self.tray:
            self.root.withdraw()
        else:
            self.quit()

    def quit(self):
        self.watcher.stop()
        self.server.stop()
        if self.tray:
            self.tray.stop()
        self.root.destroy()

    def run(self):
        self.root.mainloop()
