import asyncio
import json
import urllib.request
from storage import now_ms


def send_expo(token, title, body, signal_id):
    payload = json.dumps({
        "to": token, "title": title, "body": body, "sound": "default",
        "channelId": "signals",
        "data": {"signal_id": signal_id or ""}}).encode()
    request = urllib.request.Request("https://exp.host/--/api/v2/push/send", payload,
                                     {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=12) as response:
        result = json.load(response)
    if result.get("data", {}).get("status") != "ok":
        raise RuntimeError("Push provider rejected notification")


async def outbox(store, enabled):
    while True:
        if enabled:
            devices = store.rows("SELECT token FROM devices WHERE enabled=1")
            notifications = store.rows(
                "SELECT * FROM notifications WHERE sent_at IS NULL AND attempts<8 ORDER BY id LIMIT 10")
            for note in notifications:
                try:
                    for device in devices:
                        await asyncio.to_thread(send_expo, device["token"], note["title"],
                                                note["body"], note["signal_id"])
                    # With no registered phone, keep the record for later delivery.
                    if devices:
                        with store.lock, store.connect() as db:
                            db.execute("UPDATE notifications SET sent_at=? WHERE id=?",
                                       (now_ms(), note["id"]))
                except Exception as exc:
                    print("push delivery error:", str(exc), flush=True)
                    with store.lock, store.connect() as db:
                        db.execute("UPDATE notifications SET attempts=attempts+1 WHERE id=?",
                                   (note["id"],))
        await asyncio.sleep(10)
