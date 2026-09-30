"""Configuração persistente, chave de acesso e certificado TLS autoassinado."""
import datetime
import hashlib
import json
import os
import secrets
import socket
import sys
from pathlib import Path

DEFAULTS = {
    "port": 8765,
    "key": "",
    "external_host": "",
    "allow_control": True,
    "send_notifications": True,
}


def data_dir() -> Path:
    base = os.environ.get("APPDATA") or os.path.join(Path.home(), ".config")
    p = Path(base) / "ConectorDesktop"
    p.mkdir(parents=True, exist_ok=True)
    return p


def new_key() -> str:
    # 20 caracteres sem ambiguidade (sem 0/O, 1/l/I)
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    raw = "".join(secrets.choice(alphabet) for _ in range(20))
    return "-".join(raw[i:i + 5] for i in range(0, 20, 5))


class Config:
    def __init__(self):
        self.path = data_dir() / "config.json"
        self.data = dict(DEFAULTS)
        if self.path.exists():
            try:
                self.data.update(json.loads(self.path.read_text("utf-8")))
            except Exception:
                pass
        if not self.data.get("key"):
            self.data["key"] = new_key()
            self.save()

    def __getitem__(self, k):
        return self.data[k]

    def __setitem__(self, k, v):
        self.data[k] = v
        self.save()

    def save(self):
        self.path.write_text(json.dumps(self.data, indent=2), "utf-8")

    def regenerate_key(self) -> str:
        self["key"] = new_key()
        return self["key"]


def ensure_certificate():
    """Retorna (cert_path, key_path, fingerprint_sha256_hex). Cria se não existir."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    d = data_dir()
    cert_p, key_p = d / "cert.pem", d / "key.pem"
    if not (cert_p.exists() and key_p.exists()):
        key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "ConectorDesktop")])
        now = datetime.datetime.now(datetime.timezone.utc)
        cert = (
            x509.CertificateBuilder()
            .subject_name(name).issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .sign(key, hashes.SHA256())
        )
        key_p.write_bytes(key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption()))
        cert_p.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    from cryptography import x509 as _x
    cert = _x.load_pem_x509_certificate(cert_p.read_bytes())
    der = cert.public_bytes(serialization.Encoding.DER)
    return str(cert_p), str(key_p), hashlib.sha256(der).hexdigest()


def local_addresses():
    """Lista (rótulo, ip) das interfaces IPv4 úteis: rede local e Tailscale."""
    import psutil
    out = []
    for iface, addrs in psutil.net_if_addrs().items():
        for a in addrs:
            if a.family != socket.AF_INET:
                continue
            ip = a.address
            if ip.startswith("127.") or ip.startswith("169.254."):
                continue
            parts = ip.split(".")
            tailscale = parts[0] == "100" and 64 <= int(parts[1]) <= 127
            out.append(("Tailscale" if tailscale else "Rede local", ip))
    out.sort(key=lambda t: (t[0] != "Rede local", t[1]))
    return out


def pair_url(cfg: Config, fingerprint: str) -> str:
    from urllib.parse import quote
    hosts = [ip for _, ip in local_addresses()]
    if cfg["external_host"]:
        hosts.append(cfg["external_host"])
    return "conector://pair?h={}&p={}&k={}&f={}&n={}".format(
        quote(",".join(hosts), safe=","), cfg["port"], cfg["key"], fingerprint,
        quote(socket.gethostname()))


def resource_dir() -> Path:
    return Path(getattr(sys, "_MEIPASS", Path(__file__).parent))
