import { useEffect, useState } from 'react';
import { AppState, Platform } from 'react-native';
import { requireOptionalNativeModule } from 'expo-modules-core';

export const PHONE_API = 'phone://local';
export type PhoneCoverage = {
  mode: 'ALL_TRY' | 'SELECTED'; total: number; scored: number; scores_current: number;
  researched: number; backfill_complete: number; discovered_at?: number;
  discovery_error?: string; backoff_until?: number; score_next?: string; research_next?: string;
  last_score_cycle?: number;
};
export type PhoneStatus = {
  available: boolean; enabled: boolean; running: boolean; foreground: boolean; charging: boolean; periodicScheduled: boolean;
  pairs: string[]; lastCycle: number; engineError: string; serviceNote: string;
  driveSelected: boolean; driveName: string; backupHandoff: number; backupBytes: number; backupError: string;
  coverage?: PhoneCoverage;
  scoreRunning?: boolean; scoreNextAt?: number; lastScoreCycle?: number; scoreError?: string;
};
type NativePhone = {
  status(): Promise<string>; start(pairs: string[]): Promise<string>; stop(): Promise<string>;
  refreshScores(pairs: string[]): Promise<string>;
  prices(visible: boolean): Promise<string>;
  selectDrive(): Promise<string | null>; backupNow(): Promise<string>;
  request(method: string, path: string, query: string, body: string): Promise<string>;
};
const native = Platform.OS === 'android' ? requireOptionalNativeModule<NativePhone>('RadarPhone') : null;
const empty: PhoneStatus = { available: !!native, enabled: false, running: false, foreground: false, charging: false, periodicScheduled: false,
  pairs: [], lastCycle: 0, engineError: '', serviceNote: '',
  driveSelected: false, driveName: '', backupHandoff: 0, backupBytes: 0, backupError: '' };
let status = empty;
const listeners = new Set<() => void>();
function publish(value: string) {
  status = JSON.parse(value);
  listeners.forEach(listener => listener());
  return status;
}
export async function refreshPhone() {
  if (native) publish(await native.status());
  return status;
}
export function usePhone() {
  const [value, setValue] = useState(status);
  useEffect(() => {
    const listener = () => setValue(status);
    listeners.add(listener);
    void refreshPhone().catch(() => undefined);
    const timer = setInterval(() => {
      if (AppState.currentState === 'active') void refreshPhone().catch(() => undefined);
    }, 15000);
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') void refreshPhone().catch(() => undefined);
    });
    return () => { listeners.delete(listener); clearInterval(timer); subscription.remove(); };
  }, []);
  return value;
}
function available(): NativePhone {
  if (!native) throw new Error('Telefon motoru bu sürümde kullanılamıyor. Android Lab APK gerekli.');
  return native;
}
export async function startPhone(pairs: string[] = []) { return publish(await available().start(pairs)); }
export async function stopPhone() { return publish(await available().stop()); }
export async function selectDrive() {
  const value = await available().selectDrive();
  return value ? publish(value) : null;
}
export async function backupPhone() { return publish(await available().backupNow()); }
export type ScoreRefreshResult = { status: 'QUEUED' | 'RUNNING' | 'THROTTLED' | 'PAUSED'; nextAt: number };
export async function refreshScores(pairs: string[] = []): Promise<ScoreRefreshResult> {
  return JSON.parse(await available().refreshScores(pairs));
}
export async function phoneJSON(path: string, init?: RequestInit) {
  const index = path.indexOf('?');
  const route = index >= 0 ? path.slice(0, index) : path;
  const query = index >= 0 ? path.slice(index + 1) : '';
  const response = JSON.parse(await available().request(init?.method || 'GET', route, query, typeof init?.body === 'string' ? init.body : '{}'));
  if (response.status >= 400) throw new Error(response.body?.error || `Telefon motoru: ${response.status}`);
  return response.body;
}
