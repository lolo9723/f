"""Descriptive chart commentary from observed statistics, without forecasting claims."""


def interpret(features):
    if not features:
        return []
    volume = features["volume_ratio@lag0"]
    compression = features["range_compression@lag0"]
    trend = features["trend_12@lag0"]
    skew = features["return_skew@lag0"]
    flow = features["taker_share@lag0"]
    return [
        f"Son 3 saatin ortalama hacmi önceki dönemin {volume:.2f} katı.",
        f"Son 6 saatin mum aralığı önceki dönemin {compression:.2f} katı; "
        + ("aralıklar daralıyor." if compression < 1 else "aralıklar genişliyor."),
        f"Fiyat 12 saatlik ortalamanın {abs(trend) * 100:.2f}% "
        + ("üzerinde." if trend >= 0 else "altında."),
        f"Son 24 saat getiri çarpıklığı {skew:.2f}; negatif ve pozitif hareketlerin dağılımı ölçülüyor.",
        f"Son kapalı mumda agresif alış hacminin payı {flow * 100:.1f}%.",
        "Bu açıklamalar gözlenen grafiği özetler; gelecek hareket için kanıt tablosu ayrıca değerlendirilir.",
    ]
