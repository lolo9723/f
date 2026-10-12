export function formatMoney(n?: number | null) {
  if (n == null || !Number.isFinite(n)) return '—';
  return n.toLocaleString('tr-TR', n >= 100 ? { maximumFractionDigits: 2 } : { maximumSignificantDigits: 10 });
}
