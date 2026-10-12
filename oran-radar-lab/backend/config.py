import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    db_path: str = os.getenv("RADAR_DB", "radar.sqlite3")
    host: str = os.getenv("RADAR_HOST", "0.0.0.0")
    port: int = int(os.getenv("PORT", os.getenv("RADAR_PORT", "8787")))
    api_token: str = os.getenv("RADAR_API_TOKEN", "")
    scan_pairs: int = int(os.getenv("RADAR_SCAN_PAIRS", "20"))
    min_quote_volume_try: float = float(os.getenv("RADAR_MIN_QUOTE_VOLUME_TRY", "1000000"))
    scan_seconds: int = int(os.getenv("RADAR_SCAN_SECONDS", "300"))
    maker_fee_bps: float = float(os.getenv("RADAR_MAKER_FEE_BPS", "10"))
    exit_fee_bps: float = float(os.getenv("RADAR_EXIT_FEE_BPS", "10"))
    slip_bps: float = float(os.getenv("RADAR_SLIP_BPS", "8"))
    paper_notional_try: float = float(os.getenv("RADAR_PAPER_NOTIONAL_TRY", "1000"))
    push_enabled: bool = os.getenv("RADAR_PUSH_ENABLED", "0") == "1"
    model_version: str = "heuristic-empirical-v0.1"
