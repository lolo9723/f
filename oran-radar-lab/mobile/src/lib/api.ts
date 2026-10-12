import AsyncStorage from '@react-native-async-storage/async-storage';
import { PHONE_API, phoneJSON } from './phone';

export async function requestJSON(url: string, init?: RequestInit) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 12000);
  try {
    const response = await fetch(url, { ...init, signal: controller.signal });
    if (!response.ok) {
      const payload = await response.json().catch(() => null);
      throw new Error(payload?.error || `HTTP ${response.status}`);
    }
    return await response.json();
  } finally {
    clearTimeout(timeout);
  }
}

export async function serverJSON(base: string, path: string, init?: RequestInit, token?: string) {
  if (base === PHONE_API) return phoneJSON(path, init);
  const key = token ?? await AsyncStorage.getItem('radarApiToken') ?? '';
  return requestJSON(base + path, { ...init, headers: {
    'Content-Type': 'application/json', ...(key ? { Authorization: 'Bearer ' + key } : {}), ...init?.headers
  } });
}

export function normalizeServer(value: string) {
  const trimmed = value.trim().replace(/\/+$/, '');
  if (!trimmed) return '';
  const parsed = new URL(trimmed);
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password ||
      parsed.search || parsed.hash || parsed.pathname !== '/') {
    throw new Error('Sunucu adresi http:// veya https:// ile başlamalı; ek yol içermemeli.');
  }
  return trimmed;
}
