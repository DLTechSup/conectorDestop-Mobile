@echo off
setlocal
cd /d "%~dp0"
title Gerando ConectorDesktop.exe

where python >nul 2>nul
if errorlevel 1 (
    echo [ERRO] Python nao encontrado. Instale o Python 3.10+ em https://www.python.org/downloads/
    echo        e marque a opcao "Add Python to PATH".
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
    --name ConectorDesktop ^
    --hidden-import pystray._win32 ^
    --collect-submodules websockets ^
    --collect-submodules pynput ^
    main.py || (echo [ERRO] Falha no PyInstaller. & pause & exit /b 1)

echo.
echo ============================================================
echo  Pronto! Executavel: %~dp0dist\ConectorDesktop.exe
echo ============================================================
echo Na primeira execucao, permita o acesso no Firewall do Windows
echo (redes privadas) ou execute liberar_firewall.bat como administrador.
pause
