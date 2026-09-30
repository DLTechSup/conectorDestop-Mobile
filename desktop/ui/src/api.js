// Cliente da API local do DeskLink (token vem na URL aberta pelo programa).
const params = new URLSearchParams(location.search);
if (params.get("t")) sessionStorage.setItem("dl_token", params.get("t"));
const token = sessionStorage.getItem("dl_token") || "";

export const MOCK = !token || params.get("mock") === "1";

const mockState = {
  name: "DeskLink", version: "0.2.0", hostname: "DESKTOP-DL01",
  running: true, error: "", port: 8765, key: "K7QM2-XW9RT-4HBN8-PC3VD",
  external_host: "", allow_control: true, send_notifications: true,
  autostart: false, autostart_supported: true,
  addresses: [{ label: "Rede local", ip: "192.168.0.14" }, { label: "Tailscale", ip: "100.88.14.20" }],
  pair_url: "conector://pair?h=192.168.0.14,100.88.14.20&p=8765&k=K7QM2-XW9RT-4HBN8-PC3VD&f=00&n=DESKTOP-DL01",
  clients: params.get("empty") ? [] : [{ name: "Pixel 8", ip: "192.168.0.31", since: Math.floor(Date.now() / 1000) - 340 }],
  logs: [
    { t: "14:02:11", msg: "Servidor ouvindo na porta 8765" },
    { t: "14:05:40", msg: "Conectado: Pixel 8 (192.168.0.31)" },
  ],
};

export async function call(name, args = {}) {
  if (MOCK) {
    if (name === "state") return mockState;
    if (name === "set_option") mockState[args.key] = args.value;
    if (name === "set_autostart") mockState.autostart = args.value;
    if (name === "save_external") mockState.external_host = args.host;
    if (name === "public_ip") return { ip: "203.0.113.42" };
    return { ok: true };
  }
  const r = await fetch(`./api/${name}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Token": token },
    body: JSON.stringify(args),
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || `Erro ${r.status}`);
  return data;
}
