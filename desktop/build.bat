@echo off
setlocal
cd /d "%~dp0"
title Gerando DeskLink.exe

where python >nul 2>nul
if errorlevel 1 (
    echo [ERRO] Python nao encontrado. Instale o Python 3.10+ em https://www.python.org/downloads/
    echo        e marque a opcao "Add Python to PATH".
    pause & exit /b 1
)

:: Interface React: recompila se o Node estiver instalado; senao usa ui\dist ja incluido.
where npm >nul 2>nul
if not errorlevel 1 (
    echo Compilando interface React...
    pushd ui
    call npm install --no-audit --no-fund || (popd & echo [ERRO] npm install falhou. & pause & exit /b 1)
    call npm run build || (popd & echo [ERRO] npm run build falhou. & pause & exit /b 1)
    popd
) else (
    echo Node.js nao encontrado - usando a interface ja compilada em ui\dist.
)
if not exist ui\dist\index.html (
    echo [ERRO] ui\dist nao existe. Instale o Node.js LTS em https://nodejs.org e rode de novo.
    pause & exit /b 1
)

if not exist .venv (
    echo Criando ambiente virtual...
    python -m venv .venv || (pause & exit /b 1)
)
call .venv\Scripts\activate.bat

echo Instalando dependencias...
python -m pip install --upgrade pip >nul
pip install -r requirements.txt || (echo [ERRO] Falha ao instalar dependencias. & pause & exit /b 1)

echo Gerando executavel...
pyinstaller --noconfirm --clean --onefile --noconsole ^
    --name DeskLink ^
    --icon ..\assets\icon.ico ^
    --add-data "ui\dist;ui\dist" ^
    --add-data "..\assets\icon.ico;assets" ^
    --hidden-import pystray._win32 ^
    --collect-submodules websockets ^
    --collect-submodules pynput ^
    --collect-all webview ^
    --collect-all soundcard ^
    main.py || (echo [ERRO] Falha no PyInstaller. & pause & exit /b 1)

echo.
echo ============================================================
echo  Pronto! Executavel: %~dp0dist\DeskLink.exe
echo ============================================================
echo Na primeira execucao, permita o acesso no Firewall do Windows
echo (redes privadas) ou execute liberar_firewall.bat como administrador.
pause
