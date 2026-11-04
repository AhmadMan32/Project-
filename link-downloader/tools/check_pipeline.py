#!/usr/bin/env python3
"""End-to-end check of the app's yt-dlp arguments against local YouTube-like streams.

Usage: python3 check.py <yt-dlp> <args dir from CoreTest>
"""
import json
import os
import shutil
import subprocess
import sys
import threading
import functools
import http.server

YTDLP, ARGS = sys.argv[1], sys.argv[2]
HERE = os.path.dirname(os.path.abspath(__file__))
MEDIA = os.path.join(HERE, "media")
PORT = 8765
BASE = f"http://127.0.0.1:{PORT}/"


def serve():
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=MEDIA)
    handler.log_message = lambda *a, **k: None
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


def fmt(fid, file, ext, vcodec, acodec, h=None, w=None, tbr=None, abr=None, fps=None):
    d = {"format_id": fid, "url": BASE + file, "ext": ext, "vcodec": vcodec, "acodec": acodec, "protocol": "http"}
    if h:
        d.update(height=h, width=w, fps=fps or 30)
    if tbr:
        d["tbr"] = tbr
    if abr:
        d["abr"] = abr
    d["filesize"] = os.path.getsize(os.path.join(MEDIA, file))
    return d


ALL_FORMATS = [
    fmt("18", "muxed360.mp4", "mp4", "avc1.42001E", "mp4a.40.2", 360, 640, tbr=500),
    fmt("136", "v720_h264.mp4", "mp4", "avc1.4d401f", "none", 720, 1280, tbr=1500),
    fmt("137", "v1080_h264.mp4", "mp4", "avc1.640028", "none", 1080, 1920, tbr=4000),
    fmt("248", "v1080_vp9.webm", "webm", "vp9", "none", 1080, 1920, tbr=2500),
    fmt("313", "v2160_vp9.webm", "webm", "vp9", "none", 2160, 3840, tbr=9000),
    fmt("140", "a_aac.m4a", "m4a", "none", "mp4a.40.2", tbr=128, abr=128),
    fmt("251", "a_opus.webm", "webm", "none", "opus", tbr=160, abr=160),
]


def info(formats, title="Test Şarkı: Ğüş/İ? \"klip\"", vid="test123"):
    return {
        "id": vid, "title": title, "fulltitle": title, "uploader": "Test Kanal", "artist": None,
        "extractor": "youtube", "extractor_key": "Youtube", "webpage_url": "https://www.youtube.com/watch?v=" + vid,
        "original_url": "https://www.youtube.com/watch?v=" + vid,
        "thumbnail": BASE + "thumb.jpg", "thumbnails": [{"url": BASE + "thumb.jpg", "id": "0", "width": 1280, "height": 720}],
        "duration": 4, "formats": formats, "_type": "video", "epoch": 1,
    }


def args(name, out):
    with open(os.path.join(ARGS, name), encoding="utf-8") as f:
        return [a.replace("OUT/", out + "/") for a in f.read().split("\n") if a]


def run(case, argfile, formats, expect):
    out = os.path.join(HERE, "out", case)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    path = os.path.join(out, "info.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(info(formats), f)
    a = args(argfile, out)
    if expect.get("height_override"):
        i = a.index("-S")
        a[i + 1] = a[i + 1].replace("res:1080", "res:%d" % expect["height_override"])
    cmd = [YTDLP, "--no-warnings", "--load-info-json", path] + a
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    marker = [l for l in p.stdout.splitlines() if l.startswith("LINKDL_FILE:")]
    ok = p.returncode == 0 and len(marker) == 1
    final = marker[0][len("LINKDL_FILE:"):] if marker else None
    result = {"case": case, "exit": p.returncode, "file": os.path.basename(final) if final else None}
    if not ok:
        result["stderr"] = p.stderr[-800:]
        print(json.dumps(result, ensure_ascii=False, indent=1))
        return False
    probe = json.loads(subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", final],
                                      capture_output=True, text=True).stdout)
    streams = probe["streams"]
    v = [s for s in streams if s["codec_type"] == "video" and not s.get("disposition", {}).get("attached_pic")]
    covers = [s for s in streams if s.get("disposition", {}).get("attached_pic")]
    a_ = [s for s in streams if s["codec_type"] == "audio"]
    tags = {k.lower(): v_ for k, v_ in probe["format"].get("tags", {}).items()}
    result.update({
        "container": probe["format"]["format_name"],
        "video": [f'{s["codec_name"]} {s["width"]}x{s["height"]}' for s in v],
        "audio": [f'{s["codec_name"]} {int(s.get("bit_rate", 0) or probe["format"].get("bit_rate", 0)) // 1000}k' for s in a_],
        "cover": [f'{s["codec_name"]} {s["width"]}x{s["height"]}' for s in covers],
        "title_tag": tags.get("title"), "artist_tag": tags.get("artist"),
        "progress_lines": sum(1 for l in p.stdout.splitlines() + p.stderr.splitlines() if l.startswith("[download]") and "%" in l),
        "leftovers": sorted(f for f in os.listdir(out) if f != "info.json" and os.path.join(out, f) != final),
    })
    checks = []
    for key, want in expect.items():
        if key == "height_override":
            continue
        got = result.get(key)
        good = want(got) if callable(want) else got == want
        checks.append((key, good))
        ok = ok and good
    result["checks"] = {k: ("ok" if g else "FAIL") for k, g in checks}
    print(json.dumps(result, ensure_ascii=False, indent=1))
    return ok


httpd = serve()
results = [
    run("video-1080", "args-video.txt", ALL_FORMATS, {
        "container": lambda c: "mp4" in c, "video": ["h264 1920x1080"], "audio": lambda a: len(a) == 1 and a[0].startswith("aac"),
        "file": lambda f: f.endswith(".mp4") and "/" not in f and "?" not in f and ":" not in f, "leftovers": [],
        "progress_lines": lambda n: n > 0}),
    # resolution beats codec: only VP9 has 1080p here -> 1080p VP9, not 720p H.264
    run("video-1080-no-h264", "args-video.txt", [f for f in ALL_FORMATS if f["format_id"] != "137"], {
        "video": ["vp9 1920x1080"], "container": lambda c: "mp4" in c, "audio": lambda a: len(a) == 1}),
    run("video-4k", "args-video.txt", ALL_FORMATS, {"height_override": 2160, "video": ["vp9 3840x2160"]}),
    run("video-720-cap", "args-video.txt", ALL_FORMATS, {"height_override": 720, "video": ["h264 1280x720"]}),
    run("audio-mp3", "args-audio.txt", ALL_FORMATS, {
        "container": "mp3", "audio": lambda a: a and a[0].startswith("mp3") and 300 <= int(a[0].split()[1][:-1]) <= 330,
        "cover": lambda c: len(c) == 1 and c[0].startswith("mjpeg"),
        "title_tag": lambda t: t is not None and "Ğüş" in t, "artist_tag": "Test Kanal",
        "file": lambda f: f.endswith(".mp3"), "leftovers": []}),
    run("audio-m4a", "args-m4a.txt", ALL_FORMATS, {
        "audio": lambda a: a and a[0].startswith("aac"), "file": lambda f: f.endswith(".m4a"), "leftovers": [],
        "cover": lambda c: len(c) == 1}),
]
httpd.shutdown()
print("\nRESULT:", "ALL PASSED" if all(results) else f"{results.count(False)} FAILED")
