import asyncio
import threading

from config import Settings
from http_api import api_server
from push import outbox
from service import Radar
from storage import Store


async def main():
    settings = Settings()
    store = Store(settings.db_path)
    radar = Radar(settings, store)
    server = api_server(settings, store, radar)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    print(f"Oran Radar PAPER_ONLY API http://{settings.host}:{settings.port}", flush=True)
    try:
        await asyncio.gather(radar.run(), outbox(store, settings.push_enabled))
    finally:
        server.shutdown()


if __name__ == "__main__":
    asyncio.run(main())
