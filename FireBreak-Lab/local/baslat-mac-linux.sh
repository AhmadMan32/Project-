#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
PY=$(command -v python3 || command -v python || true)
if [ -z "$PY" ]; then
  echo "Python bulunamadı. Python 3.11 veya yenisini kurun: https://www.python.org/downloads/"
  exit 1
fi
if [ ! -d .venv ]; then
  echo "İlk kurulum yapılıyor, birkaç dakika sürebilir..."
  "$PY" -m venv .venv
  .venv/bin/python -m pip install --upgrade pip
  .venv/bin/python -m pip install -r requirements.txt
fi
exec .venv/bin/python run_local.py
