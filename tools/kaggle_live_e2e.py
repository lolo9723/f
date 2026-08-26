#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests

RPC = "https://api.kaggle.com/v1/kernels.KernelsApiService"
MAX_SECONDS = 4 * 60 * 60
POLL_SECONDS = 30
OUTPUT_WAIT_SECONDS = 12 * 60


def compact(value, limit=300):
    text = " ".join(str(value or "").split())
    return text if len(text) <= limit else text[:limit] + "…"


def fail(message):
    payload = {
        "success": False,
        "time_utc": datetime.now(timezone.utc).isoformat(),
        "error": compact(message),
    }
    Path("e2e_failure.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    raise RuntimeError(payload["error"])


def auth_headers(token):
    return {"Authorization": f"Bearer {token}", "Accept": "application/json"}


def rpc(method, token, body, allow_redirects=True, timeout=90):
    url = f"{RPC}/{method}"
    response = requests.post(
        url,
        headers={**auth_headers(token), "Content-Type": "application/json; charset=utf-8"},
        json=body,
        timeout=timeout,
        allow_redirects=allow_redirects,
    )
    if response.status_code < 200 or response.status_code >= 300:
        raise RuntimeError(f"{method} HTTP {response.status_code}: {compact(response.text)}")
    return response


def push_kernel(user, slug, token, python_text):
    body = {
        "slug": f"{user}/{slug}",
        "newTitle": slug,
        "text": python_text,
        "language": "python",
        "kernelType": "script",
        "isPrivate": True,
        "enableGpu": True,
        "enableInternet": True,
        "machineShape": "NvidiaTeslaT4",
        "sessionTimeoutSeconds": 10800,
        "datasetDataSources": [],
        "competitionDataSources": [],
        "kernelDataSources": [],
        "modelDataSources": [],
    }
    data = rpc("SaveKernel", token, body).json()
    version = int(data.get("versionNumber", data.get("version_number", 0)) or 0)
    if version <= 0:
        raise RuntimeError("SaveKernel succeeded without a concrete version number")
    return version


def normalize_status(raw):
    value = str(raw or "").upper()
    if "COMPLETE" in value:
        return "COMPLETE"
    if "ERROR" in value or "FAIL" in value:
        return "FAILED"
    if "CANCEL" in value:
        return "CANCELLED"
    if "RUNNING" in value:
        return "RUNNING"
    if "QUEUE" in value or "PENDING" in value or "NEW_SCRIPT" in value:
        return "QUEUED"
    return value or "UNKNOWN"


def wait_for_kernel(user, slug, token):
    deadline = time.time() + MAX_SECONDS
    last = "UNKNOWN"
    while time.time() < deadline:
        data = rpc("GetKernelSessionStatus", token, {"userName": user, "kernelSlug": slug}).json()
        failure = data.get("failureMessage", data.get("failure_message", ""))
        if failure:
            raise RuntimeError(f"Kaggle failure: {compact(failure)}")
        last = normalize_status(data.get("status", ""))
        print(f"Kaggle status: {last}", flush=True)
        if last == "COMPLETE":
            return
        if last in ("FAILED", "CANCELLED"):
            raise RuntimeError(f"Kaggle terminal status: {last}")
        time.sleep(POLL_SECONDS)
    raise RuntimeError(f"Kaggle run exceeded {MAX_SECONDS} seconds; last status={last}")


def list_outputs(user, slug, token):
    page_token = ""
    files = []
    for _ in range(20):
        body = {"userName": user, "kernelSlug": slug, "pageSize": 100}
        if page_token:
            body["pageToken"] = page_token
        data = rpc("ListKernelSessionOutput", token, body).json()
        for item in data.get("files", []) or []:
            name = item.get("fileName", item.get("file_name", ""))
            url = item.get("url", "")
            if name and url:
                files.append((name, url))
        page_token = data.get("nextPageToken", data.get("next_page_token", "")) or ""
        if not page_token:
            break
    return files


def resolve_output(user, slug, token, wanted):
    deadline = time.time() + OUTPUT_WAIT_SECONDS
    while time.time() < deadline:
        for name, url in list_outputs(user, slug, token):
            if name == wanted:
                if not str(url).startswith("https://"):
                    raise RuntimeError(f"Unsafe output URL for {wanted}")
                return url
        print(f"Waiting for persisted output: {wanted}", flush=True)
        time.sleep(20)
    raise RuntimeError(f"Persisted output not found: {wanted}")


def download_json(url, destination):
    response = requests.get(url, timeout=120)
    if response.status_code < 200 or response.status_code >= 300:
        raise RuntimeError(f"JSON output HTTP {response.status_code}")
    Path(destination).write_bytes(response.content)
    return response.json()


def download_file(url, destination):
    with requests.get(url, timeout=180, stream=True) as response:
        if response.status_code < 200 or response.status_code >= 300:
            raise RuntimeError(f"File output HTTP {response.status_code}")
        with open(destination, "wb") as out:
            for chunk in response.iter_content(chunk_size=1024 * 1024):
                if chunk:
                    out.write(chunk)


def assert_status_certificate(data):
    translation = data.get("translation") or {}
    checks = {
        "ai_ok": data.get("ai_ok") is True,
        "stage_complete": str(data.get("stage", "")).upper() == "COMPLETE",
        "story_v3": "story-v3" in str(data.get("engine", "")).lower(),
        "five_scenes": int(data.get("scenes", 0) or 0) == 5,
        "english_prompt": str(data.get("prompt_language", "")).lower() == "english",
        "translation_tr_to_en": str(translation.get("mode", "")).lower() == "tr_to_en",
        "continuity": data.get("continuity") == "previous_scene_last_frame",
        "continuity_strength": 0.55 <= float(data.get("continuity_strength", -1)) <= 0.75,
        "audio_aac": "aac" in str(data.get("audio", "")).lower(),
        "final_name": data.get("final") == "FINAL.mp4",
        "no_error": not str(data.get("error", "")).strip(),
    }
    failed = [name for name, ok in checks.items() if not ok]
    if failed:
        raise RuntimeError("status.json certificate failed: " + ", ".join(failed))
    return checks


def ffprobe(path):
    raw = subprocess.check_output(
        [
            "ffprobe", "-v", "error",
            "-show_entries", "stream=codec_type,width,height:format=duration",
            "-of", "json", str(path),
        ],
        text=True,
    )
    data = json.loads(raw)
    Path("ffprobe.json").write_text(json.dumps(data, indent=2), encoding="utf-8")
    return data


def assert_media(path, probe):
    size = Path(path).stat().st_size
    if size < 100_000:
        raise RuntimeError(f"FINAL.mp4 too small: {size} bytes")
    streams = probe.get("streams", []) or []
    video = next((s for s in streams if s.get("codec_type") == "video"), None)
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)
    if not video:
        raise RuntimeError("FINAL.mp4 has no video stream")
    if int(video.get("width", 0) or 0) != 1080 or int(video.get("height", 0) or 0) != 1920:
        raise RuntimeError(f"FINAL.mp4 wrong dimensions: {video.get('width')}x{video.get('height')}")
    if not audio:
        raise RuntimeError("FINAL.mp4 has no audio stream")
    duration = float((probe.get("format") or {}).get("duration", 0) or 0)
    if duration < 8.0:
        raise RuntimeError(f"FINAL.mp4 too short: {duration:.3f}s")
    return {"bytes": size, "duration_seconds": duration, "width": 1080, "height": 1920, "audio_stream": True}


def main():
    user = os.environ.get("KAGGLE_USERNAME", "").strip()
    token = os.environ.get("KAGGLE_API_TOKEN", "").strip()
    script_path = Path(os.environ.get("E2E_KERNEL_SCRIPT", "e2e/kernel.py"))
    slug = os.environ.get("E2E_KERNEL_SLUG", "").strip()
    if not user or not token:
        fail("Missing KAGGLE_USERNAME or KAGGLE_API_TOKEN GitHub secret")
    if not slug:
        fail("Missing E2E_KERNEL_SLUG")
    if not script_path.is_file() or script_path.stat().st_size < 1000:
        fail("Generated production kernel.py is missing or unexpectedly small")

    try:
        version = push_kernel(user, slug, token, script_path.read_text(encoding="utf-8"))
        print(f"Private Kaggle kernel created: {user}/{slug} version={version}", flush=True)
        wait_for_kernel(user, slug, token)

        status_url = resolve_output(user, slug, token, "status.json")
        status_data = download_json(status_url, "status.json")
        status_checks = assert_status_certificate(status_data)

        final_url = resolve_output(user, slug, token, "FINAL.mp4")
        download_file(final_url, "FINAL.mp4")
        media_probe = ffprobe("FINAL.mp4")
        media_checks = assert_media("FINAL.mp4", media_probe)

        certificate = {
            "success": True,
            "time_utc": datetime.now(timezone.utc).isoformat(),
            "kernel": {"owner": user, "slug": slug, "version": version, "private": True, "machine_shape": "NvidiaTeslaT4"},
            "status_checks": status_checks,
            "status": status_data,
            "media": media_checks,
        }
        Path("e2e_certificate.json").write_text(
            json.dumps(certificate, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print("LIVE_KAGGLE_E2E_PASS", flush=True)
    except Exception as exc:
        fail(exc)


if __name__ == "__main__":
    main()
