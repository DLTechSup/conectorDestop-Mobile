@echo off
:: Execute como administrador. Libera a porta do DeskLink (TCP 8765 por padrao).
net session >nul 2>&1 || (echo Execute este arquivo como ADMINISTRADOR. & pause & exit /b 1)
set PORT=8765
if not "%1"=="" set PORT=%1
netsh advfirewall firewall delete rule name="DeskLink" >nul 2>&1
netsh advfirewall firewall add rule name="DeskLink" dir=in action=allow protocol=TCP localport=%PORT% profile=private,public
echo Porta %PORT% liberada.
pause
