/**
 * Local listening store under Electron userData (JSON, atomic writes).
 * Schema: day aggregates keyed by record_id.
 */
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { app } from 'electron';

const STORE_NAME = 'listening-store.json';
const CREDS_NAME = 'sync-credentials.json';

function emptyStore() {
  return {
    version: 1,
    records: {}, // record_id -> { record_id, package, appName, title, day, duration_ms, source, updated_at }
    meta: {
      lastPullAt: null,
      lastPushAt: null,
      streakCache: null,
    },
  };
}

function emptyCreds() {
  return {
    deviceId: null,
    userId: null,
    // token stored here (userData only; never committed). Prefer OS keychain later.
    deviceToken: null,
    displayName: '听时用户',
    syncEnabled: false,
    paired: false,
    accountUserId: null,
    trayEnabled: true,
    autostart: false,
    lastServerTime: null,
  };
}

function storePath() {
  return path.join(app.getPath('userData'), STORE_NAME);
}

function credsPath() {
  return path.join(app.getPath('userData'), CREDS_NAME);
}

function readJson(file, fallback) {
  try {
    if (!fs.existsSync(file)) return structuredClone(fallback);
    return { ...structuredClone(fallback), ...JSON.parse(fs.readFileSync(file, 'utf8')) };
  } catch {
    return structuredClone(fallback);
  }
}

function writeJsonAtomic(file, data) {
  const dir = path.dirname(file);
  fs.mkdirSync(dir, { recursive: true });
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2), 'utf8');
  fs.renameSync(tmp, file);
}

export function recordId(packageName, title, day) {
  const h = crypto.createHash('sha1');
  h.update(`${packageName}\0${title}\0${day}`);
  const hex = h.digest('hex');
  // UUID-shaped deterministic id (compatible spirit with Android nameUUID)
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
}

export function todayLocal() {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export function loadStore() {
  const s = readJson(storePath(), emptyStore());
  if (!s.records) s.records = {};
  return s;
}

export function saveStore(store) {
  writeJsonAtomic(storePath(), store);
}

export function loadCredentials() {
  const c = readJson(credsPath(), emptyCreds());
  if (!c.deviceId) {
    c.deviceId = 'ls-desk-' + crypto.randomBytes(8).toString('hex');
    saveCredentials(c);
  }
  return c;
}

export function saveCredentials(creds) {
  writeJsonAtomic(credsPath(), creds);
}

/** Add elapsed ms to a day aggregate (desktop source by default). */
export function addListeningMs({ packageName, appName, title, day, durationMs, source = 'desktop' }) {
  const store = loadStore();
  const dayKey = day || todayLocal();
  const pkg = packageName || appName || 'unknown';
  const ttl = title || appName || pkg;
  const id = recordId(pkg, ttl, dayKey);
  const existing = store.records[id];
  const now = new Date().toISOString();
  if (existing) {
    existing.duration_ms = (existing.duration_ms || 0) + Math.max(0, durationMs | 0);
    existing.updated_at = now;
    existing.appName = appName || existing.appName;
    existing.source = source || existing.source;
  } else {
    store.records[id] = {
      record_id: id,
      package: pkg,
      appName: appName || pkg,
      title: ttl,
      day: dayKey,
      duration_ms: Math.max(0, durationMs | 0),
      source: source === 'phone' ? 'phone' : 'desktop',
      updated_at: now,
    };
  }
  saveStore(store);
  return store.records[id];
}

export function upsertRemoteRecords(records) {
  const store = loadStore();
  let n = 0;
  for (const r of records || []) {
    const id = r.record_id;
    if (!id) continue;
    const existing = store.records[id];
    const remoteMs = Number(r.duration_ms) || 0;
    if (!existing || remoteMs >= (existing.duration_ms || 0)) {
      store.records[id] = {
        record_id: id,
        package: r.package,
        appName: existing?.appName || r.package,
        title: r.title || '',
        day: r.day,
        duration_ms: remoteMs,
        source: existing?.source || 'phone',
        updated_at: r.updated_at || new Date().toISOString(),
      };
      n += 1;
    }
  }
  saveStore(store);
  return n;
}

export function listRecords() {
  return Object.values(loadStore().records);
}

export function recordsForPush() {
  return listRecords().map((r) => ({
    record_id: r.record_id,
    package: r.package,
    title: r.title,
    day: r.day,
    duration_ms: r.duration_ms,
  }));
}

export function computeStats(records = listRecords()) {
  const today = todayLocal();
  const now = new Date();
  const weekAgo = new Date(now);
  weekAgo.setDate(weekAgo.getDate() - 6);
  const weekStart = weekAgo.toISOString().slice(0, 10);
  const monthPrefix = today.slice(0, 7);

  let todayMs = 0;
  let weekMs = 0;
  let monthMs = 0;
  const byApp = new Map();
  const byDay = new Map();
  const recent = [];

  for (const r of records) {
    const ms = r.duration_ms || 0;
    if (r.day === today) todayMs += ms;
    if (r.day >= weekStart && r.day <= today) weekMs += ms;
    if (r.day.startsWith(monthPrefix)) monthMs += ms;

    const app = r.appName || r.package;
    const prev = byApp.get(app) || { app, package: r.package, duration_ms: 0, source: r.source, titles: [] };
    prev.duration_ms += ms;
    prev.titles.push(r);
    byApp.set(app, prev);

    byDay.set(r.day, (byDay.get(r.day) || 0) + ms);
    recent.push(r);
  }

  // streak: consecutive days ending today with any listening
  let streak = 0;
  for (let i = 0; i < 400; i++) {
    const d = new Date(now);
    d.setDate(d.getDate() - i);
    const key = d.toISOString().slice(0, 10);
    if ((byDay.get(key) || 0) > 0) streak += 1;
    else if (i > 0) break;
  }

  const ranking = [...byApp.values()].sort((a, b) => b.duration_ms - a.duration_ms);
  const maxDur = ranking[0]?.duration_ms || 1;

  recent.sort((a, b) => (b.updated_at || '').localeCompare(a.updated_at || ''));

  const daily = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date(now);
    d.setDate(d.getDate() - i);
    const key = d.toISOString().slice(0, 10);
    const labels = ['日', '一', '二', '三', '四', '五', '六'];
    daily.push({ day: labels[d.getDay()], date: key, sec: Math.round((byDay.get(key) || 0) / 1000) });
  }

  return {
    kpis: {
      todaySec: Math.round(todayMs / 1000),
      weekSec: Math.round(weekMs / 1000),
      monthSec: Math.round(monthMs / 1000),
      streakDays: streak,
    },
    ranking: ranking.map((r, i) => ({
      app: r.app,
      package: r.package,
      durationSec: Math.round(r.duration_ms / 1000),
      progress: r.duration_ms / maxDur,
      device: r.source === 'phone' ? '手机' : '电脑',
      sessions: r.titles.length,
    })),
    dailyListening: daily,
    sourceFunnel: ranking.slice(0, 4).map((r) => ({
      name: r.app,
      pct: r.duration_ms / maxDur,
      sec: Math.round(r.duration_ms / 1000),
    })),
    recent: recent.slice(0, 20).map((r) => ({
      app: r.appName || r.package,
      title: r.title,
      durationSec: Math.round(r.duration_ms / 1000),
      progress: Math.min(1, r.duration_ms / Math.max(maxDur, 1)),
      device: r.source === 'phone' ? '手机' : '电脑',
    })),
    appDetails: Object.fromEntries(
      ranking.map((r) => {
        const titles = [...r.titles]
          .sort((a, b) => b.duration_ms - a.duration_ms)
          .slice(0, 30)
          .map((t) => ({
            title: t.title,
            artist: t.appName || t.package,
            durationSec: Math.round(t.duration_ms / 1000),
            progress: t.duration_ms / Math.max(r.duration_ms, 1),
          }));
        return [r.app, { titles }];
      })
    ),
  };
}

/** Seed demo mock if store empty (first launch UX). */
export function ensureDemoIfEmpty(mockRecords) {
  const store = loadStore();
  if (Object.keys(store.records).length > 0) return false;
  for (const r of mockRecords || []) {
    store.records[r.record_id] = r;
  }
  saveStore(store);
  return true;
}
