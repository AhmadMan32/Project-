"""FireBreak Lab on your own computer: the real API (FastAPI + NumPy) and the web app in one process.

    python run_local.py            -> http://127.0.0.1:8000 opens in your browser
"""
import os
import sys
import threading
import webbrowser
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = HERE / "data"
DATA.mkdir(exist_ok=True)
os.environ.setdefault("FIREBREAK_DB", str(DATA / "firebreak.sqlite"))
os.environ.setdefault("FIREBREAK_CACHE", str(DATA / "cache"))
# In the repository the API lives in ../artifacts/api-server; in the download package it sits next to this file in api/.
for candidate in (HERE / "api", HERE.parent / "artifacts" / "api-server"):
    if (candidate / "firebreak").is_dir():
        sys.path.insert(0, str(candidate))
        break

import uvicorn  # noqa: E402
from fastapi import HTTPException  # noqa: E402
from fastapi.responses import FileResponse  # noqa: E402
from firebreak.app import app  # noqa: E402

WEB = HERE / "web"


@app.get("/{path:path}", include_in_schema=False)
def web(path: str):
    # Built files are served as they are; app routes such as /experiments fall back to index.html.
    if path.startswith("api/"):
        raise HTTPException(404, "Not found")
    target = (WEB / path).resolve()
    if path and target.is_file() and WEB in target.parents:
        return FileResponse(target)
    return FileResponse(WEB / "index.html")


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8000"))
    url = f"http://127.0.0.1:{port}"
    print(f"\n  FireBreak Lab çalışıyor: {url}\n  Kapatmak için bu pencerede Ctrl+C\n")
    threading.Timer(1.5, lambda: webbrowser.open(url)).start()
    uvicorn.run(app, host="127.0.0.1", port=port, log_level="warning")
