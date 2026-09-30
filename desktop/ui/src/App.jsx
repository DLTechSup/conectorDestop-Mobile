import { useCallback, useEffect, useRef, useState } from "react";
import { QRCodeSVG } from "qrcode.react";
import {
  Activity, Bell, Check, Copy, Eye, EyeOff, Globe, Keyboard, Link2, Minus,
  Monitor, Power, RefreshCw, Rocket, Send, ShieldCheck, Smartphone, Trash2, Volume2, Wifi, X,
} from "lucide-react";
import { call, MOCK } from "./api.js";

function ago(since) {
  const s = Math.max(0, Math.floor(Date.now() / 1000) - since);
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)} min`;
  return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`;
}

function Toggle({ checked, onChange, disabled }) {
  return (
    <button
      role="switch" aria-checked={checked} disabled={disabled}
      className={"toggle" + (checked ? " on" : "")}
      onClick={() => onChange(!checked)}
    >
      <span />
    </button>
  );
}

function Card({ icon: Icon, title, action, children, className = "" }) {
  return (
    <section className={"card " + className}>
      <header className="card-h">
        <span className="card-ico"><Icon size={16} /></span>
        <h2>{title}</h2>
        <div className="grow" />
        {action}
      </header>
      {children}
    </section>
  );
}

function Row({ icon: Icon, title, desc, children }) {
  return (
    <div className="row">
      <span className="row-ico"><Icon size={18} /></span>
      <div className="row-t">
        <b>{title}</b>
        <small>{desc}</small>
      </div>
      {children}
    </div>
  );
}

function Modal({ title, children, onCancel, onOk, okText }) {
  return (
    <div className="modal-bg" onClick={onCancel}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>{title}</h3>
        <p>{children}</p>
        <div className="modal-a">
          <button className="btn ghost" onClick={onCancel}>Cancelar</button>
          <button className="btn danger" onClick={onOk}>{okText}</button>
        </div>
      </div>
    </div>
  );
}

export default function App() {
  const [s, setS] = useState(null);
  const [offline, setOffline] = useState(false);
  const [toast, setToast] = useState(null);
  const [showKey, setShowKey] = useState(false);
  const [copied, setCopied] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [ext, setExt] = useState("");
  const [port, setPort] = useState("");
  const [tab, setTab] = useState("tailscale");
  const edited = useRef({ ext: false, port: false });
  const toastT = useRef();

  const notify = useCallback((msg, kind = "ok") => {
    setToast({ msg, kind });
    clearTimeout(toastT.current);
    toastT.current = setTimeout(() => setToast(null), 3200);
  }, []);

  const refresh = useCallback(async () => {
    try {
      const st = await call("state");
      setS(st);
      setOffline(false);
      if (!edited.current.ext) setExt(st.external_host);
      if (!edited.current.port) setPort(String(st.port));
    } catch {
      setOffline(true);
    }
  }, []);

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, 1500);
    return () => clearInterval(id);
  }, [refresh]);

  const act = useCallback(async (name, args, okMsg) => {
    try {
      await call(name, args);
      if (okMsg) notify(okMsg);
      refresh();
    } catch (e) {
      notify(e.message, "err");
    }
  }, [notify, refresh]);

  if (!s) {
    return <div className="boot">{offline ? "Não foi possível falar com o DeskLink." : "Carregando…"}</div>;
  }

  const copyKey = async () => {
    try { await navigator.clipboard.writeText(s.key); } catch { /* sem permissão */ }
    setCopied(true);
    setTimeout(() => setCopied(false), 1600);
  };

  const detectIp = async () => {
    try {
      const r = await call("public_ip");
      edited.current.ext = true;
      setExt(r.ip);
      notify("IP público detectado — clique em Salvar");
    } catch {
      notify("Não foi possível descobrir o IP público", "err");
    }
  };

  const online = s.running && !offline;
  const maskedKey = s.key.replace(/[A-Z0-9]/g, "•");

  return (
    <div className="app">
      <header className="top">
        <img src="./logo.svg" alt="" className="logo" />
        <div>
          <h1>DeskLink</h1>
          <span className="sub">Seu PC no bolso · v{s.version}</span>
        </div>
        <div className="grow" />
        <span className={"pill " + (online ? "ok" : "err")}>
          <i /> {online ? `Servidor ativo · porta ${s.port}` : s.error || "Servidor parado"}
        </span>
        {!MOCK && (
          <button className="icon-btn" title="Minimizar para a bandeja" onClick={() => act("hide")}>
            <Minus size={16} />
          </button>
        )}
      </header>

      <main className="grid">
        <Card icon={Smartphone} title="Parear celular" className="pair">
          <div className="qr-wrap">
            <div className="qr">
              {s.pair_url ? (
                <QRCodeSVG value={s.pair_url} size={196} level="M" marginSize={0} fgColor="#0B1020" bgColor="#ffffff" />
              ) : <div className="qr-empty">Aguardando servidor…</div>}
            </div>
            <p className="hint">No app DeskLink, toque em <b>Ler QR code</b>.</p>
          </div>

          <label className="lbl">Chave de acesso</label>
          <div className="key">
            <code>{showKey ? s.key : maskedKey}</code>
            <button className="icon-btn" title={showKey ? "Ocultar" : "Mostrar"} onClick={() => setShowKey(!showKey)}>
              {showKey ? <EyeOff size={16} /> : <Eye size={16} />}
            </button>
            <button className="icon-btn" title="Copiar" onClick={copyKey}>
              {copied ? <Check size={16} /> : <Copy size={16} />}
            </button>
          </div>
          <button className="btn ghost small" onClick={() => setConfirm(true)}>
            <RefreshCw size={14} /> Gerar nova chave
          </button>

          <label className="lbl">Endereços deste PC</label>
          <div className="chips">
            {s.addresses.length === 0 && <span className="muted">Nenhuma rede detectada</span>}
            {s.addresses.map((a) => (
              <span className="chip" key={a.ip}>
                {a.label === "Tailscale" ? <Globe size={13} /> : <Wifi size={13} />}
                <small>{a.label}</small> {a.ip}
              </span>
            ))}
          </div>
        </Card>

        <div className="col">
          <Card
            icon={Link2} title="Dispositivos conectados"
            action={s.clients.length > 0 && (
              <button className="btn ghost small" onClick={() => act("disconnect_all", {}, "Dispositivos desconectados")}>
                Desconectar todos
              </button>
            )}
          >
            {s.clients.length === 0 ? (
              <div className="empty">
                <Smartphone size={28} />
                <b>Nenhum celular conectado</b>
                <small>Leia o QR code no app para começar.</small>
              </div>
            ) : (
              <ul className="devices">
                {s.clients.map((c) => (
                  <li key={c.ip + c.since}>
                    <span className="dev-ico"><Smartphone size={18} /></span>
                    <div><b>{c.name}</b><small>{c.ip}</small></div>
                    <div className="grow" />
                    <span className="live"><i /> há {ago(c.since)}</span>
                  </li>
                ))}
              </ul>
            )}
          </Card>

          <Card icon={Globe} title="Acesso pela internet">
            <div className="tabs">
              <button className={tab === "tailscale" ? "on" : ""} onClick={() => setTab("tailscale")}>Tailscale (fácil)</button>
              <button className={tab === "port" ? "on" : ""} onClick={() => setTab("port")}>Porta no roteador</button>
            </div>
            {tab === "tailscale" ? (
              <p className="help">
                Instale o <b>Tailscale</b> (grátis) no PC e no celular e entre na mesma conta. O IP
                <code>100.x.x.x</code> aparece acima e já vai no QR — sem mexer no roteador.
              </p>
            ) : (
              <div>
                <p className="help">Encaminhe a porta TCP <b>{s.port}</b> no roteador para este PC e informe seu IP público ou DDNS.</p>
                <div className="field">
                  <input
                    value={ext} placeholder="ex.: meupc.ddns.net ou 203.0.113.42"
                    onChange={(e) => { edited.current.ext = true; setExt(e.target.value); }}
                  />
                  <button className="btn ghost" onClick={detectIp}>Detectar IP</button>
                  <button className="btn" onClick={() => { edited.current.ext = false; act("save_external", { host: ext }, "Endereço externo salvo"); }}>
                    Salvar
                  </button>
                </div>
                <div className="field">
                  <span className="pre">Porta</span>
                  <input
                    className="short" value={port} inputMode="numeric"
                    onChange={(e) => { edited.current.port = true; setPort(e.target.value.replace(/\D/g, "")); }}
                  />
                  <button className="btn ghost" onClick={() => { edited.current.port = false; act("apply_port", { port }, "Porta alterada"); }}>
                    Aplicar
                  </button>
                </div>
              </div>
            )}
          </Card>

          <Card icon={ShieldCheck} title="Preferências">
            <Row icon={Keyboard} title="Permitir controle" desc="Mouse e teclado do PC pelo celular. Desligado = só visualizar.">
              <Toggle checked={s.allow_control} onChange={(v) => act("set_option", { key: "allow_control", value: v })} />
            </Row>
            <Row icon={Bell} title="Enviar notificações do PC" desc="Toda notificação do Windows toca no celular.">
              <Toggle checked={s.send_notifications} onChange={(v) => act("set_option", { key: "send_notifications", value: v })} />
            </Row>
            <Row icon={Volume2} title="Enviar áudio do PC" desc="O som que toca no PC (vídeos, músicas) sai no celular.">
              <Toggle checked={s.send_audio} onChange={(v) => act("set_option", { key: "send_audio", value: v })} />
            </Row>
            <Row icon={Rocket} title="Iniciar com o Windows" desc={s.autostart_supported ? "Abre minimizado na bandeja." : "Disponível no .exe instalado."}>
              <Toggle checked={s.autostart} disabled={!s.autostart_supported} onChange={(v) => act("set_autostart", { value: v })} />
            </Row>
            <div className="actions">
              <button className="btn ghost" onClick={() => act("test_notification", {}, "Notificação de teste enviada")}>
                <Send size={15} /> Enviar notificação de teste
              </button>
            </div>
          </Card>
        </div>
      </main>

      <Card icon={Activity} title="Atividade" className="log">
        <ul>
          {s.logs.length === 0 && <li className="muted">Sem atividade ainda.</li>}
          {[...s.logs].reverse().slice(0, 6).map((l, i) => (
            <li key={i}><time>{l.t}</time>{l.msg}</li>
          ))}
        </ul>
      </Card>

      <footer className="foot">
        <Monitor size={14} /> {s.hostname}
        <div className="grow" />
        {!MOCK && (
          <button className="btn quit" onClick={() => act("quit")}>
            <Power size={14} /> Encerrar DeskLink
          </button>
        )}
      </footer>

      {confirm && (
        <Modal
          title="Gerar nova chave?" okText="Gerar nova chave" onCancel={() => setConfirm(false)}
          onOk={() => { setConfirm(false); act("regenerate_key", {}, "Nova chave gerada"); }}
        >
          Todos os celulares pareados serão desconectados e precisarão ler o novo QR code.
        </Modal>
      )}

      {toast && (
        <div className={"toast " + toast.kind}>
          {toast.kind === "ok" ? <Check size={15} /> : <X size={15} />} {toast.msg}
        </div>
      )}
    </div>
  );
}
