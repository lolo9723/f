import os
from dataclasses import dataclass, field

HOUR = 3_600_000
PERIOD = 300_000
VERSION = "radar-lab-2.1"


@dataclass(frozen=True)
class Config:
    pairs: tuple = field(
        default_factory=lambda: tuple(
            os.getenv("RADAR_PAIRS", "BTCTRY,ETHTRY,SOLTRY").split(",")
        )
    )
    lookback_days: int = field(
        default_factory=lambda: int(os.getenv("LOOKBACK_DAYS", "730"))
    )
    horizons: tuple = (1, 6, 24)
    alpha: float = 0.05
    power: float = field(default_factory=lambda: float(os.getenv("STAT_POWER", "0.8")))
    detectable_lift: float = field(
        default_factory=lambda: float(os.getenv("MIN_DETECTABLE_LIFT", "0.10"))
    )
    ci_half_width: float = field(
        default_factory=lambda: float(os.getenv("CI_HALF_WIDTH", "0.10"))
    )
    retrain_ms: int = 24 * HOUR
    max_pages: int = 5
    max_bars: int = 30000
    discover_pairs: bool = False
    min_move: float = 0.005
    fee_bps: float = 28.0
    token: str = field(default_factory=lambda: os.getenv("RADAR_API_TOKEN", ""))
    max_forward: int = 2000
    stop_horizons: tuple = (6, 24, 48)
    stop_percentages: tuple = (0.005, 0.01, 0.015, 0.02, 0.03, 0.05)
    stop_atr_multipliers: tuple = (0.75, 1.0, 1.5, 2.0, 3.0, 4.0)
    entry_alpha: float = 0.01
    entry_min_gain_bps: float = 10.0
    stop_alpha: float = 0.01
    stop_min_gain_bps: float = field(
        default_factory=lambda: float(os.getenv("STOP_MIN_GAIN_BPS", "10"))
    )
    stop_slippage_bps: float = field(
        default_factory=lambda: float(os.getenv("STOP_SLIPPAGE_BPS", "5"))
    )
    stop_max_fraction: float = 0.10
    stop_target_atr: float = 2.0

    def __post_init__(self):
        import re

        if (not self.pairs and not self.discover_pairs) or any(
            not re.fullmatch(r"[A-Z0-9]{2,20}TRY", p) for p in self.pairs
        ):
            raise ValueError("RADAR_PAIRS must contain Binance TR TRY pairs")
        if not 30 <= self.lookback_days <= 1200:
            raise ValueError("LOOKBACK_DAYS must be 30..1200")
        if (
            not 0.5 < self.power < 1
            or not 0.01 <= self.detectable_lift <= 0.5
            or not 0.01 <= self.ci_half_width <= 0.25
        ):
            raise ValueError("Invalid predeclared statistical design")
        if (
            not 0 < self.entry_alpha < 0.05
            or not 0 < self.entry_min_gain_bps <= 500
            or not self.stop_horizons
            or any(
                not isinstance(h, int) or not 1 <= h <= 48 for h in self.stop_horizons
            )
            or len(set(self.stop_horizons)) != len(self.stop_horizons)
            or not self.stop_percentages
            or not self.stop_atr_multipliers
            or len(set(self.stop_percentages)) != len(self.stop_percentages)
            or len(set(self.stop_atr_multipliers)) != len(self.stop_atr_multipliers)
            or any(not 0 < p <= self.stop_max_fraction for p in self.stop_percentages)
            or any(not 0 < a <= 10 for a in self.stop_atr_multipliers)
            or not 0 < self.stop_alpha < 0.05
            or not 0 < self.stop_min_gain_bps <= 500
            or not 0 <= self.stop_slippage_bps <= 500
            or not 0.01 <= self.stop_max_fraction < 1
            or not 0 < self.stop_target_atr <= 10
            or self.max_forward < 1
        ):
            raise ValueError("Invalid stop-loss research design")


def score_direction_design(cfg):
    import hashlib
    import json

    value = {
        k: getattr(cfg, k)
        for k in (
            "horizons",
            "alpha",
            "power",
            "detectable_lift",
            "ci_half_width",
            "fee_bps",
            "min_move",
        )
    }
    value["pairs"] = sorted(cfg.pairs)
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()[:16]
