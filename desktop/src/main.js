const state = {
  view: 'overview',
  period: 'day',
  device: 'all',
  search: '',
  selectedApp: null,
  dayOffset: 0,
  data: null,
  pairCode: null,
  pairExpires: null,
  pairMsg: '',
  busy: false,
};

const api = typeof window !== 'undefined' ? window.listenstat : null;

const ICONS = {
  clock: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>`,
  flame: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z"/></svg>`,
  trophy: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9H4.5a2.5 2.5 0 0 1 0-5H6"/><path d="M18 9h1.5a2.5 2.5 0 0 0 0-5H18"/><path d="M4 22h16"/><path d="M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20 7 22"/><path d="M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20 17 22"/><path d="M18 2H6v7a6 6 0 0 0 12 0V2Z"/></svg>`,
  apps: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><rect width="7" height="7" x="3" y="3" rx="1"/><rect width="7" height="7" x="14" y="3" rx="1"/><rect width="7" height="7" x="14" y="14" rx="1"/><rect width="7" height="7" x="3" y="14" rx="1"/></svg>`,
  headphones: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3"/></svg>`,
  moon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/></svg>`,
  sun: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/></svg>`,
  devices: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="20" x="5" y="2" rx="2" ry="2"/><path d="M12 18h.01"/></svg>`,
  medal: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M7.21 15 2.66 7.14a2 2 0 0 1 .13-2.2L4.4 2.8A2 2 0 0 1 6 2h12a2 2 0 0 1 1.6.8l1.6 2.14a2 2 0 0 1 .14 2.2L15.79 15"/><path d="M11 12 5.12 2.2"/><path d="m13 12 5.88-9.8"/><path d="M8 7h8"/><circle cx="12" cy="17" r="5"/><path d="M12 18v-2h-.5"/></svg>`,
  music: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>`,
  chevronL: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="m15 18-6-6 6-6"/></svg>`,
  chevronR: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="m9 18 6-6-6-6"/></svg>`,
};

function emptyDashboard() {
  return {
    profile: {
      displayName: '',
      deviceId: '',
      syncEnabled: false,
      trayEnabled: true,
      autostart: false,
      paired: false,
    },
    kpis: { todaySec: 0, weekSec: 0, monthSec: 0, streakDays: 0 },
    nowPlaying: { state: 'idle' },
    recent: [],
    dailyListening: [],
    sourceFunnel: [],
    ranking: [],
    achievements: [],
    appDetails: {},
  };
}

function data() {
  return state.data || emptyDashboard();
}

function formatDuration(sec) {
  if (sec == null || Number.isNaN(sec)) return '0 分钟';
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  if (h > 0 && m > 0) return `${h} 小时 ${m} 分`;
  if (h > 0) return `${h} 小时`;
  if (m > 0) return `${m} 分钟`;
  return `${sec} 秒`;
}

function formatDurationShort(sec) {
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  if (h > 0) return `${h}时${m}分`;
  return `${m}分`;
}

function matchDevice(item) {
  if (state.device === 'all') return true;
  if (state.device === 'phone') return item.device === '手机';
  if (state.device === 'pc') return item.device === '电脑';
  return true;
}

function matchSearch(text) {
  const q = state.search.trim().toLowerCase();
  if (!q) return true;
  return String(text).toLowerCase().includes(q);
}

function dayLabel(offset) {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  if (offset === 0) return `今天 ${m}-${day}`;
  if (offset === -1) return `昨天 ${m}-${day}`;
  return `${y}-${m}-${day}`;
}

function renderOverview() {
  const d = data();
  const { kpis, dailyListening, sourceFunnel, recent } = d;
  const nowPlaying = Object.prototype.hasOwnProperty.call(d, "nowPlaying") ? d.nowPlaying : { state: "idle" };
  const maxBar = Math.max(...(dailyListening || []).map((x) => x.sec), 1);
  const filteredRecent = (recent || []).filter((r) => matchDevice(r) && (matchSearch(r.app) || matchSearch(r.title)));

  const np = nowPlaying && nowPlaying.state !== 'idle' ? nowPlaying : null;

  return `
    <div class="ov-kpis">
      <div class="card">
        <div class="card-title">今日时长</div>
        <div class="card-kpi">${formatDuration(kpis.todaySec)}</div>
        <div class="card-hint">本地统计</div>
      </div>
      <div class="card">
        <div class="card-title">本周时长</div>
        <div class="card-kpi">${formatDuration(kpis.weekSec)}</div>
        <div class="card-hint">近 7 天合计</div>
      </div>
      <div class="card">
        <div class="card-title">本月时长</div>
        <div class="card-kpi">${formatDuration(kpis.monthSec)}</div>
        <div class="card-hint">本月累计</div>
      </div>
      <div class="card">
        <div class="card-title">连续天数</div>
        <div class="card-kpi">${kpis.streakDays} 天</div>
        <div class="card-hint ${kpis.streakDays > 0 ? 'up' : ''}">${kpis.streakDays > 0 ? '保持中' : '尚未开始'}</div>
      </div>
    </div>

    <div class="ov-mid">
      <div class="card">
        <div class="card-title">近 7 日收听</div>
        <div class="card-kpi" style="font-size:22px">${formatDuration((dailyListening || []).reduce((a, b) => a + b.sec, 0))}</div>
        <div class="chart-bars">
          ${(dailyListening || [])
            .map(
              (x) => `
            <div class="chart-bar">
              <div class="chart-bar-fill" style="height:${Math.max(6, (x.sec / maxBar) * 100)}%"></div>
              <div class="chart-bar-label">${x.day}</div>
            </div>`
            )
            .join('')}
        </div>
      </div>
      <div class="card">
        <div class="card-title">来源占比</div>
        <div class="card-kpi" style="font-size:22px">${(sourceFunnel || []).length} 个主要来源</div>
        <div class="funnel">
          ${(sourceFunnel || [])
            .map(
              (s) => `
            <div class="funnel-row">
              <div class="funnel-name">${s.name}</div>
              <div class="funnel-bar"><span style="width:${Math.round(s.pct * 100)}%"></span></div>
              <div class="funnel-pct">${Math.round(s.pct * 100)}%</div>
            </div>`
            )
            .join('') || '<div class="empty">暂无数据</div>'}
        </div>
      </div>
    </div>

    <div class="card">
      <div class="section-label">正在播放 / 最近</div>
      ${
        np
          ? `<div class="now-playing-card">
        <div class="now-art">${ICONS.music}</div>
        <div class="now-info">
          <div class="now-app">${np.app || ''}</div>
          <div class="now-title">${np.title || '正在播放'}</div>
          <div class="now-artist">${np.artist || ''} · 本段 ${formatDuration(np.elapsedSec || 0)}</div>
          <div class="progress thin" style="margin-top:8px"><span style="width:${Math.round((np.progress || 0) * 100)}%"></span></div>
        </div>
      </div>`
          : `<div class="now-playing-card" style="opacity:0.75">
        <div class="now-art">${ICONS.music}</div>
        <div class="now-info">
          <div class="now-app">媒体会话</div>
          <div class="now-title">当前无播放</div>
          <div class="now-artist">Windows 上将通过系统媒体控件读取</div>
        </div>
      </div>`
      }
      <div class="recent-list">
        ${
          filteredRecent.length
            ? filteredRecent
                .map(
                  (r) => `
          <div class="recent-item">
            <div>
              <div class="recent-title">${r.title}</div>
              <div class="recent-meta">${r.app} · ${r.device}</div>
            </div>
            <div class="recent-dur">${formatDuration(r.durationSec)}</div>
            <div class="progress thin"><span style="width:${Math.round(r.progress * 100)}%"></span></div>
          </div>`
                )
                .join('')
            : '<div class="empty">没有匹配的记录</div>'
        }
      </div>
    </div>
  `;
}

function renderRank() {
  const items = (data().ranking || []).filter((r) => matchDevice(r) && matchSearch(r.app));
  const topCards = items.slice(0, 4);
  const rest = items.slice(4);
  if (!state.selectedApp && items[0]) state.selectedApp = items[0].app;

  return `
    <div class="rank-header">
      <h2>应用排行</h2>
      <span>按收听时长 · 前 ${items.length} 名</span>
    </div>
    <div class="rank-grid" style="margin-bottom:18px">
      ${topCards
        .map(
          (r, i) => `
        <div class="rank-card ${i === 0 ? 'active' : ''}" data-app="${r.app}">
          <div class="rank-top">
            <div class="rank-name">${r.app}</div>
            <div class="rank-badge">第 ${i + 1} 名</div>
          </div>
          <div class="rank-desc">${r.sessions} 次会话 · ${r.device}</div>
          <div class="rank-meta">
            <span>进度</span>
            <span>${formatDuration(r.durationSec)}</span>
          </div>
          <div class="progress"><span style="width:${Math.round(r.progress * 100)}%"></span></div>
        </div>`
        )
        .join('')}
    </div>
    ${
      rest.length
        ? `<div class="rank-list">
      ${rest
        .map(
          (r, i) => `
        <div class="rank-row" data-app="${r.app}">
          <div class="rank-idx">${i + 5}</div>
          <div>
            <div class="rank-row-name">${r.app}</div>
            <div class="rank-row-sub">${r.sessions} 次 · ${r.device}</div>
            <div class="progress thin" style="margin-top:8px"><span style="width:${Math.round(r.progress * 100)}%"></span></div>
          </div>
          <div class="rank-row-dur">${formatDurationShort(r.durationSec)}</div>
        </div>`
        )
        .join('')}
    </div>`
        : ''
    }
    ${!items.length ? '<div class="empty">没有匹配的应用</div>' : ''}
  `;
}

function renderDetail() {
  const ranking = data().ranking || [];
  const apps = ranking.filter((r) => matchSearch(r.app));
  const selected = state.selectedApp || apps[0]?.app || '';
  const detail = (data().appDetails || {})[selected] || { titles: [] };
  const titles = (detail?.titles || []).filter((t) => matchSearch(t.title) || matchSearch(t.artist));
  const scale = state.dayOffset === 0 ? 1 : Math.max(0.35, 1 + state.dayOffset * 0.08);

  return `
    <div class="detail-layout">
      <div class="app-list">
        ${apps
          .map(
            (a) => `
          <button type="button" class="app-list-item ${a.app === selected ? 'active' : ''}" data-select-app="${a.app}">
            <div class="app-list-icon">${a.app.slice(0, 1)}</div>
            <div class="app-list-name">${a.app}</div>
            <div class="app-list-dur">${formatDurationShort(a.durationSec)}</div>
          </button>`
          )
          .join('') || '<div class="empty">暂无应用</div>'}
      </div>
      <div class="detail-panel">
        <div class="detail-toolbar">
          <h2>${selected || '明细'}</h2>
          <div class="day-switcher">
            <button type="button" id="day-prev" aria-label="前一天">${ICONS.chevronL}</button>
            <div class="day-label">${dayLabel(state.dayOffset)}</div>
            <button type="button" id="day-next" aria-label="后一天" ${state.dayOffset >= 0 ? 'disabled style="opacity:0.35"' : ''}>${ICONS.chevronR}</button>
          </div>
        </div>
        <div class="title-list">
          ${
            titles.length
              ? titles
                  .map((t) => {
                    const sec = Math.round(t.durationSec * scale);
                    return `
            <div class="title-card">
              <div class="title-card-top">
                <div>
                  <div class="title-name">${t.title}</div>
                  <div class="title-artist">${t.artist}</div>
                </div>
                <div class="title-dur">${formatDuration(sec)}</div>
              </div>
              <div class="progress"><span style="width:${Math.round(t.progress * 100)}%"></span></div>
            </div>`;
                  })
                  .join('')
              : '<div class="card empty">该应用暂无标题明细</div>'
          }
        </div>
      </div>
    </div>
  `;
}

function renderAchievements() {
  const list = data().achievements || [];
  const unlocked = list.filter((a) => a.unlocked).length;
  return `
    <div class="ach-header">
      <h2>成就</h2>
      <p>已解锁 ${unlocked} / ${list.length}</p>
    </div>
    <div class="ach-grid">
      ${list
        .map((a) => {
          if (state.search && !matchSearch(a.name) && !matchSearch(a.desc)) return '';
          return `
        <div class="ach-card ${a.unlocked ? '' : 'locked'}">
          <div class="ach-icon">${ICONS[a.icon] || ICONS.medal}</div>
          <div class="ach-name">${a.name}</div>
          <div class="ach-desc">${a.desc}</div>
          <div class="ach-status">${a.unlocked ? '已解锁' : '未解锁'}</div>
        </div>`;
        })
        .join('')}
    </div>
  `;
}

function renderSettings() {
  const p = data().profile || emptyDashboard().profile;
  const pairedLabel = p.paired ? '已与其他设备配对' : '尚未配对';
  return `
    <div class="settings-wrap">
      <h2>设置</h2>
      <div class="settings-section">
        <h3>个人资料</h3>
        <div class="field">
          <label for="display-name">显示名称</label>
          <input id="display-name" type="text" value="${p.displayName || ''}" />
        </div>
        <div class="field">
          <label for="device-id">设备 ID</label>
          <input id="device-id" type="text" value="${p.deviceId || ''}" disabled />
        </div>
        <button type="button" class="btn-primary" id="btn-save-profile" style="margin-top:8px">保存名称</button>
      </div>
      <div class="settings-section">
        <h3>云端同步</h3>
        <div class="toggle-row">
          <div class="toggle-info">
            <div class="t-title">启用同步</div>
            <div class="t-desc">与手机端共享每日收听合计（默认关闭）</div>
          </div>
          <label class="toggle">
            <input type="checkbox" id="sync-toggle" ${p.syncEnabled ? 'checked' : ''} />
            <span class="toggle-track"></span>
          </label>
        </div>
        <div class="field" style="margin-top:14px">
          <button type="button" class="btn-primary" id="btn-sync-now" ${p.syncEnabled ? '' : 'disabled style="opacity:0.5"'}>立即同步</button>
        </div>
        <p class="settings-note" id="sync-status">${p.syncEnabled ? '同步已开启' : '同步已关闭'} · ${pairedLabel}</p>
      </div>
      <div class="settings-section">
        <h3>设备配对</h3>
        <p class="settings-note" style="margin-top:0;margin-bottom:12px">
          在本机生成配对码，供手机端输入；或输入手机端生成的配对码，将本机并入同一账号。
        </p>
        <div class="pair-actions" style="display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-bottom:12px">
          <button type="button" class="btn-primary" id="btn-pair-create">生成本机配对码</button>
          ${
            state.pairCode
              ? `<div class="pair-code-display" style="font-size:28px;letter-spacing:0.2em;font-weight:600">${state.pairCode}</div>`
              : ''
          }
        </div>
        ${state.pairExpires ? `<p class="settings-note">有效期至 ${state.pairExpires}</p>` : ''}
        <div class="field">
          <label for="pair-code-input">输入对方配对码</label>
          <input id="pair-code-input" type="text" placeholder="输入 6 位配对码" maxlength="16" />
        </div>
        <button type="button" class="btn-primary" id="btn-pair-claim">用配对码加入</button>
        <p class="settings-note" id="pair-msg">${state.pairMsg || ''}</p>
      </div>
      <div class="settings-section">
        <h3>桌面行为</h3>
        <div class="toggle-row">
          <div class="toggle-info">
            <div class="t-title">托盘常驻</div>
            <div class="t-desc">关闭窗口时保留系统托盘图标</div>
          </div>
          <label class="toggle">
            <input type="checkbox" id="tray-toggle" ${p.trayEnabled ? 'checked' : ''} />
            <span class="toggle-track"></span>
          </label>
        </div>
        <div class="toggle-row">
          <div class="toggle-info">
            <div class="t-title">开机自启</div>
            <div class="t-desc">登录系统后自动启动 ListenStat</div>
          </div>
          <label class="toggle">
            <input type="checkbox" id="autostart-toggle" ${p.autostart ? 'checked' : ''} />
            <span class="toggle-track"></span>
          </label>
        </div>
      </div>
      <div class="settings-section">
        <h3>本地数据</h3>
        <p class="settings-note" style="margin-top:0;margin-bottom:12px">
          早期版本会写入演示曲目（如「起风了」）。可一键清空本机记录；不会删除云端账号。
        </p>
        <button type="button" class="btn-primary" id="btn-clear-local">清空本机听时记录</button>
        <p class="settings-note" id="clear-local-msg"></p>
      </div>
      <div class="settings-section">
        <h3>关于</h3>
        <p class="settings-note">ListenStat（听时）桌面端 v0.2.5<br/>在 Windows 上通过系统媒体控件统计收听时长；云端同步可选。</p>
      </div>
    </div>
  `;
}

const RENDERERS = {
  overview: renderOverview,
  rank: renderRank,
  detail: renderDetail,
  achievements: renderAchievements,
  settings: renderSettings,
};

function render() {
  const el = document.getElementById('content');
  el.innerHTML = RENDERERS[state.view]();
  bindViewEvents();
  updateNowMini();
}

function bindViewEvents() {
  document.querySelectorAll('[data-app]').forEach((node) => {
    node.addEventListener('click', () => {
      state.selectedApp = node.getAttribute('data-app');
      state.view = 'detail';
      syncNav();
      render();
    });
  });

  document.querySelectorAll('[data-select-app]').forEach((node) => {
    node.addEventListener('click', () => {
      state.selectedApp = node.getAttribute('data-select-app');
      render();
    });
  });

  const prev = document.getElementById('day-prev');
  const next = document.getElementById('day-next');
  if (prev) prev.addEventListener('click', () => { state.dayOffset -= 1; render(); });
  if (next) next.addEventListener('click', () => { if (state.dayOffset < 0) { state.dayOffset += 1; render(); } });

  const saveProfile = document.getElementById('btn-save-profile');
  if (saveProfile) {
    saveProfile.addEventListener('click', async () => {
      const name = document.getElementById('display-name')?.value || '';
      if (api?.setProfile) {
        const d = await api.setProfile({ displayName: name });
        state.data = d;
        render();
      }
    });
  }

  const syncToggle = document.getElementById('sync-toggle');
  if (syncToggle) {
    syncToggle.addEventListener('change', async () => {
      if (!api?.setSyncEnabled) return;
      state.busy = true;
      const res = await api.setSyncEnabled(syncToggle.checked);
      state.data = res.data || res;
      state.busy = false;
      if (res.ok === false) state.pairMsg = res.error || '同步失败';
      render();
    });
  }

  const syncNow = document.getElementById('btn-sync-now');
  if (syncNow) {
    syncNow.addEventListener('click', async () => {
      if (!api?.syncNow) return;
      const res = await api.syncNow();
      state.data = res.data || state.data;
      state.pairMsg = res.ok ? '同步完成' : (res.reason || '同步失败');
      render();
    });
  }

  const pairCreate = document.getElementById('btn-pair-create');
  if (pairCreate) {
    pairCreate.addEventListener('click', async () => {
      if (!api?.pairCreate) {
        state.pairMsg = '当前为浏览器预览，请在 Electron 中使用配对';
        render();
        return;
      }
      const res = await api.pairCreate();
      if (res.ok) {
        state.pairCode = res.code;
        state.pairExpires = res.expiresAt;
        state.pairMsg = '请在另一台设备输入此配对码';
      } else {
        state.pairMsg = res.error || '生成失败';
      }
      render();
    });
  }

  const pairClaim = document.getElementById('btn-pair-claim');
  if (pairClaim) {
    pairClaim.addEventListener('click', async () => {
      const code = document.getElementById('pair-code-input')?.value || '';
      if (!api?.pairClaim) {
        state.pairMsg = '当前为浏览器预览，请在 Electron 中使用配对';
        render();
        return;
      }
      const res = await api.pairClaim(code);
      if (res.ok) {
        state.data = res.data;
        state.pairMsg = `配对成功${res.merged ? `，合并 ${res.merged} 条记录` : ''}`;
        state.pairCode = null;
      } else {
        state.pairMsg = res.error || '配对失败';
      }
      render();
    });
  }

  const trayToggle = document.getElementById('tray-toggle');
  if (trayToggle) {
    trayToggle.addEventListener('change', async () => {
      if (api?.setProfile) {
        state.data = await api.setProfile({ trayEnabled: trayToggle.checked });
      }
    });
  }
  const autoToggle = document.getElementById('autostart-toggle');
  if (autoToggle) {
    autoToggle.addEventListener('change', async () => {
      if (api?.setProfile) {
        state.data = await api.setProfile({ autostart: autoToggle.checked });
      }
    });
  }

  document.getElementById('btn-clear-local')?.addEventListener('click', async () => {
    if (!api?.clearLocalRecords) return;
    if (!confirm('确定清空本机全部听时记录？此操作不可撤销。')) return;
    state.busy = true;
    const msg = document.getElementById('clear-local-msg');
    try {
      await api.clearLocalRecords();
      state.data = await api.getData();
      if (msg) msg.textContent = '已清空本机记录';
      render();
    } catch (e) {
      if (msg) msg.textContent = '清空失败：' + (e.message || e);
    } finally {
      state.busy = false;
    }
  });
}

function syncNav() {
  document.querySelectorAll('.nav-item').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.view === state.view);
  });
}

function updateNowMini() {
  const mini = document.getElementById('now-mini');
  const np = data().nowPlaying;
  if (!mini) return;
  if (np && np.state !== 'idle' && np.title) {
    mini.classList.add('playing');
    mini.querySelector('.now-mini-label').textContent = np.app || '播放中';
    mini.querySelector('.now-mini-sub').textContent = np.title;
  } else {
    const recent = (data().recent || [])[0];
    if (recent) {
      mini.classList.add('playing');
      mini.querySelector('.now-mini-label').textContent = recent.app;
      mini.querySelector('.now-mini-sub').textContent = recent.title;
    } else {
      mini.classList.remove('playing');
      mini.querySelector('.now-mini-label').textContent = '空闲';
      mini.querySelector('.now-mini-sub').textContent = '无正在播放';
    }
  }
}

async function loadInitial() {
  if (api?.getData) {
    try {
      state.data = await api.getData();
      if (!state.selectedApp && state.data?.ranking?.[0]) {
        state.selectedApp = state.data.ranking[0].app;
      }
    } catch {
      state.data = emptyDashboard();
    }
  } else {
    state.data = emptyDashboard();
    state.selectedApp = null;
  }
  if (api?.onData) {
    api.onData((d) => {
      state.data = d;
      if (state.view !== 'settings' || !state.pairCode) render();
      else updateNowMini();
    });
  }
}

function init() {
  document.querySelectorAll('.nav-item').forEach((btn) => {
    btn.addEventListener('click', () => {
      state.view = btn.dataset.view;
      syncNav();
      render();
    });
  });

  document.querySelectorAll('#period-pills .pill').forEach((btn) => {
    btn.addEventListener('click', () => {
      state.period = btn.dataset.period;
      document.querySelectorAll('#period-pills .pill').forEach((b) => b.classList.toggle('active', b === btn));
      render();
    });
  });

  document.querySelectorAll('#device-pills .pill').forEach((btn) => {
    btn.addEventListener('click', () => {
      state.device = btn.dataset.device;
      document.querySelectorAll('#device-pills .pill').forEach((b) => b.classList.toggle('active', b === btn));
      render();
    });
  });

  const search = document.getElementById('search');
  let timer = null;
  search.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      state.search = search.value;
      render();
    }, 120);
  });

  const params = new URLSearchParams(location.search);
  const viewParam = params.get('view');
  if (viewParam && RENDERERS[viewParam]) {
    state.view = viewParam;
    syncNav();
  }

  window.__listenstat = {
    setView(v) {
      if (RENDERERS[v]) {
        state.view = v;
        syncNav();
        render();
      }
    },
    getView: () => state.view,
  };

  loadInitial().then(render);
}

init();
