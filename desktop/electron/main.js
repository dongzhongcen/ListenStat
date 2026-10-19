import {
  app,
  BrowserWindow,
  Tray,
  Menu,
  nativeImage,
  ipcMain,
  shell,
} from 'electron';
import path from 'path';
import { fileURLToPath } from 'url';
import {
  loadCredentials,
  saveCredentials,
  loadStore,
  addListeningMs,
  upsertRemoteRecords,
  recordsForPush,
  computeStats,
  ensureDemoIfEmpty,
  listRecords,
  todayLocal,
  recordId,
} from './store.js';
import { syncClient } from './syncClient.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const isDev = Boolean(process.env.VITE_DEV_SERVER_URL);

let mainWindow = null;
let tray = null;
let stopMedia = null;
let syncTimer = null;

async function loadMediaModule() {
  if (process.platform === 'win32') {
    return import('./mediaWin32.js');
  }
  return import('./mediaStub.js');
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 1024,
    minHeight: 640,
    title: 'ListenStat',
    backgroundColor: '#F1F1F3',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  if (isDev) {
    mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL);
  } else {
    mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'));
  }

  mainWindow.on('close', (e) => {
    const creds = loadCredentials();
    if (creds.trayEnabled && tray && process.platform === 'win32') {
      e.preventDefault();
      mainWindow.hide();
    }
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

function trayIcon() {
  // Simple 16x16 PNG (blue square) as data URL
  const png = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAMUlEQVQ4T2NkYGD4z0ABYBzVMKoBBgAGhgEGBgYGBgYGBgYGBgYGBgYGBgYGBgYGAGkQAQ8A8Y0iAAAAAElFTkSuQmCC',
    'base64'
  );
  return nativeImage.createFromBuffer(png);
}

function createTray() {
  try {
    if (tray) return;
    tray = new Tray(trayIcon());
    tray.setToolTip('ListenStat 听时');
    rebuildTrayMenu();
    tray.on('double-click', () => {
      mainWindow?.show();
      mainWindow?.focus();
    });
  } catch (e) {
    console.warn('[tray] unavailable:', e.message);
  }
}

function rebuildTrayMenu() {
  if (!tray) return;
  const creds = loadCredentials();
  tray.setContextMenu(
    Menu.buildFromTemplate([
      {
        label: '显示 ListenStat',
        click: () => {
          mainWindow?.show();
          mainWindow?.focus();
        },
      },
      {
        label: creds.syncEnabled ? '云端同步：开' : '云端同步：关',
        enabled: false,
      },
      { type: 'separator' },
      {
        label: '退出',
        click: () => {
          if (tray) {
            tray.destroy();
            tray = null;
          }
          app.exit(0);
        },
      },
    ])
  );
}

function applyAutostart(enabled) {
  try {
    app.setLoginItemSettings({
      openAtLogin: Boolean(enabled),
      path: process.execPath,
      args: [],
    });
  } catch (e) {
    console.warn('[autostart]', e.message);
  }
}

function seedDemoData() {
  const today = todayLocal();
  const demos = [
    { pkg: 'com.netease.cloudmusic', app: '网易云音乐', title: '起风了', ms: 1860_000 },
    { pkg: 'com.ximalaya.ting.android', app: '喜马拉雅', title: '三体 · 第12集', ms: 3240_000 },
    { pkg: 'com.spotify.music', app: 'Spotify', title: 'Blinding Lights', ms: 1980_000 },
    { pkg: 'com.microsoft.edge', app: 'Edge', title: 'TED Talk: How habits work', ms: 2640_000 },
    { pkg: 'tv.danmaku.bili', app: '哔哩哔哩', title: '古典音乐入门指南', ms: 4120_000 },
    { pkg: 'com.netease.cloudmusic', app: '网易云音乐', title: '消愁', ms: 2400_000 },
    { pkg: 'com.tencent.qqmusic', app: 'QQ音乐', title: '晴天', ms: 1500_000 },
  ];
  const records = demos.map((d) => ({
    record_id: recordId(d.pkg, d.title, today),
    package: d.pkg,
    appName: d.app,
    title: d.title,
    day: today,
    duration_ms: d.ms,
    source: d.app.includes('喜马') || d.app.includes('哔哩') || d.app.includes('QQ') ? 'phone' : 'desktop',
    updated_at: new Date().toISOString(),
  }));
  // Also seed a few past days for chart
  for (let i = 1; i <= 6; i++) {
    const d = new Date();
    d.setDate(d.getDate() - i);
    const day = d.toISOString().slice(0, 10);
    const item = demos[i % demos.length];
    records.push({
      record_id: recordId(item.pkg, item.title + '-' + i, day),
      package: item.pkg,
      appName: item.app,
      title: item.title,
      day,
      duration_ms: 1800_000 + i * 400_000,
      source: 'desktop',
      updated_at: new Date().toISOString(),
    });
  }
  ensureDemoIfEmpty(records);
}

function getDashboardPayload() {
  const creds = loadCredentials();
  const stats = computeStats();
  return {
    ...stats,
    profile: {
      displayName: creds.displayName,
      deviceId: creds.deviceId,
      userId: creds.userId,
      syncEnabled: creds.syncEnabled,
      paired: creds.paired,
      accountUserId: creds.accountUserId,
      trayEnabled: creds.trayEnabled,
      autostart: creds.autostart,
    },
    nowPlaying: globalThis.__nowPlaying || null,
    achievements: defaultAchievements(stats),
  };
}

function defaultAchievements(stats) {
  const totalSec =
    listRecords().reduce((a, r) => a + (r.duration_ms || 0), 0) / 1000;
  const apps = new Set(listRecords().map((r) => r.appName || r.package)).size;
  const hasPhone = listRecords().some((r) => r.source === 'phone');
  const hasDesk = listRecords().some((r) => r.source === 'desktop');
  return [
    { id: 'first_hour', name: '初听一小时', desc: '累计收听满 1 小时', unlocked: totalSec >= 3600, icon: 'clock' },
    { id: 'streak_3', name: '三日连听', desc: '连续 3 天有收听记录', unlocked: stats.kpis.streakDays >= 3, icon: 'flame' },
    { id: 'streak_7', name: '一周坚持', desc: '连续 7 天有收听记录', unlocked: stats.kpis.streakDays >= 7, icon: 'flame' },
    { id: 'streak_30', name: '月度达人', desc: '连续 30 天有收听记录', unlocked: stats.kpis.streakDays >= 30, icon: 'trophy' },
    { id: 'apps_5', name: '五源并进', desc: '在 5 个不同应用中有收听', unlocked: apps >= 5, icon: 'apps' },
    { id: 'apps_10', name: '十源合奏', desc: '在 10 个不同应用中有收听', unlocked: apps >= 10, icon: 'apps' },
    { id: 'day_4h', name: '沉浸半日', desc: '单日收听满 4 小时', unlocked: stats.kpis.todaySec >= 14400, icon: 'headphones' },
    { id: 'day_8h', name: '全天沉浸', desc: '单日收听满 8 小时', unlocked: stats.kpis.todaySec >= 28800, icon: 'headphones' },
    { id: 'night_owl', name: '夜猫子', desc: '凌晨 0–5 点累计满 2 小时', unlocked: false, icon: 'moon' },
    { id: 'early_bird', name: '早起鸟', desc: '清晨 5–8 点累计满 2 小时', unlocked: false, icon: 'sun' },
    { id: 'cross_device', name: '双端同步', desc: '手机与电脑均有收听记录', unlocked: hasPhone && hasDesk, icon: 'devices' },
    { id: 'hundred_hours', name: '百小时勋章', desc: '累计收听满 100 小时', unlocked: totalSec >= 360000, icon: 'medal' },
  ];
}

async function ensureRegistered() {
  const creds = loadCredentials();
  if (creds.deviceToken && creds.userId) return creds;
  const res = await syncClient.register(creds.deviceId, creds.displayName);
  creds.deviceToken = res.device_token;
  creds.userId = res.user_id;
  creds.displayName = res.display_name || creds.displayName;
  saveCredentials(creds);
  return creds;
}

async function runSyncCycle() {
  const creds = loadCredentials();
  if (!creds.syncEnabled) return { ok: false, reason: 'disabled' };
  try {
    await ensureRegistered();
    const c = loadCredentials();
    const token = c.deviceToken;
    // pull
    const pulled = await syncClient.pull(token, c.lastServerTime || undefined);
    upsertRemoteRecords(pulled.records || []);
    if (pulled.server_time) {
      c.lastServerTime = pulled.server_time;
    }
    // push
    const records = recordsForPush();
    if (records.length) {
      await syncClient.push(token, records.slice(0, 500));
    }
    try {
      const me = await syncClient.getMe(token);
      c.userId = me.user_id;
      c.paired = Boolean(me.paired);
      c.accountUserId = me.account_user_id || null;
      if (me.display_name) c.displayName = me.display_name;
    } catch {
      /* ignore me errors */
    }
    c.lastPushAt = new Date().toISOString();
    saveCredentials(c);
    notifyRenderer();
    return { ok: true, pulled: (pulled.records || []).length, pushed: records.length };
  } catch (e) {
    return { ok: false, reason: e.message };
  }
}

function notifyRenderer() {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('listenstat:data', getDashboardPayload());
  }
}

function registerIpc() {
  ipcMain.handle('listenstat:getData', async () => getDashboardPayload());

  ipcMain.handle('listenstat:getMedia', async () => globalThis.__nowPlaying || { state: 'idle' });

  ipcMain.handle('listenstat:setProfile', async (_e, patch) => {
    const creds = loadCredentials();
    if (typeof patch.displayName === 'string' && patch.displayName.trim()) {
      creds.displayName = patch.displayName.trim();
      if (creds.syncEnabled && creds.deviceToken) {
        try {
          await syncClient.patchMe(creds.deviceToken, creds.displayName);
        } catch {
          /* offline ok */
        }
      }
    }
    if (typeof patch.trayEnabled === 'boolean') {
      creds.trayEnabled = patch.trayEnabled;
      if (creds.trayEnabled) createTray();
      else if (tray) {
        tray.destroy();
        tray = null;
      }
    }
    if (typeof patch.autostart === 'boolean') {
      creds.autostart = patch.autostart;
      applyAutostart(creds.autostart);
    }
    saveCredentials(creds);
    rebuildTrayMenu();
    return getDashboardPayload();
  });

  ipcMain.handle('listenstat:setSyncEnabled', async (_e, enabled) => {
    const creds = loadCredentials();
    creds.syncEnabled = Boolean(enabled);
    saveCredentials(creds);
    rebuildTrayMenu();
    if (creds.syncEnabled) {
      try {
        await ensureRegistered();
        await runSyncCycle();
      } catch (e) {
        return { ok: false, error: e.message, data: getDashboardPayload() };
      }
    }
    return { ok: true, data: getDashboardPayload() };
  });

  ipcMain.handle('listenstat:syncNow', async () => {
    const result = await runSyncCycle();
    return { ...result, data: getDashboardPayload() };
  });

  ipcMain.handle('listenstat:pairCreate', async () => {
    try {
      const creds = await ensureRegistered();
      // Enabling sync is not required to pair, but we need a token
      const res = await syncClient.pairCreate(creds.deviceToken);
      return { ok: true, code: res.code, expiresAt: res.expires_at };
    } catch (e) {
      return { ok: false, error: e.message };
    }
  });

  ipcMain.handle('listenstat:pairClaim', async (_e, code) => {
    try {
      const creds = await ensureRegistered();
      const res = await syncClient.pairClaim(creds.deviceToken, String(code || '').trim());
      creds.userId = res.user_id;
      creds.paired = true;
      creds.accountUserId = res.user_id;
      if (res.display_name) creds.displayName = res.display_name;
      creds.syncEnabled = true;
      saveCredentials(creds);
      await runSyncCycle();
      rebuildTrayMenu();
      return { ok: true, data: getDashboardPayload(), merged: res.merged_records };
    } catch (e) {
      return { ok: false, error: e.message };
    }
  });

  ipcMain.handle('listenstat:openExternal', async (_e, url) => {
    if (typeof url === 'string' && url.startsWith('https://')) shell.openExternal(url);
  });
}

async function startMediaTracking() {
  const media = await loadMediaModule();
  stopMedia = media.startMediaWatcher((session, elapsedMs) => {
    globalThis.__nowPlaying = {
      state: session.state,
      app: session.appName || session.appPackage || '未知应用',
      title: session.title || '正在播放',
      artist: session.artist || '',
      elapsedSec: Math.round((session.positionMs || 0) / 1000),
      progress: session.durationMs
        ? Math.min(1, (session.positionMs || 0) / session.durationMs)
        : 0.5,
    };
    if (elapsedMs > 0 && session.state === 'playing') {
      addListeningMs({
        packageName: session.appPackage || session.appName || 'unknown',
        appName: session.appName || session.appPackage || '未知应用',
        title: session.title || session.appName || '未知标题',
        durationMs: elapsedMs,
        source: 'desktop',
      });
      notifyRenderer();
    }
  }, 5000);

  // Initial poll
  const cur = await media.getCurrentMediaSession();
  if (cur.state !== 'idle') {
    globalThis.__nowPlaying = {
      state: cur.state,
      app: cur.appName || cur.appPackage || '未知应用',
      title: cur.title || '正在播放',
      artist: cur.artist || '',
      elapsedSec: Math.round((cur.positionMs || 0) / 1000),
      progress: 0.5,
    };
  }
}

app.whenReady().then(async () => {
  seedDemoData();
  registerIpc();
  createWindow();
  const creds = loadCredentials();
  if (creds.trayEnabled) createTray();
  applyAutostart(creds.autostart);
  await startMediaTracking();
  syncTimer = setInterval(() => {
    if (loadCredentials().syncEnabled) runSyncCycle();
  }, 5 * 60 * 1000);

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
    else mainWindow?.show();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    const creds = loadCredentials();
    if (!creds.trayEnabled) app.quit();
  }
});

app.on('before-quit', () => {
  if (stopMedia) stopMedia();
  if (syncTimer) clearInterval(syncTimer);
});
