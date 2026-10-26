@echo off
chcp 65001 >nul
cd /d "%~dp0"
where python >nul 2>nul
if errorlevel 1 (
  echo Python bulunamadi. https://www.python.org/downloads/ adresinden Python 3.11 veya yenisini kurun.
  echo Kurulumda "Add python.exe to PATH" kutusunu isaretleyin, sonra bu dosyayi tekrar calistirin.
  pause
  exit /b 1
)
if not exist .venv (
  echo Ilk kurulum yapiliyor, birkac dakika surebilir...
  python -m venv .venv || goto :fail
  .venv\Scripts\python -m pip install --upgrade pip || goto :fail
  .venv\Scripts\python -m pip install -r requirements.txt || goto :fail
)
.venv\Scripts\python run_local.py
pause
exit /b 0
:fail
echo Kurulum basarisiz oldu. Internet baglantisini kontrol edip tekrar deneyin.
pause
exit /b 1
