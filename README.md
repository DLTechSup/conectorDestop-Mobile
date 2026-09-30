# Conector Desktop ⇄ Mobile

Veja e controle a tela do seu PC (Windows) pelo celular (Android) e receba no celular,
com som alto/personalizado, **todas as notificações do PC**. Funciona por Wi-Fi na rede
local e pela internet, **sem servidor/VPS**.

Diferenciais:

- **A sessão não cai ao sair do app.** O APK roda um serviço em primeiro plano; a conexão só
  termina em **"Sair e desconectar"** (dentro do app ou na notificação fixa).
- **Notificações do PC no celular** com som escolhido por você (toque do aparelho ou mp3),
  modo "alto" (toca como alarme, mesmo no silencioso) e lista de apps ignorados.

```
desktop/   Programa do PC (Python) — gera ConectorDesktop.exe com build.bat
android/   App do celular (Kotlin) — gera o APK
```

## PC (Windows)

1. Instale o [Python 3.10+](https://www.python.org/downloads/) (marque *Add Python to PATH*).
2. Execute `desktop\build.bat` → gera `desktop\dist\ConectorDesktop.exe`.
   (Para testar sem gerar o exe: `desktop\run_dev.bat`.)
3. Abra o `ConectorDesktop.exe`. Permita o acesso no Firewall (redes privadas) ou rode
   `liberar_firewall.bat` como administrador. Fechar a janela minimiza para a bandeja.
4. A janela mostra um **QR code**, a **chave de acesso** e os endereços do PC.

## Celular (Android)

Sem Android Studio: envie o repositório ao GitHub e baixe o APK em
*Actions → Build APK → ConectorMobile-debug-apk* (ou rode o workflow manualmente).
Com Android Studio: abra a pasta `android/`, *Build → Build APK(s)*.

1. Instale o APK (permita "fontes desconhecidas") e aceite as permissões de notificação.
2. **Ler QR code do PC** (ou digite endereço, porta e chave) → conecta e abre a tela.
3. Toque = clique · toque duplo = duplo clique · segurar = botão direito · arrastar = mover o
   cursor · dois dedos = rolar · *Arrastar: ON* = segurar o botão esquerdo (selecionar/mover
   janelas) · *Teclado* / *Teclas* = digitar e atalhos (Ctrl+C, Alt+Tab, Win…).
4. Na tela inicial do app, configure o **som das notificações** e desative a
   **otimização de bateria** (evita que Xiaomi/Samsung/Huawei matem a conexão).

## Acesso de fora de casa (sem servidor)

- **Tailscale (recomendado, grátis, sem mexer no roteador):** instale nos dois aparelhos e
  entre na mesma conta. O IP `100.x.x.x` do PC aparece na janela e no QR automaticamente.
- **Encaminhamento de porta:** no roteador, encaminhe a porta TCP (padrão 8765) para o PC e
  informe seu IP público/DDNS em *Endereço externo* (há botão "Descobrir meu IP público").
  Ao ler o QR, o app tenta todos os endereços (local primeiro, externo depois).

## Segurança

- Tráfego criptografado (TLS). O celular **fixa o certificado do PC** (SHA-256 vem no QR);
  se ele mudar, a conexão é recusada.
- Acesso só com a **chave** (20 caracteres aleatórios); 5 tentativas erradas bloqueiam o IP
  por 1 minuto. *Gerar nova* invalida todos os celulares pareados.
- Se digitar o endereço manualmente (sem QR), o certificado é confiado no 1º uso —
  prefira o QR, principalmente ao expor a porta na internet.
- Dá para desligar o controle de mouse/teclado (só visualizar) na janela do PC.

## Como funciona (resumo técnico)

WebSocket seguro (`wss`) PC↔celular. O PC captura a tela (mss → JPEG, só envia quando muda,
com o cursor desenhado) e injeta mouse/teclado (pynput). As notificações são lidas do banco
`wpndatabase.db` do Windows (toasts) e enviadas ao celular, que toca o som e mostra a
notificação. No Android, `RemoteService` (foreground service + WakeLock/WifiLock) mantém a
conexão com reconexão automática; a `ViewerActivity` só pede vídeo enquanto está visível.

## Limitações conhecidas

- Não há tela de bloqueio/UAC (janelas elevadas) nem Ctrl+Alt+Del — limitação do Windows.
- Notificações: dependem do banco interno do Windows; apps com notificações desativadas no
  Windows não aparecem. Sem áudio do PC e sem transferência de arquivos (ainda).
- Sem zoom na tela remota (use o celular na horizontal).
