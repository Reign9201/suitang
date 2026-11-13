'use strict';

// ---------- 小工具 ----------
const $ = (sel, root = document) => root.querySelector(sel);
const enc = encodeURIComponent;
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const store = {
  get(key, fallback) {
    try { return JSON.parse(localStorage.getItem('bs.' + key)) ?? fallback; } catch { return fallback; }
  },
  set(key, value) { localStorage.setItem('bs.' + key, JSON.stringify(value)); },
};

const SUBJECT_COLORS = {
  语文: '#B5443B', 数学: '#2E64A8', 英语: '#6B4E9E', 物理: '#1E7F80', 化学: '#9A5E17',
  生物: '#427A39', 历史: '#875A3D', 地理: '#33789A', 道德与法治: '#A23C66', 政治: '#A23C66', 科学: '#54792A',
};
const SPINE_HEIGHTS = [152, 144, 158, 140, 150, 146, 156, 142, 150];
// 随堂的标：田字格里一个播放键（黑板上用，不带底色；带底色的完整图标见 logo.svg）
const MARK = `<svg class="mark" viewBox="0 0 64 64" aria-hidden="true"><rect x="4" y="4" width="56" height="56" rx="1.5" fill="none" stroke="currentColor" stroke-width="3"/><path d="M32 8.5v47M8.5 32h47" stroke="currentColor" stroke-opacity=".55" stroke-width="1.4" stroke-linecap="round" stroke-dasharray="2.5 2.3"/><path d="M26.2 21.2v21.6L44.9 32z" fill="#F4C24D" stroke="#F4C24D" stroke-width="5" stroke-linejoin="round"/></svg>`;
const PLAY = '<svg aria-hidden="true" width="14" height="14" viewBox="0 0 14 14"><path d="M3.5 2.2v9.6L11.6 7z" fill="currentColor" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/></svg>';

function fmtDur(sec) {
  sec = Math.max(0, Math.round(sec || 0));
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  const pad = (n) => String(n).padStart(2, '0');
  return h ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}
function fmtHours(sec) {
  if (sec >= 3600) return `${Math.round(sec / 360) / 10} 小时`;
  return `${Math.max(1, Math.round(sec / 60))} 分钟`;
}
function fmtCount(n) {
  if (n >= 1e8) return `${(n / 1e8).toFixed(1).replace(/\.0$/, '')} 亿`;
  if (n >= 1e4) return `${(n / 1e4).toFixed(1).replace(/\.0$/, '')} 万`;
  return String(n || 0);
}
function thumb(url, w = 336, h = 189) {
  return /hdslb\.com/.test(url) ? `${url}@${w}w_${h}h_1c.webp` : url;
}

// ---------- 数据 ----------
async function api(path, params) {
  const res = await fetch(`${path}?${new URLSearchParams(params)}`);
  const body = await res.json().catch(() => ({ error: '本地服务没有响应，确认 server.py 在运行' }));
  if (!res.ok) throw new Error(body.error || `请求失败（HTTP ${res.status}）`);
  return body;
}
const memo = new Map();
function cached(path, params) {
  const key = path + JSON.stringify(params);
  if (!memo.has(key)) memo.set(key, api(path, params).catch((e) => { memo.delete(key); throw e; }));
  return memo.get(key);
}

let TAX = null;
const gradeOf = (n) => TAX.stages.flatMap((s) => s.grades).find((g) => g.n === n);

// ---------- 本机学习数据：同步集合，和 iPad、Android 同一格式（docs/sync-format.md） ----------
const LIMITS = { history: 100, progress: 3000, watched: 3000 };

/** 每个键各自按时间决定；删除时间不早于最新修改时删掉并保留墓碑；墓碑 180 天后忘掉。和 sync.py、iOS、Android 一致。 */
function mergeSets(sets, now) {
  const items = {}, removed = {};
  for (const s of sets) {
    if (!s) continue;
    for (const [k, item] of Object.entries(s.items || {})) if (item && item.at > (items[k]?.at ?? -Infinity)) items[k] = item;
    for (const [k, at] of Object.entries(s.removals || {})) if (at > (removed[k] ?? -Infinity)) removed[k] = at;
  }
  const kept = Object.fromEntries(Object.entries(items).filter(([k, v]) => v.at > (removed[k] ?? -Infinity)));
  const removals = Object.fromEntries(Object.entries(removed).filter(([k, at]) => now - at < 180 * 24 * 3600e3 && !(k in kept)));
  return { items: kept, removals };
}

/** 旧版原型把选择、最近在学、看完的节、进度分开存；第一次打开新版时转成同步集合。 */
function migrate() {
  const now = Date.now();
  const sets = {};
  const put = (name, key, data, at = now) => { (sets[name] ||= { items: {}, removals: {} }).items[key] = { at, data }; };
  const old = store.get('prefs', null);
  if (old) {
    if (old.grade) put('prefs', 'grade', old.grade);
    Object.entries(old.subject || {}).forEach(([g, v]) => put('prefs', `subject|${g}`, v));
    Object.entries(old.volume || {}).forEach(([k, v]) => put('prefs', `volume|${k}`, v));
    Object.entries(old.edition || {}).forEach(([k, v]) => v && put('prefs', `edition|${k}`, v));
  }
  for (const h of store.get('history', [])) {
    const [g, subject] = (h.from || '').split('/');
    put('history', h.bvid, { bvid: h.bvid, title: h.title, cover: h.cover, author: h.author, lesson: h.lesson, index: h.idx, part: h.part,
      total: h.total, grade: +g || null, subject: +g ? subject : null, volume: null }, h.at || now);
  }
  Object.entries(store.get('watched', {})).forEach(([b, keys]) => keys.forEach((k) => put('watched', `${b}|${k}`, true)));
  Object.entries(store.get('positions', {})).forEach(([k, pos]) => pos > 0 && put('progress', k, { position: pos, duration: 0 }));
  ['prefs', 'history', 'watched', 'positions'].forEach((k) => localStorage.removeItem('bs.' + k));
  store.set('sets', sets);
  return sets;
}

const lib = {
  sets: store.get('sets', null) || migrate(),
  save() { store.set('sets', this.sets); },
  set(name) { return (this.sets[name] ||= { items: {}, removals: {} }); },
  update(name, change, urgent = false) {
    const s = this.set(name);
    change(s);
    const limit = LIMITS[name];
    const keys = Object.keys(s.items);
    if (limit && keys.length > limit) keys.sort((a, b) => s.items[b].at - s.items[a].at).slice(limit).forEach((k) => delete s.items[k]);
    this.save();
    if (urgent) syncer.schedule(3000); // 收藏变化后约 3 秒同步
  },
  list(name) { return Object.values(this.set(name).items).sort((a, b) => b.at - a.at).map((v) => v.data); },
  pref(key) { return this.sets.prefs?.items?.[key]?.data; },
  setPref(key, value) {
    if (JSON.stringify(this.pref(key)) === JSON.stringify(value)) return;
    this.update('prefs', (s) => { s.items[key] = { at: Date.now(), data: value }; });
  },
  isFavorite(bvid) { return !!this.sets.favorites?.items?.[bvid]; },
  toggleFavorite(course) {
    const now = Date.now();
    this.update('favorites', (s) => {
      if (s.items[course.bvid]) { delete s.items[course.bvid]; s.removals[course.bvid] = now; }
      else { s.items[course.bvid] = { at: now, data: course }; delete s.removals[course.bvid]; }
    }, true);
  },
  historyEntry(bvid) { return this.sets.history?.items?.[bvid]?.data; },
  record(entry) { this.update('history', (s) => { s.items[entry.bvid] = { at: Date.now(), data: entry }; }); },
  progress(bvid, key) { return this.sets.progress?.items?.[`${bvid}|${key}`]?.data; },
  setProgress(bvid, key, position, duration) {
    this.update('progress', (s) => { s.items[`${bvid}|${key}`] = { at: Date.now(), data: { position, duration } }; });
  },
  isWatched(bvid, key) { return !!this.sets.watched?.items?.[`${bvid}|${key}`]; },
  markWatched(bvid, key) {
    if (!this.isWatched(bvid, key)) this.update('watched', (s) => { s.items[`${bvid}|${key}`] = { at: Date.now(), data: true }; });
  },
  watchedCount(bvid) { return Object.keys(this.sets.watched?.items || {}).filter((k) => k.startsWith(bvid + '|')).length; },
};

/**
 * 多端同步：浏览器不能直接访问 WebDAV，由本地服务 /api/sync 转交（sync.py），账号只随请求带上。
 * 打开或切回页面时最多每分钟一次、收藏变化后约 3 秒、退出播放页时，以及手动「立即同步」。
 */
const syncer = {
  device: store.get('device', null) || (() => { const id = crypto.randomUUID().toUpperCase(); store.set('device', id); return id; })(),
  account: store.get('dav', null),
  lastAt: store.get('syncAt', 0),
  error: null,
  running: false,
  timer: 0,
  deviceName() {
    const ua = navigator.userAgent;
    const b = /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome' : /Firefox\//.test(ua) ? 'Firefox' : /Safari\//.test(ua) ? 'Safari' : '浏览器';
    const os = /Mac OS X/.test(ua) ? 'Mac' : /Windows/.test(ua) ? 'Windows' : /Android/.test(ua) ? 'Android' : /Linux/.test(ua) ? 'Linux' : '';
    return `网页 ${b}${os ? `（${os}）` : ''}`;
  },
  schedule(ms) { if (!this.account) return; clearTimeout(this.timer); this.timer = setTimeout(() => this.run(), ms); },
  runIfStale() { if (Date.now() - this.lastAt > 60e3) this.schedule(500); },
  async post(path, body) {
    const res = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const data = await res.json().catch(() => ({ error: '本地服务没有响应，确认 server.py 在运行' }));
    if (!res.ok) throw new Error(data.error || `同步失败（HTTP ${res.status}）`);
    return data;
  },
  /** 保存账号前先实际访问一次网盘，账号或密码不对直接报错。 */
  async login(account) {
    await this.post('/api/sync/check', { account });
    this.account = account;
    store.set('dav', account);
    this.error = null;
    await this.run();
  },
  logout() { this.account = null; this.error = null; localStorage.removeItem('bs.dav'); },
  async run() {
    if (!this.account || this.running) return;
    this.running = true;
    showSyncStatus();
    try {
      const r = await this.post('/api/sync', { account: this.account, device: this.device, deviceName: this.deviceName(), sets: lib.sets });
      // 请求期间本机又有改动时，和返回的结果再合并一次，改动不会丢
      const now = Date.now();
      const before = JSON.stringify(lib.sets);
      const names = new Set([...Object.keys(lib.sets), ...Object.keys(r.sets)]);
      lib.sets = Object.fromEntries([...names].map((n) => [n, mergeSets([lib.sets[n], r.sets[n]], now)]));
      lib.save();
      this.lastAt = r.syncedAt;
      store.set('syncAt', r.syncedAt);
      this.error = null;
      if (JSON.stringify(lib.sets) !== before) afterSync();
    } catch (e) {
      this.error = e.message;
    } finally {
      this.running = false;
      showSyncStatus();
    }
  },
  status() {
    if (!this.account) return '未登录，学习记录只保存在这个浏览器里';
    let host = this.account.url;
    try { host = new URL(this.account.url).host; } catch { /* 原样显示 */ }
    if (this.running) return `正在同步 ${host}`;
    if (this.error) return `同步失败：${this.error}`;
    if (!this.lastAt) return `已登录 ${host}，还没有同步过`;
    const d = new Date(this.lastAt), today = new Date().toDateString() === d.toDateString();
    const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
    return `已登录 ${host}，上次同步 ${today ? '今天' : `${d.getMonth() + 1} 月 ${d.getDate()} 日`} ${hm}`;
  },
};

function showSyncStatus() {
  document.querySelectorAll('[data-sync-status]').forEach((el) => {
    el.textContent = syncer.status();
    el.classList.toggle('error', !!syncer.error);
  });
  document.querySelectorAll('[data-sync-now]').forEach((b) => { b.disabled = syncer.running; });
}

/**
 * 同步拿到别的设备的数据后刷新当前页。播放页只更新目录的勾，不打断播放；
 * 书架和搜索结果原地更新书签和继续学习，不整页重画（重画会闪、滚动位置会跳）。
 */
function afterSync() {
  const { parts } = parseHash();
  if (parts[0] === 'play') { if (playing && $('.player')) drawToc(); return; }
  if (parts[0] === 'mine' || parts[0] === 'recent' || parts[0] === 'settings' || $('.welcome')) return render();
  document.querySelectorAll('[data-save]').forEach(paintSave);
  if (openShelf && $('#resume')) renderResume(openShelf.n, openShelf.subject);
}

function paintSave(button) {
  const on = lib.isFavorite(button.dataset.save);
  const title = (courseIndex.get(button.dataset.save) || {}).title || '';
  button.classList.toggle('on', on);
  button.setAttribute('aria-pressed', on);
  button.setAttribute('aria-label', `${on ? '取消收藏' : '收藏'}：${String(title).slice(0, 24)}`);
  if (button.classList.contains('btn')) button.querySelector('.label').textContent = on ? '已收藏' : '收藏';
}

function progressOf(bvid) {
  const total = lib.historyEntry(bvid)?.total;
  const seen = lib.watchedCount(bvid);
  return total && seen ? { seen, total } : null;
}

/** 课程行用到的收藏快照：bvid → 收藏时要存的字段。 */
const courseIndex = new Map();
function remember(it, from) {
  const [n, subject] = (from || '').split('/');
  courseIndex.set(it.bvid, {
    bvid: it.bvid, title: it.title, cover: it.cover, author: it.author, duration: it.duration || 0, episodes: it.episodes || 0,
    grade: +n || it.grade || null, subject: +n ? subject : (it.subject || null), volume: it.volume || null,
  });
}

const BOOKMARK = '<svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><path d="M6 3.5h12v17l-6-4.2-6 4.2z"></path></svg>';
function saveButton(bvid, title) {
  const on = lib.isFavorite(bvid);
  return `<button class="save${on ? ' on' : ''}" data-save="${esc(bvid)}" aria-pressed="${on}" aria-label="${on ? '取消收藏' : '收藏'}：${esc(String(title).slice(0, 24))}">${BOOKMARK}</button>`;
}

document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-save]');
  if (!b) return;
  const course = courseIndex.get(b.dataset.save) || lib.sets.favorites?.items?.[b.dataset.save]?.data;
  if (!course) return;
  courseIndex.set(course.bvid, course);
  lib.toggleFavorite(course);
  document.querySelectorAll(`[data-save="${CSS.escape(course.bvid)}"]`).forEach(paintSave);
});

// ---------- 路由 ----------
const view = $('#view');
const rail = $('#rail');
let token = 0;

function parseHash() {
  const raw = location.hash.slice(1) || '/';
  const [path, query = ''] = raw.split('?');
  return {
    parts: path.split('/').filter(Boolean).map(decodeURIComponent),
    q: Object.fromEntries(new URLSearchParams(query)),
  };
}

function render() {
  const { parts, q } = parseHash();
  $('.recent-link').classList.toggle('on', parts[0] === 'mine');
  $('.settings-link').classList.toggle('on', parts[0] === 'settings');
  if (parts[0] !== 'search') $('#search').q.value = '';
  if (parts[0] === 'play' && parts[1]) return renderPlayer(parts[1], q);
  if (parts[0] === 'search') return renderSearch(q.q || '', +q.page || 1);
  if (parts[0] === 'mine' || parts[0] === 'recent') return renderMine();
  if (parts[0] === 'settings') return renderSettings();
  if (lib.pref('grade') == null && !parts.length) return renderWelcome();
  const saved = lib.pref('grade');
  const n = gradeOf(+parts[1]) ? +parts[1] : (gradeOf(saved) ? saved : 7);
  const g = gradeOf(n);
  const want = parts[2] || lib.pref(`subject|${n}`);
  const subject = g.subjects.some((s) => s.name === want) ? want : g.subjects[0].name;
  return renderCatalog(n, subject, q);
}

function renderRail(activeN) {
  rail.innerHTML = `<a class="brand" href="#/" aria-label="随堂 首页">${MARK}<span>随堂</span></a>` + TAX.stages.map((stage) => `
    <section class="stage">
      <h2>${stage.name}</h2>
      <div class="grades">${stage.grades.map((g) => `
        <a class="grade${g.n > 9 ? ' wide' : ''}${g.n === activeN ? ' on' : ''}" href="#/grade/${g.n}"
           title="${g.name}"${g.n === activeN ? ' aria-current="page"' : ''}>${g.short}</a>`).join('')}
      </div>
    </section>`).join('');
  // 手机上年级是一行横向滚动，把当前年级滚到可见处
  const on = rail.querySelector('.grade.on');
  if (on && rail.scrollWidth > rail.clientWidth) {
    rail.scrollLeft = on.offsetLeft - (rail.clientWidth - on.offsetWidth) / 2;
  }
}

// ---------- 分类页 ----------
function volumeFor(n, subject) {
  const subj = gradeOf(n).subjects.find((s) => s.name === subject);
  const saved = lib.pref(`volume|${n}|${subject}`);
  return subj.volumes.some((v) => v.name === saved) ? saved : subj.volumes[0].name;
}

function spines(n, active) {
  return gradeOf(n).subjects.map((s, i) => {
    const vol = s.volumes.find((v) => v.name === volumeFor(n, s.name));
    const on = s.name === active;
    return `<a class="spine${on ? ' on' : ''}" href="#/grade/${n}/${enc(s.name)}"
      style="--c:${SUBJECT_COLORS[s.name]};--h:${SPINE_HEIGHTS[i % SPINE_HEIGHTS.length]}px"
      ${on ? 'aria-current="page"' : ''} aria-label="${s.name}">
      <span class="spine-name${s.name.length > 2 ? ' long' : ''}">${s.name}</span>
      <span class="spine-vol">${vol.short}</span>
    </a>`;
  }).join('');
}

function ghostRows(count = 4) {
  return Array.from({ length: count }, () => '<div class="ghost-row"><i></i><div><span></span><span></span><span></span></div></div>').join('');
}

let openShelf = null; // 当前书架的年级和学科，同步后原地更新继续学习卡片用
async function renderCatalog(n, subject, q) {
  const my = ++token;
  openShelf = { n, subject };
  const g = gradeOf(n);
  const subj = g.subjects.find((s) => s.name === subject);
  if (q.vol && subj.volumes.some((v) => v.name === q.vol)) lib.setPref(`volume|${n}|${subject}`, q.vol);
  const vol = volumeFor(n, subject);
  lib.setPref('grade', n);
  lib.setPref(`subject|${n}`, subject);
  renderRail(n);

  view.innerHTML = `
    <nav class="shelf" aria-label="学科">${spines(n, subject)}<span class="shelf-label">${g.name}</span></nav>
    <div class="filters">
      ${subj.volumes.length > 1 ? `<div class="seg">${subj.volumes.map((v) => `
        <a href="#/grade/${n}/${enc(subject)}?vol=${enc(v.name)}" class="${v.name === vol ? 'on' : ''}">${v.name}</a>`).join('')}</div>` : ''}
      <div class="editions" id="editions"></div>
      <div class="editions" id="kinds"></div>
    </div>
    <div id="resume"></div>
    <div id="list">${ghostRows()}</div>`;
  const shelf = $('.shelf'), on = $('.spine.on');
  if (shelf && on && shelf.scrollWidth > shelf.clientWidth) {
    shelf.scrollLeft = on.offsetLeft - shelf.offsetLeft - (shelf.clientWidth - on.offsetWidth) / 2;
  }
  renderResume(n, subject);

  try {
    const params = { grade: n, subject, volume: vol };
    const data = await cached('/api/catalog', params);
    if (my !== token) return;
    renderCatalogList(data, n, subject, params);
  } catch (e) {
    if (my !== token) return;
    $('#list').innerHTML = `<div class="notice">${esc(e.message)}<br><button class="btn" id="retry">重试</button></div>`;
    $('#retry').onclick = () => render();
  }
}

function renderResume(n, subject) {
  const h = lib.list('history').find((x) => x.grade === n && x.subject === subject);
  if (!h) return;
  const seen = lib.watchedCount(h.bvid);
  $('#resume').innerHTML = `
    <a class="resume" href="${playHref(h.bvid, h.lesson, `${n}/${subject}`)}">
      <span class="resume-cover">${h.cover ? `<img src="${thumb(h.cover)}" alt="" referrerpolicy="no-referrer">` : ''}</span>
      <span class="what">
        <b>${esc(h.part)}</b>
        <span class="course-name">${esc(h.title)}</span>
        <span class="progress"><span class="bar"><i style="width:${Math.min(100, Math.round(seen / Math.max(1, h.total) * 100))}%"></i></span>已看完 ${seen} / ${h.total} 节</span>
      </span>
      <span class="go btn primary">${PLAY}继续第 ${h.index + 1} 节</span>
    </a>`;
}

function renderCatalogList(data, n, subject, params) {
  const my = token;
  const ai = data.ai || {};
  const count = (key) => {
    const c = {};
    data.items.forEach((it) => [].concat(key(it) || []).forEach((v) => { c[v] = (c[v] || 0) + 1; }));
    return c;
  };
  const edCounts = count((it) => it.editions);
  // 只出现一次的版本多半是标题顺带提到，不单独给筛选项
  const editions = Object.entries(edCounts).filter(([, c]) => c >= 2).sort((a, b) => b[1] - a[1]);
  const kinds = Object.entries(count((it) => it.ai?.kind)).sort((a, b) => b[1] - a[1]);
  const picks = new Set(data.picks || []);
  let ed = edCounts[lib.pref(`edition|${subject}`)] ? lib.pref(`edition|${subject}`) : '';
  let kind = '';

  const chips = (list, current, attr) => list.map(([name, c]) => `
    <button class="chip${name === current ? ' on' : ''}" data-${attr}="${esc(name)}"${name === current ? ' aria-pressed="true"' : ''}>${esc(name) || '全部'}<b>${c}</b></button>`).join('');

  const draw = () => {
    // 版本是单选（含「全部」）；内容类型是可取消的开关，避免两组都出现「全部」
    $('#editions').innerHTML = editions.length ? chips([['', data.items.length], ...editions], ed, 'ed') : '';
    $('#kinds').innerHTML = kinds.length > 1 ? chips(kinds, kind, 'kind') : '';
    let items = data.items;
    if (ed) items = items.filter((it) => it.editions.includes(ed));
    if (kind) items = items.filter((it) => it.ai?.kind === kind);
    const top = items.filter((it) => picks.has(it.bvid));
    const rest = items.filter((it) => !picks.has(it.bvid));
    const from = `${n}/${subject}`;
    const section = (title, list, isPick) => list.length ? `
      <h3 class="sec">${title}<span class="n">${list.length}</span></h3>
      ${isPick ? `<ol class="cards">${list.map((it) => pickCard(it, from)).join('')}</ol>`
        : `<ol class="courses">${list.map((it) => courseRow(it, from)).join('')}</ol>`}` : '';
    const rejected = data.rejected.reduce((sum, r) => sum + r.count, 0);
    const aiNote = ai.status === 'ready' ? `<p>大模型整理：${esc(ai.model)}</p>`
      : ai.status === 'error' ? `<p>大模型整理没有完成：${esc(ai.message)}</p>`
      : ai.status === 'off' ? `<p>未启用大模型整理（${esc(ai.message)}），当前只按标题规则归类。</p>` : '';
    $('#list').innerHTML = (ai.status === 'pending' ? '<p class="ai-status">大模型整理中</p>' : '')
      + (items.length
        ? section('推荐', top, true) + section('整套课程', rest.filter((it) => it.series)) + section('单个讲解', rest.filter((it) => !it.series))
        : '<div class="notice">这个分类下还没有找到合适的视频。</div>')
      + `<details class="filtered">
        <summary>${rejected ? `已过滤 ${rejected} 条` : '整理说明'}</summary>
        ${rejected ? `<ul>${data.rejected.map((r) => `<li><b>${esc(r.reason)}</b>${r.count} 条
          ${r.samples.length ? `<span class="eg">${r.samples.map(esc).join('；')}</span>` : ''}</li>`).join('')}</ul>` : ''}
        <p>搜索词：${data.queries.map(esc).join('、')}</p>${aiNote}
      </details>`;
  };
  draw();
  $('.filters').onclick = (e) => {
    const chip = e.target.closest('.chip');
    if (!chip) return;
    if ('ed' in chip.dataset) {
      ed = chip.dataset.ed;
      lib.setPref(`edition|${subject}`, ed);
    } else {
      kind = kind === chip.dataset.kind ? '' : chip.dataset.kind;
    }
    draw();
  };

  // 大模型在后台整理，好了以后整页换成整理后的结果
  if (ai.status === 'pending') {
    setTimeout(async () => {
      if (my !== token) return;
      try {
        const fresh = await api('/api/catalog', params);
        if (my !== token) return;
        memo.set('/api/catalog' + JSON.stringify(params), Promise.resolve(fresh));
        renderCatalogList(fresh, n, subject, params);
      } catch { /* 下次进入这个分类再取 */ }
    }, 3000);
  }
}

function courseTags(it, extraTags = '') {
  const ai = it.ai;
  return [
    extraTags,
    ai ? `<span class="tag kind">${esc(ai.kind)}</span>` : '',
    ai && ai.level !== '中等' ? `<span class="tag">${esc(ai.level)}</span>` : '',
    ai?.fit === '相关' ? '<span class="tag">可参考</span>' : '',
    it.editions[0] ? `<span class="tag">${esc(it.editions[0])}</span>` : '',
    it.new_textbook ? '<span class="tag new">新教材</span>' : '',
    !ai && it.review ? '<span class="tag review">复习</span>' : '',
  ].join('');
}

/** 推荐：大封面卡片，封面底部是看完的进度。 */
function pickCard(it, from) {
  const prog = progressOf(it.bvid);
  const ai = it.ai;
  remember(it, from);
  return `<li class="card"><a class="card-link" href="${playHref(it.bvid, null, from)}">
    <span class="thumb"><img loading="lazy" src="${thumb(it.cover, 480, 270)}" alt="" referrerpolicy="no-referrer">
      <span class="len" aria-hidden="true">${fmtDur(it.duration)}</span>
      ${prog ? `<span class="bar" title="已看 ${prog.seen} 节，共 ${prog.total} 节"><i style="width:${Math.min(100, Math.round(prog.seen / prog.total * 100))}%"></i></span>` : ''}</span>
    <span class="card-body">
      <span class="title">${esc(it.title)}</span>
      ${ai?.comment ? `<span class="comment">${esc(ai.comment)}</span>` : ''}
      ${ai?.summary ? `<span class="summary">${esc(ai.summary)}</span>` : ''}
      <span class="meta"><span>${esc(it.author)}</span>${it.episodes ? `<span>${it.episodes} 集</span>` : ''}<span>${fmtCount(it.play)} 次播放</span></span>
      <span class="tags">${courseTags(it)}</span>
    </span>
  </a>${saveButton(it.bvid, it.title)}</li>`;
}

function courseRow(it, from, extraTags = '') {
  const prog = progressOf(it.bvid);
  const ai = it.ai;
  const tags = courseTags(it, extraTags);
  remember(it, from);
  return `<li class="course-row"><a class="course" href="${playHref(it.bvid, null, from)}">
    <span class="thumb"><img loading="lazy" src="${thumb(it.cover)}" alt="" referrerpolicy="no-referrer"><span class="len" aria-hidden="true">${fmtDur(it.duration)}</span></span>
    <span class="info">
      <span class="title">${esc(it.title)}</span>
      ${ai?.summary ? `<span class="summary">${esc(ai.summary)}</span>` : ''}
      <span class="meta"><span>${esc(it.author)}</span>${it.episodes ? `<span>${it.episodes} 集</span>` : ''}<span>${fmtCount(it.play)} 次播放</span></span>
      <span class="tags">${tags}</span>
    </span>
    ${prog ? `<span class="prog" title="已看 ${prog.seen} 节，共 ${prog.total} 节">✓ <b>${prog.seen}</b>/${prog.total}</span>` : ''}
  </a>${saveButton(it.bvid, it.title)}</li>`;
}

// ---------- 首次打开：选年级 ----------
function renderWelcome() {
  ++token;
  renderRail(0);
  view.innerHTML = `<section class="welcome">
    <img class="welcome-logo" src="logo.svg" alt="随堂">
    <h1 class="page-h">你在读几年级？</h1>
    ${TAX.stages.map((stage) => `<div class="welcome-stage">
      <h2>${stage.name}</h2>
      <div class="welcome-grades">${stage.grades.map((g) => `
        <a class="grade big${g.n > 9 ? ' wide' : ''}" href="#/grade/${g.n}" title="${g.name}">${g.short}</a>`).join('')}</div>
    </div>`).join('')}
  </section>`;
}

// ---------- 搜索 ----------
$('#search').addEventListener('submit', (e) => {
  e.preventDefault();
  const q = e.target.q.value.trim();
  if (q) location.hash = `#/search?q=${enc(q)}`;
});

async function renderSearch(q, page) {
  const my = ++token;
  $('#search').q.value = q;
  renderRail(0);
  view.innerHTML = `<p class="hint">只搜索 B 站校园学习分区</p><div id="list">${ghostRows()}</div>`;
  try {
    const pages = [];
    for (let p = 1; p <= page; p++) pages.push(await cached('/api/search', { q, page: p, grade: lib.pref('grade') || 0 }));
    if (my !== token) return;
    // 大模型把这句话理解成了哪个年级、学科、知识点；能对上分类时给一个直达入口
    const intent = pages[0].intent;
    if (intent && !intent.error) {
      const labels = [intent.gradeName, intent.subject, intent.volume, intent.topic].filter(Boolean);
      const target = intent.grade && intent.subject
        ? `<a href="#/grade/${intent.grade}/${enc(intent.subject)}${intent.volume ? `?vol=${enc(intent.volume)}` : ''}">打开${esc(intent.gradeName + intent.subject + intent.volume)}</a>` : '';
      $('.hint').outerHTML = `<div class="intent">${labels.map((l) => `<span class="tag">${esc(l)}</span>`).join('')}${target}</div>
        <p class="hint">只搜索 B 站校园学习分区${intent.query && intent.query !== q ? `，搜索词「${esc(intent.query)}」` : ''}</p>`;
    }
    const items = pages.flatMap((d) => d.items);
    const last = pages[pages.length - 1];
    const from = `search:${q}`;
    const where = (it) => {
      const g = it.grades.length === 1 ? gradeOf(it.grades[0]) : null;
      const label = [g?.name, it.subjects.length === 1 ? it.subjects[0] : '', it.volumes[0] || ''].filter(Boolean).join(' ');
      return label ? `<span class="tag where">${esc(label)}</span>` : '';
    };
    $('#list').innerHTML = items.length ? `
      <ol class="courses" style="margin-top:12px">${items.map((it) => courseRow(it, from, where(it))).join('')}</ol>
      ${last.page < last.pages ? '<button class="btn more" id="more">加载更多</button>' : ''}`
      : `<div class="notice">没有找到「${esc(q)}」相关的学习视频，换个说法试试，比如加上年级或课本版本。</div>`;
    $('#more')?.addEventListener('click', () => { location.hash = `#/search?q=${enc(q)}&page=${page + 1}`; });
  } catch (e) {
    if (my !== token) return;
    $('#list').innerHTML = `<div class="notice">${esc(e.message)}<br><button class="btn" id="retry">重试</button></div>`;
    $('#retry').onclick = () => render();
  }
}

// ---------- 我的：继续学习、收藏、同步 ----------
function renderMine() {
  ++token;
  renderRail(0);
  const history = lib.list('history').slice(0, 6);
  const favorites = lib.list('favorites');
  const syncBox = syncer.account
    ? `<span class="sync-status" data-sync-status></span><button class="btn" data-sync-now>立即同步</button>`
    : `<a class="btn" href="#/settings">登录网盘同步</a>`;
  view.innerHTML = `
    <div class="page-head"><h1 class="page-h">我的</h1><div class="page-tools">${syncBox}</div></div>
    ${history.length ? `<h2 class="sec">继续学习</h2>
      <ol class="courses">${history.map((h) => {
        const from = h.grade && h.subject ? `${h.grade}/${h.subject}` : 'mine';
        const where = [gradeOf(h.grade)?.name, h.subject, h.volume].filter(Boolean).join(' ');
        return `<li class="course-row"><a class="course" href="${playHref(h.bvid, h.lesson, from)}">
          <span class="thumb">${h.cover ? `<img loading="lazy" src="${thumb(h.cover)}" alt="" referrerpolicy="no-referrer">` : ''}</span>
          <span class="info">
            <span class="title">第 ${h.index + 1} 节　${esc(h.part)}</span>
            <span class="meta"><span>${esc(h.title)}</span></span>
            ${where ? `<span class="tags"><span class="tag where">${esc(where)}</span></span>` : ''}
          </span>
          ${lib.watchedCount(h.bvid) ? `<span class="prog">✓ <b>${lib.watchedCount(h.bvid)}</b>/${h.total}</span>` : ''}
        </a></li>`;
      }).join('')}</ol>` : ''}
    <h2 class="sec">收藏<span class="n">${favorites.length}</span></h2>
    ${favorites.length ? `<ol class="courses">${favorites.map((f) => {
      const from = f.grade && f.subject ? `${f.grade}/${f.subject}` : 'mine';
      const where = [gradeOf(f.grade)?.name, f.subject].filter(Boolean).join(' ');
      courseIndex.set(f.bvid, f);
      return `<li class="course-row"><a class="course" href="${playHref(f.bvid, null, from)}">
        <span class="thumb">${f.cover ? `<img loading="lazy" src="${thumb(f.cover)}" alt="" referrerpolicy="no-referrer">` : ''}${f.duration ? `<span class="len" aria-hidden="true">${fmtDur(f.duration)}</span>` : ''}</span>
        <span class="info">
          <span class="title">${esc(f.title)}</span>
          <span class="meta"><span>${esc(f.author)}</span>${f.episodes > 1 ? `<span>${f.episodes} 集</span>` : ''}</span>
          ${where ? `<span class="tags"><span class="tag where">${esc(where)}</span></span>` : ''}
        </span>
      </a>${saveButton(f.bvid, f.title)}</li>`;
    }).join('')}</ol>` : '<div class="notice">在课程旁点书签就能收藏，换设备登录同一个网盘后也能看到。</div>'}`;
  showSyncStatus();
  $('[data-sync-now]')?.addEventListener('click', () => syncer.run());
}

// ---------- 设置：多端同步 ----------
function renderSettings() {
  ++token;
  renderRail(0);
  const a = syncer.account || {};
  view.innerHTML = `
    <h1 class="page-h">设置</h1>
    <form class="card sync-form" id="sync-form">
      <h2 class="sec">多端同步</h2>
      <p class="sync-status" data-sync-status></p>
      <label>WebDAV 地址<input name="url" value="${esc(a.url || 'https://dav.jianguoyun.com/dav/')}" autocomplete="off" spellcheck="false"></label>
      <label>账号<input name="user" value="${esc(a.user || '')}" autocomplete="username" spellcheck="false"></label>
      <label>应用密码<input name="password" type="password" value="${esc(a.password || '')}" autocomplete="current-password">
        <span class="field-hint">坚果云在网页版「账户信息 → 安全选项 → 第三方应用管理」生成，不是登录密码</span></label>
      <p class="field-hint">同步收藏、学习进度、最近在学，以及选过的年级和课本版本。账号只存在这个浏览器里。</p>
      <p class="form-error" id="sync-error" role="alert"></p>
      <div class="actions">
        <button class="btn primary" type="submit">保存并同步</button>
        ${syncer.account ? '<button class="btn plain" type="button" id="sync-stop">停止同步</button>' : ''}
      </div>
    </form>
    <p class="hint">大模型整理的设置在本机 config.json 里。</p>`;
  showSyncStatus();
  const form = $('#sync-form');
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = form.querySelector('[type=submit]');
    btn.disabled = true;
    btn.textContent = '正在连接…';
    $('#sync-error').textContent = '';
    try {
      await syncer.login({ url: form.url.value.trim(), user: form.user.value.trim(), password: form.password.value });
      location.hash = '#/mine';
    } catch (err) {
      $('#sync-error').textContent = err.message;
    } finally {
      btn.disabled = false;
      btn.textContent = '保存并同步';
    }
  });
  $('#sync-stop')?.addEventListener('click', () => { syncer.logout(); renderSettings(); });
}

// ---------- 播放页 ----------
function playHref(bvid, lesson, from) {
  const p = new URLSearchParams();
  if (lesson) p.set('l', lesson);
  if (from) p.set('from', from);
  return `#/play/${bvid}?${p}`;
}

function backLink(from) {
  if (from?.startsWith('search:')) return { href: `#/search?q=${enc(from.slice(7))}`, label: '搜索结果' };
  if (from === 'mine' || from === 'recent') return { href: '#/mine', label: '我的' };
  const [n, subject] = (from || '').split('/');
  const g = gradeOf(+n);
  if (g && subject) return { href: `#/grade/${n}/${enc(subject)}`, label: `${g.name} ${subject}` };
  return { href: '#/', label: '返回' };
}

// 分 P 视频用 p 区分每一节；B 站「合集」的每一节是独立的 BV 号
function lessonsOf(data) {
  if (data.pages.length > 1) {
    return data.pages.map((p) => ({ key: `p${p.page}`, bvid: data.bvid, cid: p.cid, p: p.page, part: p.part, duration: p.duration }));
  }
  if (data.season) {
    return data.season.episodes.map((e) => ({ key: e.bvid, bvid: e.bvid, cid: e.cid, p: 1, part: e.part, duration: e.duration }));
  }
  return [{ key: 'p1', bvid: data.bvid, cid: data.pages[0]?.cid, p: 1, part: data.title, duration: data.duration }];
}

const CN_NUM = '零一二三四五六七八九十';
function chapterOf(part) {
  let m = part.match(/第\s*([一二三四五六七八九十]+|\d+)\s*(章|单元|模块|专题)/);
  if (m) return `第${m[1]}${m[2]}`;
  m = part.match(/^\s*(?:Unit|UNIT|unit)\s*(\d+)/);
  if (m) return `Unit ${m[1]}`;
  // 「1.2.1 有理数」取 1；允许前面有「P3 」「03、」这样的序号
  m = part.match(/^\s*(?:P\d+[\s._-]+|\d+[\s、_-]+)?(\d{1,2})\.\d+/);
  if (m) return `第${+m[1] <= 10 ? CN_NUM[+m[1]] : m[1]}章`;
  return null;
}

function groupLessons(lessons) {
  const groups = [];
  let current = null, labelled = 0;
  lessons.forEach((lesson, i) => {
    const label = chapterOf(lesson.part);
    if (label) labelled++;
    if (!current || (label && label !== current.label)) {
      current = { label, items: [] };
      groups.push(current);
    }
    current.items.push(i);
  });
  if (labelled < lessons.length * 0.5 || groups.filter((g) => g.label).length < 2) {
    return [{ label: null, items: lessons.map((_, i) => i) }];
  }
  return groups;
}

// 每一节看到哪里；看过 90% 算看完，目录里打红勾
const SPEEDS = [1, 1.25, 1.5, 2];
let speed = store.get('speed', 1);
let playing = null; // { bvid, lessons, data, from, key }

async function renderPlayer(bvid, q) {
  const from = q.from || '';
  const [fromN] = from.split('/');
  renderRail(gradeOf(+fromN) ? +fromN : 0);

  if (playing && playing.bvid === bvid && $('.player')) {
    selectLesson(q.l);
    return;
  }
  const my = ++token;
  const back = backLink(from);
  view.innerHTML = `<a class="back" href="${back.href}">${esc(back.label)}</a><div class="player"><div><div class="screen"></div>${ghostRows(1)}</div></div>`;
  try {
    const data = await cached('/api/view', { bvid });
    if (my !== token) return;
    const lessons = lessonsOf(data);
    playing = { bvid, data, lessons, from };
    const [fn, fs] = from.split('/');
    courseIndex.set(bvid, { bvid, title: data.season?.title || data.title, cover: data.cover, author: data.author,
      duration: lessons.reduce((s, l) => s + (l.duration || 0), 0), episodes: lessons.length,
      grade: +fn || null, subject: +fn ? fs : null, volume: +fn ? volumeFor(+fn, fs) : null });
    const groups = groupLessons(lessons);
    const total = lessons.reduce((s, l) => s + (l.duration || 0), 0);
    view.innerHTML = `
      <a class="back" href="${back.href}">${esc(back.label)}</a>
      <div class="player">
        <div>
          <div class="screen"><video id="video" controls playsinline preload="metadata"></video></div>
          <div class="lesson-no" id="lesson-no"></div>
          <h1 class="lesson-title" id="lesson-title"></h1>
          <p class="course-title">${esc(data.season?.title || data.title)}</p>
          <div class="course-meta">
            <span>${esc(data.author)}</span><span>${lessons.length} 节</span><span>${fmtHours(total)}</span><span>${fmtCount(data.play)} 次播放</span><span id="quality"></span>
          </div>
          <div class="actions">
            <button class="btn" id="prev">上一节</button>
            <button class="btn primary" id="next">下一节</button>
            <button class="btn" id="speed"></button>
            <button class="btn save-btn${lib.isFavorite(bvid) ? ' on' : ''}" data-save="${esc(bvid)}" aria-pressed="${lib.isFavorite(bvid)}"
              aria-label="${lib.isFavorite(bvid) ? '取消收藏' : '收藏'}：${esc((data.season?.title || data.title).slice(0, 24))}">${BOOKMARK}<span class="label">${lib.isFavorite(bvid) ? '已收藏' : '收藏'}</span></button>
            <a class="btn plain" id="open" target="_blank" rel="noopener">在 B 站打开</a>
          </div>
        </div>
        <aside class="toc" aria-label="目录">
          <div class="toc-head">目录<span id="toc-count"></span></div>
          <ol id="toc">${groups.map((g) => `
            ${g.label ? `<li class="chap">${esc(g.label)}</li>` : ''}
            ${g.items.map((i) => `<li><button class="lesson" data-key="${esc(lessons[i].key)}" title="${esc(lessons[i].part)}">
              <span class="i">${i + 1}</span><span class="t">${esc(lessons[i].part)}</span>
              <span class="d">${lessons[i].duration ? fmtDur(lessons[i].duration) : ''}</span><span class="k"></span>
            </button></li>`).join('')}`).join('')}
          </ol>
        </aside>
      </div>`;
    $('#toc').onclick = (e) => {
      const b = e.target.closest('.lesson');
      if (b) location.hash = playHref(bvid, b.dataset.key, from);
    };
    $('#prev').onclick = () => step(-1);
    $('#next').onclick = () => step(1);
    const speedBtn = $('#speed');
    speedBtn.textContent = `${speed}×`;
    speedBtn.onclick = () => {
      speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.length];
      store.set('speed', speed);
      $('#video').playbackRate = speed;
      speedBtn.textContent = `${speed}×`;
    };
    bindVideo($('#video'));
    selectLesson(q.l);
  } catch (e) {
    if (my !== token) return;
    view.innerHTML = `<a class="back" href="${back.href}">${esc(back.label)}</a>
      <div class="notice">${esc(e.message)}<br><button class="btn" id="retry">重试</button></div>`;
    $('#retry').onclick = () => render();
  }
}

function bindVideo(video) {
  let savedAt = 0;
  video.addEventListener('loadedmetadata', () => {
    video.playbackRate = speed;
    const pos = lib.progress(playing.bvid, playing.key)?.position || 0;
    if (pos > 5 && pos < video.duration - 10) video.currentTime = pos;
  });
  video.addEventListener('timeupdate', () => {
    if (!playing || !video.duration) return;
    const now = Date.now();
    if (now - savedAt > 4000) {
      savedAt = now;
      if (video.currentTime > 1) lib.setProgress(playing.bvid, playing.key, Math.floor(video.currentTime), Math.round(video.duration));
    }
    if (video.currentTime / video.duration > 0.9) markWatched(playing.key);
  });
  video.addEventListener('ended', () => {
    markWatched(playing.key);
    lib.setProgress(playing.bvid, playing.key, 0, 0);
    step(1);
  });
}

function markWatched(key) {
  if (lib.isWatched(playing.bvid, key)) return;
  lib.markWatched(playing.bvid, key);
  drawToc();
}

function drawToc() {
  const { bvid, lessons, key } = playing;
  document.querySelectorAll('.lesson').forEach((b) => {
    const on = b.dataset.key === key;
    b.classList.toggle('on', on);
    b.querySelector('.k').textContent = lib.isWatched(bvid, b.dataset.key) ? '✓' : '';
    if (on) b.setAttribute('aria-current', 'true'); else b.removeAttribute('aria-current');
  });
  $('#toc-count').textContent = `已看完 ${lib.watchedCount(bvid)} / ${lessons.length}`;
}

function step(delta) {
  const { lessons, bvid, from } = playing;
  const idx = lessons.findIndex((l) => l.key === playing.key) + delta;
  if (idx >= 0 && idx < lessons.length) location.hash = playHref(bvid, lessons[idx].key, from);
}

async function selectLesson(key) {
  const { lessons, data, bvid, from } = playing;
  if (!key) key = lib.historyEntry(bvid)?.lesson;
  let idx = lessons.findIndex((l) => l.key === key);
  if (idx < 0) idx = 0;
  const lesson = lessons[idx];
  playing.key = lesson.key;

  $('#open').href = `https://www.bilibili.com/video/${lesson.bvid}/?p=${lesson.p}`;
  $('#lesson-no').textContent = `第 ${idx + 1} 节，共 ${lessons.length} 节`;
  $('#lesson-title').textContent = lesson.part;
  $('#prev').disabled = idx === 0;
  $('#next').disabled = idx === lessons.length - 1;

  const [fn, fs] = (from || '').split('/');
  lib.record({
    bvid, title: data.season?.title || data.title, cover: data.cover, author: data.author, lesson: lesson.key, index: idx,
    part: lesson.part, total: lessons.length, grade: +fn || null, subject: +fn ? fs : null, volume: +fn ? volumeFor(+fn, fs) : null,
  });

  drawToc();
  // 只滚动目录自身，不带动整页
  const box = $('#toc'), el = $('.lesson.on');
  if (box && el && box.scrollHeight > box.clientHeight) {
    const r = el.getBoundingClientRect(), br = box.getBoundingClientRect();
    if (r.top < br.top || r.bottom > br.bottom) box.scrollTop += r.top - br.top - box.clientHeight / 3;
  }

  const video = $('#video');
  video.removeAttribute('src');
  try {
    const src = await api('/api/play', { bvid: lesson.bvid, cid: lesson.cid });
    if (playing?.key !== lesson.key) return;
    $('#quality').textContent = src.quality;
    video.src = src.src;
    video.play().catch(() => {});
  } catch (e) {
    $('#quality').textContent = e.message;
  }
}

// ---------- 启动 ----------
window.addEventListener('hashchange', () => {
  const { parts } = parseHash();
  if (parts[0] !== 'play') {
    const video = $('.screen video');
    if (playing && playing.key && video && video.currentTime > 1 && video.duration) {
      lib.setProgress(playing.bvid, playing.key, Math.floor(video.currentTime), Math.round(video.duration));
    }
    if (playing) syncer.schedule(500); // 退出播放页时同步进度
    playing = null;
  }
  render();
});
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') syncer.runIfStale(); });

(async () => {
  try {
    TAX = await api('/api/taxonomy', {});
    if (TAX.ai?.enabled) $('#search').q.placeholder = '搜索课程、知识点，或直接说想学什么';
    render();
    syncer.runIfStale();
  } catch (e) {
    view.innerHTML = `<div class="notice">${esc(e.message)}</div>`;
  }
})();
