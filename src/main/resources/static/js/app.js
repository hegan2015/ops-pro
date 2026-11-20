"use strict";

const $ = (s, el) => (el || document).querySelector(s);
const $$ = (s, el) => Array.from((el || document).querySelectorAll(s));
const esc = s => String(s == null ? "" : s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const ic = (id, cls) => `<svg class="ic ${cls || ""}"><use href="#${id}"/></svg>`;

const STATUS_TEXT = {
  RUNNING: "运行中", STOPPED: "已停止", UNKNOWN: "未知",
  STARTING: "启动中", STOPPING: "停止中", NEED_DOCKER: "需 Docker"
};
const badge = st => `<span class="badge ${st.toLowerCase()}"><i class="dot"></i>${STATUS_TEXT[st] || st}</span>`;

const S = {
  page: "overview",
  apps: [], mw: [], docker: null, audit: [], sources: [], tools: [],
  monitor: null, release: [], releaseHist: [],
  firstRender: true,
  auditQuery: "",
  log: { source: "cs-pro", file: "", tail: 200, filter: "", autoscroll: true, live: false, lines: [], shown: 0 },
  es: null, pollTick: 0
};

async function api(path, method) {
  const r = await fetch(path, { method: method || "GET", headers: { "Accept": "application/json" } });
  let body = null;
  try { body = await r.json(); } catch (e) { /* 非 JSON 响应 */ }
  if (!r.ok) throw new Error((body && body.message) || `请求失败（HTTP ${r.status}）`);
  return body;
}

async function apiJson(path, obj) {
  const r = await fetch(path, { method: "POST", headers: { "Accept": "application/json", "Content-Type": "application/json" }, body: JSON.stringify(obj) });
  let body = null;
  try { body = await r.json(); } catch (e) { /* 非 JSON */ }
  if (!r.ok) throw new Error((body && body.message) || `请求失败（HTTP ${r.status}）`);
  return body;
}

/* ---------- Toast / 模态 ---------- */
function toast(msg, type) {
  type = type || "info";
  const icon = type === "success" ? "i-check" : type === "error" ? "i-x" : "i-alert";
  const el = document.createElement("div");
  el.className = "toast " + type;
  el.innerHTML = `${ic(icon)}<div>${esc(msg)}</div>`;
  $("#toasts").appendChild(el);
  setTimeout(() => { el.classList.add("out"); setTimeout(() => el.remove(), 260); }, 4200);
}

let modalResolve = null;
function confirmDialog(title, body, okText) {
  $("#modalTitle").textContent = title;
  $("#modalBody").textContent = body;
  $("#modalOk").textContent = okText || "确认执行";
  $("#modalScrim").hidden = false;
  return new Promise(res => { modalResolve = res; });
}
$("#modalCancel").addEventListener("click", () => { $("#modalScrim").hidden = true; if (modalResolve) modalResolve(false); });
$("#modalOk").addEventListener("click", () => { $("#modalScrim").hidden = true; if (modalResolve) modalResolve(true); });
$("#modalScrim").addEventListener("click", e => { if (e.target === e.currentTarget) { e.currentTarget.hidden = true; if (modalResolve) modalResolve(false); } });

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    toast("已复制：" + text, "success");
  } catch (e) {
    const ta = document.createElement("textarea");
    ta.value = text; document.body.appendChild(ta); ta.select();
    try { document.execCommand("copy"); toast("已复制：" + text, "success"); }
    catch (e2) { toast("复制失败，请手动选择复制", "error"); }
    ta.remove();
  }
}

/* ---------- 数据刷新 ---------- */
async function refreshData() {
  const [apps, mw, docker, audit, tools] = await Promise.all([
    api("/api/apps"), api("/api/middleware"), api("/api/docker-status"), api("/api/audit"), api("/api/tools")
  ]);
  S.apps = apps; S.mw = mw; S.docker = docker; S.audit = audit; S.tools = tools;
  if (S.page === "monitor") S.monitor = await api("/api/monitor");
  else if (S.page === "release") {
    const [rel, hist] = await Promise.all([api("/api/release"), api("/api/release/history")]);
    S.release = rel; S.releaseHist = hist;
  }
  updateChrome();
  if (S.page === "overview") renderOverview(false);
  else if (S.page === "apps") renderApps(false);
  else if (S.page === "middleware") renderMiddleware(false);
  else if (S.page === "tools") renderTools(false);
  else if (S.page === "monitor") renderMonitor(false);
  else if (S.page === "release") renderRelease(false);
  else if (S.page === "audit") renderAuditTable();
}

function updateChrome() {
  const d = S.docker;
  const pill = $("#dockerPill");
  if (d) {
    pill.className = "docker-pill " + (d.available ? "ok" : "bad");
    $("#dockerPillTitle").textContent = d.available ? "Docker 就绪" : (d.installed ? "守护进程未运行" : "未安装 Docker");
    $("#dockerPillSub").textContent = d.available ? `引擎 ${d.version} · Compose ${d.composeVersion}` : "中间件启动已禁用";
  }
  const appRun = S.apps.filter(a => a.status === "RUNNING").length;
  const mwRun = S.mw.filter(m => m.status === "RUNNING").length;
  const na = $("#navAppCount"), nm = $("#navMwCount");
  na.textContent = `${appRun}/${S.apps.length}`;
  na.className = "nav-count" + (appRun > 0 ? " ok" : "");
  nm.textContent = `${mwRun}/${S.mw.length}`;
  nm.className = "nav-count" + (mwRun > 0 ? " ok" : "");
  const ac = $("#navAlertCount");
  if (ac) {
    const n = S.monitor ? (S.monitor.alerts || []).length : 0;
    ac.textContent = n ? `${n} 告警` : "";
    ac.className = "nav-count" + (n ? " bad" : "");
  }
}

/* ---------- 总览 ---------- */
function statCard(icon, grad, glow, num, unit, label, sub, delay, animate) {
  return `<div class="card hoverable stat-card ${animate ? "enter" : ""}" style="--glow:${glow};${animate ? `animation-delay:${delay}ms` : ""}">
    <div class="stat-icon" style="background:${grad}">${ic(icon)}</div>
    <div><div class="stat-num">${num}<small> ${unit}</small></div>
    <div class="stat-label">${label}</div><div class="stat-sub">${esc(sub)}</div></div>
  </div>`;
}

function dockerBanner(animate) {
  const d = S.docker;
  if (!d) return "";
  const enter = animate ? "enter" : "";
  if (d.available) {
    return `<div class="docker-banner ok ${enter}">${ic("i-container")}<div><h3>Docker 环境就绪</h3>
      <p>${esc(d.message)}。共享中间件由 <code>deploy/middleware-compose.yml</code> 统一定义，数据卷命名 <code>claim-shared-*</code>，claim / cs / ride / payment 复用同一套实例（yiwu 独立运行）。</p></div></div>`;
  }
  return `<div class="docker-banner ${enter}">${ic("i-alert")}<div><h3>${d.installed ? "Docker 守护进程未运行" : "本机未检测到 Docker —— 中间件启动已禁用"}</h3>
    <p>${esc(d.guidance)}<br>推荐命令：<code>brew install --cask docker</code>　或前往 <a href="https://www.docker.com/products/docker-desktop/" target="_blank" rel="noopener">Docker Desktop 官网下载（Apple Silicon）</a>。应用管理与日志中心不受影响。</p></div></div>`;
}

function hasMwDep(a) {
  return S.mw.some(m => (m.usedBy || []).some(u => u === a.name || u.indexOf(a.name + "（") === 0));
}

/* 拓扑含全部应用：无外部中间件依赖者（如 quant-pro 用进程内 DuckDB）
   连到右侧「进程内存储」伪节点，避免在图上直接消失。 */
function topoApps() {
  return S.apps.slice();
}

function mwUsers(m) {
  const out = [];
  (m.usedBy || []).forEach(u => {
    const a = S.apps.find(x => u === x.name || u.indexOf(x.name + "（") === 0);
    if (a) out.push({ app: a, optional: u.indexOf("可选") >= 0 });
  });
  return out;
}

function topoSvg() {
  const stColor = st => st === "RUNNING" ? "#34d399" : st === "NEED_DOCKER" ? "#fbbf24" : st === "STARTING" || st === "STOPPING" ? "#22d3ee" : "#5c6d8f";
  const apps = topoApps();
  const standalone = apps.filter(a => !hasMwDep(a));
  const mwY = i => 22 + i * 44;
  const extraIdx = standalone.length ? S.mw.length : -1;
  const rows = S.mw.length + (standalone.length ? 1 : 0);
  const H = Math.max(mwY(rows - 1) + 34, 120);
  const slot = H / Math.max(apps.length, 1);
  const appY = j => Math.round(slot * j + (slot - 48) / 2);
  const appCy = j => appY(j) + 24;
  let edges = "", nodes = "";
  S.mw.forEach((m, i) => {
    const y = mwY(i), cy = y + 17;
    mwUsers(m).forEach(({ app, optional }) => {
      const j = apps.indexOf(app);
      const color = APP_COLOR[app.name] || "#22d3ee";
      const live = app.status === "RUNNING" && m.status === "RUNNING";
      edges += `<path class="topo-edge ${live ? "live" : ""}" stroke="${color}" ${optional ? 'stroke-dasharray="4 5"' : ""} d="M190 ${appCy(j)} C 400 ${appCy(j)}, 400 ${cy}, 600 ${cy}"/>`;
    });
    nodes += `<g class="topo-node"><rect x="600" y="${y}" width="300" height="34" rx="9"/>
      <circle cx="618" cy="${cy}" r="4" fill="${stColor(m.status)}"/>
      <text x="632" y="${cy + 4}">${esc(m.label)}</text>
      <text class="sub" x="888" y="${cy + 4}" text-anchor="end">:${m.port}</text></g>`;
  });
  if (extraIdx >= 0) {
    const y = mwY(extraIdx), cy = y + 17;
    standalone.forEach(a => {
      const j = apps.indexOf(a), color = APP_COLOR[a.name] || "#22d3ee";
      const live = a.status === "RUNNING";
      edges += `<path class="topo-edge ${live ? "live" : ""}" stroke="${color}" stroke-dasharray="2 5" d="M190 ${appCy(j)} C 400 ${appCy(j)}, 400 ${cy}, 600 ${cy}"/>`;
    });
    nodes += `<g class="topo-node"><rect x="600" y="${y}" width="300" height="34" rx="9" stroke-dasharray="3 4"/>
      <circle cx="618" cy="${cy}" r="4" fill="${standalone.some(a => a.status === "RUNNING") ? "#34d399" : "#5c6d8f"}"/>
      <text x="632" y="${cy + 4}">进程内 DuckDB（无外部中间件）</text>
      <text class="sub" x="888" y="${cy + 4}" text-anchor="end">embedded</text></g>`;
  }
  apps.forEach((a, j) => {
    const y = appY(j), color = APP_COLOR[a.name] || "#22d3ee";
    nodes += `<g class="topo-node"><rect x="20" y="${y}" width="170" height="48" rx="10" style="stroke:${color}55"/>
      <circle cx="38" cy="${y + 24}" r="4.5" fill="${stColor(a.status)}"/>
      <text x="52" y="${y + 21}" style="font-weight:600">${esc(a.label)}</text>
      <text class="sub" x="52" y="${y + 37}">:${a.port} · ${STATUS_TEXT[a.status] || a.status}</text></g>`;
  });
  return `<svg viewBox="0 0 920 ${H + 16}" role="img" aria-label="共享中间件复用拓扑">
    <text class="topo-axis" x="20" y="14">应用</text>
    <text class="topo-axis" x="600" y="14">共享中间件（Docker · claim-shared）/ 进程内存储</text>
    ${edges}${nodes}</svg>`;
}

function resultTag(r) {
  const cls = r === "成功" ? "success" : r === "失败" ? "fail" : "pending";
  return `<span class="result-tag ${cls}">${esc(r)}</span>`;
}

function feedItems(list) {
  if (!list.length) return `<div class="empty-tip">暂无操作记录 —— 启动一个应用试试</div>`;
  return list.map(a => `<div class="feed-item">
    <span class="feed-time">${esc(a.time.slice(5))}</span>
    <span class="feed-target">${esc(a.target)}</span>
    <span class="feed-action">${esc(a.action)}</span>
    <span class="feed-result">${resultTag(a.result)}</span>
  </div>`).join("");
}

function renderOverview(animate) {
  const appRun = S.apps.filter(a => a.status === "RUNNING").length;
  const mwRun = S.mw.filter(m => m.status === "RUNNING").length;
  const d = S.docker || {};
  const today = new Date().toISOString().slice(0, 10);
  const todayOps = S.audit.filter(a => (a.time || "").slice(0, 10) === today).length;
  const enter = animate ? "enter" : "";
  const delay = i => animate ? `animation-delay:${i * 60}ms` : "";
  const html = `
    <div class="stat-grid">
      ${statCard("i-apps", "var(--grad-brand)", "rgba(34,211,238,.14)", appRun, `/ ${S.apps.length}`, "运行中应用", "claim / cs / ride / payment / yiwu / quant", 0, animate)}
      ${statCard("i-layers", "var(--grad-indigo)", "rgba(129,140,248,.14)", mwRun, `/ ${S.mw.length}`, "运行中中间件", d.available ? "Docker Compose 统一管理" : "等待 Docker 环境就绪", 60, animate)}
      ${statCard("i-container", d.available ? "var(--grad-green)" : "var(--grad-amber)", d.available ? "rgba(52,211,153,.14)" : "rgba(251,191,36,.14)", d.available ? "就绪" : "缺失", "", "Docker 环境", d.available ? `引擎 ${d.version}` : (d.installed ? "请启动 Docker Desktop" : "未安装，见下方指引"), 120, animate)}
      ${statCard("i-shield", "var(--grad-red)", "rgba(248,113,113,.12)", todayOps, "条", "今日操作审计", `全部记录 ${S.audit.length} 条 · 持久化保存`, 180, animate)}
    </div>
    <div style="height:16px"></div>
    ${dockerBanner(animate)}
    <div class="overview-cols">
      <div class="card topo-card ${enter}" style="${delay(2)}">
        <div class="section-title" style="margin:0 0 6px">${ic("i-link")}<h2>共享复用拓扑</h2><span class="sub">同一套中间件 · 只此一份</span></div>
        ${topoSvg()}
        <div class="topo-legend">
          ${topoApps().map(a => `<span><i style="background:${APP_COLOR[a.name] || "#22d3ee"}"></i>${esc(a.name)} 依赖</span>`).join("")}
          <span><i style="background:#34d399"></i>流动线 = 双方运行中</span>
          <span><i style="background:transparent;border-top:2px dashed #9aa7bd;height:0"></i>虚线 = 可选复用</span>
          <span><i style="background:transparent;border-top:2px dotted #a78bfa;height:0"></i>点线 = 进程内存储（无外部中间件）</span>
        </div>
      </div>
      <div class="card feed-card ${enter}" style="${delay(3)}">
        <div class="section-title" style="margin:0">${ic("i-clock")}<h2>实时动态</h2><span class="sub">最近 12 条</span>
          <span class="right"><a class="link-btn" href="#/audit">全部审计 ${ic("i-external")}</a></span></div>
        <div class="feed-list">${feedItems(S.audit.slice(0, 12))}</div>
      </div>
    </div>
    <div class="section-title">${ic("i-apps")}<h2>应用状态速览</h2><span class="sub">点击卡片进入应用管理</span></div>
    <div class="app-strip">
      ${S.apps.map((a, i) => `<a href="#/apps" class="card hoverable ${enter}" style="padding:14px 16px;display:block;${delay(i)}">
        <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px">
          <strong style="font-size:13px">${esc(a.name)}</strong></div>
        ${badge(a.status)}
        <div class="mono" style="font-size:11px;color:var(--text-3);margin-top:8px">:${a.port} · ${esc(a.label)}</div>
      </a>`).join("")}
    </div>
    <div class="section-title">${ic("i-layers")}<h2>中间件速览</h2><span class="sub">点击卡片进入共享中间件</span></div>
    <div class="app-strip">
      ${S.mw.map((m, i) => `<a href="#/middleware" class="card hoverable ${enter}" style="padding:14px 16px;display:block;${delay(i)}">
        <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px">
          <strong style="font-size:13px">${esc(m.label)}</strong></div>
        ${badge(m.status)}
        <div class="mono" style="font-size:11px;color:var(--text-3);margin-top:8px">:${m.port} · ${esc(m.name)}</div>
      </a>`).join("")}
    </div>
    <div class="section-title">${ic("i-bolt")}<h2>运维工具</h2><span class="sub">新标签页打开</span></div>
    <div class="app-strip">
      ${S.tools.map((t, i) => `<a href="${esc(t.url)}" target="_blank" rel="noopener" class="card hoverable ${enter}" style="padding:14px 16px;display:block;${delay(i)}">
        <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px">
          <strong style="font-size:13px">${esc(t.label)}</strong>${ic("i-external")}</div>
        ${badge(t.status)}
        <div style="font-size:11px;color:var(--text-3);margin-top:8px;line-height:1.5">${esc(t.desc)}</div>
      </a>`).join("")}
    </div>`;
  $("#content").innerHTML = html;
}

/* ---------- 应用管理 ---------- */
const APP_ICON = { "claim-pro": "i-shield", "cs-pro": "i-terminal", "ride-pro": "i-flow", "payment-pro": "i-db", "yiwu-pro": "i-drive" };
const APP_COLOR = { "claim-pro": "#22d3ee", "cs-pro": "#e879f9", "ride-pro": "#34d399",
  "payment-pro": "#fbbf24", "yiwu-pro": "#818cf8", "quant-pro": "#a78bfa" };

function appDepChips(name) {
  const deps = [];
  S.mw.forEach(m => {
    const u = (m.usedBy || []).find(x => x === name || x.indexOf(name + "（") === 0);
    if (u) deps.push({ m, optional: u.indexOf("可选") >= 0 });
  });
  if (!deps.length) return `<span class="chip" style="color:var(--text-3);border-color:var(--line);background:var(--elevated)">无外部中间件依赖</span>`;
  return deps.map(({ m, optional }) => {
    const dot = m.status === "RUNNING" ? "#34d399" : "#5c6d8f";
    return `<span class="chip ${optional ? "optional" : ""}" title="${esc(m.label)} :${m.port} · ${STATUS_TEXT[m.status] || m.status}"><i style="display:inline-block;width:6px;height:6px;border-radius:50%;background:${dot};margin-right:5px;vertical-align:middle"></i>${esc(m.label)}${optional ? "（可选）" : ""}</span>`;
  }).join("");
}

function appCard(a, animate, i) {
  const color = APP_COLOR[a.name] || "#22d3ee";
  const busy = a.busy;
  const canStart = !busy && a.installed && a.status !== "RUNNING" && a.status !== "STARTING";
  const canStop = !busy && (a.status === "RUNNING" || a.status === "UNKNOWN" || a.status === "STARTING");
  const startTitle = !a.installed ? "启动脚本不存在" : a.status === "RUNNING" ? "已在运行" : busy ? "操作进行中" : "执行 " + a.startScript;
  const stopTitle = canStop ? "执行 " + a.stopScript + "（必要时按端口兜底）" : "应用未在运行";
  return `<div class="card hoverable svc-card ${animate ? "enter" : ""}" style="${animate ? `animation-delay:${i * 60}ms` : ""}">
    <div class="svc-head">
      <div class="svc-icon" style="color:${color};border-color:${color}44;background:${color}14">${ic(APP_ICON[a.name] || "i-apps")}</div>
      <div class="svc-title"><strong>${esc(a.label)}</strong><span>${esc(a.name)}</span></div>
      ${badge(a.status)}
    </div>
    <dl class="kv">
      <dt>访问</dt><dd><span class="endpoint-row"><span class="endpoint">${esc(a.url)}</span>
        <button class="icon-btn" data-copy="${esc(a.url)}" title="复制地址">${ic("i-copy")}</button>
        <a class="icon-btn" href="${esc(a.url)}" target="_blank" rel="noopener" title="打开应用（新标签页）">${ic("i-external")}</a></span></dd>
      <dt>目录</dt><dd class="mono" title="${esc(a.dir)}">${esc(a.dir)}</dd>
      <dt>脚本</dt><dd class="mono">${esc(a.startScript)} / ${esc(a.stopScript)}${a.pid ? ` · PID ${a.pid}` : ""}</dd>
      <dt>日志</dt><dd class="mono" title="${esc(a.logDir)}">${esc(a.logDir)}</dd>
    </dl>
    <div style="margin-top:9px"><div style="font-size:11px;color:var(--text-3);margin-bottom:5px">依赖中间件</div>
      <div class="chips">${appDepChips(a.name)}</div></div>
    ${a.hint ? `<div class="svc-hint">${ic("i-alert")}<span>${esc(a.hint)}</span></div>` : ""}
    <div class="svc-foot">
      <button class="btn btn-start btn-sm" data-act="app-start" data-name="${esc(a.name)}" ${canStart ? "" : "disabled"} title="${esc(startTitle)}">
        ${busy && a.status === "STARTING" ? '<span class="spinner"></span>启动中' : ic("i-play") + "启动"}</button>
      <button class="btn btn-danger btn-sm" data-act="app-stop" data-name="${esc(a.name)}" ${canStop ? "" : "disabled"} title="${esc(stopTitle)}">
        ${busy && a.status === "STOPPING" ? '<span class="spinner"></span>停止中' : ic("i-stop") + "停止"}</button>
      <span class="spacer"></span>
      <button class="link-btn" data-act="app-log" data-name="${esc(a.name)}">${ic("i-terminal")}查看日志</button>
    </div>
  </div>`;
}

function renderApps(animate) {
  $("#content").innerHTML = `
    <div class="toolbar">
      <span style="font-size:12.5px;color:var(--text-2)">通过各项目自带脚本启停，输出归档至 <span class="mono" style="color:var(--cyan)">data/proc/&lt;name&gt;.out</span>，操作全程审计。</span>
      <span class="spacer"></span>
      <button class="btn btn-ghost btn-sm" data-act="refresh">${ic("i-refresh")}立即刷新</button>
    </div>
    <div class="grid-3">${S.apps.map((a, i) => appCard(a, animate, i)).join("")}</div>`;
}

/* ---------- 中间件 ---------- */
const MW_ICON = {
  "temporal": "i-flow", "rocketmq-namesrv": "i-rocket", "rocketmq-broker": "i-rocket",
  "rocketmq-dashboard": "i-gauge", "mysql": "i-db", "redis": "i-bolt", "minio": "i-drive",
  "zookeeper": "i-link", "zookeeper-ride": "i-link"
};
const MW_COLOR = {
  "temporal": "#818cf8", "rocketmq-namesrv": "#fbbf24", "rocketmq-broker": "#f59e0b",
  "rocketmq-dashboard": "#e879f9", "mysql": "#3b82f6", "redis": "#f87171", "minio": "#34d399",
  "zookeeper": "#fb923c", "zookeeper-ride": "#a3e635"
};

function mwCard(m, animate, i) {
  const color = MW_COLOR[m.name] || "#22d3ee";
  const d = S.docker || {};
  const busy = m.busy;
  const canStart = d.available && !busy && m.status !== "RUNNING";
  const canStop = d.available && !busy && m.status === "RUNNING";
  const reason = !d.available ? "Docker 不可用，无法启停（见顶部指引）" : busy ? "操作进行中" : "";
  return `<div class="card hoverable svc-card ${animate ? "enter" : ""}" style="${animate ? `animation-delay:${i * 50}ms` : ""}">
    <div class="svc-head">
      <div class="svc-icon" style="color:${color};border-color:${color}44;background:${color}14">${ic(MW_ICON[m.name] || "i-layers")}</div>
      <div class="svc-title"><strong>${esc(m.label)}</strong><span>${esc(m.name)}</span></div>
      ${badge(m.status)}
    </div>
    <div class="svc-desc">${esc(m.desc)}</div>
    <div class="endpoint-row">
      <span class="endpoint">${esc(m.endpoint)}</span>
      <button class="icon-btn" data-copy="${esc(m.endpoint)}" title="复制端点">${ic("i-copy")}</button>
      ${m.consoleUrl ? `<a class="link-btn" href="${esc(m.consoleUrl)}" target="_blank" rel="noopener">${ic("i-external")}控制台 :${m.consolePort}</a>` : ""}
    </div>
    <div class="chips">${(m.usedBy || []).map(u => `<span class="chip ${u.indexOf("可选") >= 0 ? "optional" : ""}">${esc(u)}</span>`).join("")}
      ${m.containerState ? `<span class="chip" style="color:var(--text-3);border-color:var(--line);background:var(--elevated)">容器 ${esc(m.containerState)}</span>` : ""}</div>
    ${m.status === "NEED_DOCKER" ? `<div class="svc-hint">${ic("i-alert")}<span>需要 Docker 环境才能启动，安装指引见页面顶部横幅。</span></div>` : ""}
    <div class="svc-foot">
      <button class="btn btn-start btn-sm" data-act="mw-start" data-name="${esc(m.name)}" ${canStart ? "" : "disabled"} title="${esc(reason || "docker compose up -d " + m.composeServices.join(" "))}">
        ${busy && m.status === "STARTING" ? '<span class="spinner"></span>启动中' : ic("i-play") + "启动"}</button>
      <button class="btn btn-danger btn-sm" data-act="mw-stop" data-name="${esc(m.name)}" ${canStop ? "" : "disabled"} title="${esc(reason || "docker compose stop " + m.composeServices.join(" "))}">
        ${busy && m.status === "STOPPING" ? '<span class="spinner"></span>停止中' : ic("i-stop") + "停止"}</button>
      <span class="spacer"></span>
      <span class="mono" style="font-size:10.5px;color:var(--text-3)">compose: ${esc(m.composeServices.join(", "))}</span>
    </div>
  </div>`;
}

function renderMiddleware(animate) {
  const d = S.docker || {};
  const anyBusy = S.mw.some(m => m.busy);
  const allRun = S.mw.length > 0 && S.mw.every(m => m.status === "RUNNING");
  const allStop = S.mw.every(m => m.status === "STOPPED" || m.status === "NEED_DOCKER");
  $("#content").innerHTML = `
    ${dockerBanner(animate)}
    <div class="toolbar">
      <button class="btn btn-primary btn-sm" data-act="mw-start-all" ${d.available && !anyBusy && !allRun ? "" : "disabled"}
        title="${d.available ? "docker compose up -d（全部服务，首次需拉取镜像）" : "Docker 不可用"}">${ic("i-play")}全部启动</button>
      <button class="btn btn-danger btn-sm" data-act="mw-stop-all" ${d.available && !anyBusy && !allStop ? "" : "disabled"}
        title="${d.available ? "docker compose stop（全部服务，数据卷保留）" : "Docker 不可用"}">${ic("i-stop")}全部停止</button>
      <span style="font-size:12px;color:var(--text-3)">权威定义 <span class="mono">deploy/middleware-compose.yml</span> · 数据卷 <span class="mono">claim-shared-*</span> · 停止不删数据</span>
      <span class="spacer"></span>
      <button class="btn btn-ghost btn-sm" data-act="refresh">${ic("i-refresh")}立即刷新</button>
    </div>
    <div class="grid-3">${S.mw.map((m, i) => mwCard(m, animate, i)).join("")}</div>`;
}

/* ---------- 运维工具 ---------- */
const TOOL_ICON = { "adminer": "i-db", "rocketmq-dashboard": "i-gauge", "temporal-ui": "i-flow", "xxl-job-admin": "i-clock" };

function toolCard(t, animate, i) {
  return `<div class="card hoverable svc-card ${animate ? "enter" : ""}" style="${animate ? `animation-delay:${i * 50}ms` : ""}">
    <div class="svc-head">
      <div class="svc-icon" style="color:var(--cyan);border-color:rgba(34,211,238,.27);background:rgba(34,211,238,.08)">${ic(TOOL_ICON[t.name] || "i-bolt")}</div>
      <div class="svc-title"><strong>${esc(t.label)}</strong><span>:${t.port}</span></div>
      ${badge(t.status)}
    </div>
    <div class="svc-desc">${esc(t.desc)}</div>
    <div class="endpoint-row">
      <span class="endpoint">${esc(t.url)}</span>
      <button class="icon-btn" data-copy="${esc(t.url)}" title="复制地址">${ic("i-copy")}</button>
      <a class="link-btn" href="${esc(t.url)}" target="_blank" rel="noopener">${ic("i-external")}打开</a>
    </div>
  </div>`;
}

function renderTools(animate) {
  $("#content").innerHTML = `
    <div class="toolbar">
      <span style="font-size:12.5px;color:var(--text-2)">独立部署的网页版运维工具，端口探活显示状态，点击「打开」在新标签页访问。</span>
      <span class="spacer"></span>
      <button class="btn btn-ghost btn-sm" data-act="refresh">${ic("i-refresh")}立即刷新</button>
    </div>
    <div class="grid-3">${S.tools.map((t, i) => toolCard(t, animate, i)).join("")}</div>`;
}

/* ---------- 日志中心 ---------- */
function levelClass(line) {
  if (/\bERROR\b|\bException\b|错误|失败/.test(line)) return "lv-error";
  if (/\bWARN(ING)?\b|警告/.test(line)) return "lv-warn";
  if (/\bINFO\b/.test(line)) return "lv-info";
  if (/\bDEBUG\b|\bTRACE\b/.test(line)) return "lv-debug";
  return "";
}

function highlight(text, filter) {
  const safe = esc(text);
  if (!filter) return safe;
  const f = esc(filter);
  const idx = safe.toLowerCase().indexOf(f.toLowerCase());
  if (idx < 0) return safe;
  let out = "", rest = safe, lower = rest.toLowerCase(), fl = f.toLowerCase(), pos;
  while ((pos = lower.indexOf(fl)) >= 0) {
    out += rest.slice(0, pos) + "<mark>" + rest.slice(pos, pos + f.length) + "</mark>";
    rest = rest.slice(pos + f.length);
    lower = rest.toLowerCase();
  }
  return out + rest;
}

function renderLogLines() {
  const L = S.log;
  const body = $("#logBody");
  if (!body) return;
  const f = L.live ? "" : L.filter;
  let shown = 0;
  const html = L.lines.map((line, i) => {
    const match = !f || line.toLowerCase().includes(f.toLowerCase());
    if (match) shown++;
    return `<div class="log-line ${levelClass(line)}" ${match ? "" : 'style="display:none"'}><span class="ln">${i + 1}</span><span class="tx">${highlight(line, L.filter)}</span></div>`;
  }).join("");
  body.innerHTML = html || `<div class="empty-tip" style="padding:40px 0">暂无日志内容${L.file ? "" : " —— 请先选择日志文件"}</div>`;
  L.shown = shown;
  const foot = $("#logFoot");
  if (foot) foot.innerHTML = `<span>缓冲 ${L.lines.length} 行</span><span>显示 ${shown} 行</span>
    ${L.filter ? `<span>过滤词「${esc(L.filter)}」</span>` : ""}
    <span class="spacer" style="flex:1"></span><span>${L.autoscroll ? "自动滚动：开" : "自动滚动：关"}</span>`;
  if (L.autoscroll) body.scrollTop = body.scrollHeight;
}

function closeStream() {
  if (S.es) { S.es.close(); S.es = null; }
  const dot = $("#streamDot");
  if (dot) { dot.className = "stream-dot"; dot.innerHTML = "<i></i>静态"; }
}

function openStream() {
  closeStream();
  const L = S.log;
  if (!L.live || !L.file) return;
  const url = `/api/logs/stream?source=${encodeURIComponent(L.source)}&file=${encodeURIComponent(L.file)}&tail=50&filter=${encodeURIComponent(L.filter)}`;
  const es = new EventSource(url);
  S.es = es;
  const dot = $("#streamDot");
  es.addEventListener("init", e => {
    const d = JSON.parse(e.data);
    L.lines = d.lines.slice(-5000);
    renderLogLines();
    if (dot) { dot.className = "stream-dot on"; dot.innerHTML = "<i></i>实时追踪"; }
  });
  es.addEventListener("lines", e => {
    const arr = JSON.parse(e.data);
    L.lines.push(...arr);
    if (L.lines.length > 5000) L.lines.splice(0, L.lines.length - 5000);
    renderLogLines();
  });
  es.addEventListener("reset", () => { L.lines = []; renderLogLines(); });
  es.onerror = () => {
    if (dot) { dot.className = "stream-dot"; dot.innerHTML = "<i></i>连接断开"; }
  };
}

async function loadTail() {
  const L = S.log;
  if (!L.file) { L.lines = []; renderLogLines(); return; }
  try {
    const d = await api(`/api/logs?source=${encodeURIComponent(L.source)}&file=${encodeURIComponent(L.file)}&tail=${L.tail}`);
    L.lines = d.lines;
    const head = $("#logPath");
    if (head) head.textContent = `${d.source} / ${d.file}`;
    const meta = $("#logMeta");
    if (meta) meta.textContent = `${(d.size / 1024).toFixed(1)} KB · 更新于 ${d.mtime}`;
    renderLogLines();
  } catch (e) { toast(e.message, "error"); }
}

function currentSource() { return S.sources.find(s => s.id === S.log.source); }

function fillFileSelect() {
  const sel = $("#logFile");
  if (!sel) return;
  const src = currentSource();
  const files = (src && src.files) || [];
  const prev = S.log.file;
  sel.innerHTML = files.length
    ? files.map(f => `<option value="${esc(f.name)}">${esc(f.name)}（${(f.size / 1024).toFixed(1)} KB · ${esc(f.mtime.slice(5))}）</option>`).join("")
    : '<option value="">（该目录暂无日志文件）</option>';
  if (files.some(f => f.name === prev)) sel.value = prev;
  else { S.log.file = files.length ? files[0].name : ""; sel.value = S.log.file; }
}

function renderLogs() {
  const L = S.log;
  $("#content").innerHTML = `
    <div class="toolbar">
      <div class="field"><label>日志源</label>
        <select id="logSource">${S.sources.map(s => `<option value="${esc(s.id)}" ${s.id === L.source ? "selected" : ""}>${esc(s.label)}（${s.id}）</option>`).join("")}</select></div>
      <div class="field"><label>日志文件</label><select id="logFile" style="min-width:250px"></select></div>
      <div class="field"><label>尾部行数</label>
        <select id="logTail">${[100, 200, 500, 1000, 2000].map(n => `<option value="${n}" ${n === L.tail ? "selected" : ""}>${n} 行</option>`).join("")}</select></div>
      <div class="field"><label>关键字过滤</label>
        <span class="search-wrap">${ic("i-search")}<input class="input" id="logFilter" placeholder="如 ERROR / 订单号…" value="${esc(L.filter)}" style="width:190px"></span></div>
      <div class="field"><label>&nbsp;</label>
        <div style="display:flex;gap:14px;align-items:center;height:34px">
          <label class="switch"><input type="checkbox" id="logLive" ${L.live ? "checked" : ""}><span class="track"></span>实时追踪</label>
          <label class="switch"><input type="checkbox" id="logScroll" ${L.autoscroll ? "checked" : ""}><span class="track"></span>自动滚动</label>
        </div></div>
      <div class="field"><label>&nbsp;</label>
        <div style="display:flex;gap:8px;height:34px;align-items:center">
          <button class="btn btn-ghost btn-sm" id="logRefresh">${ic("i-refresh")}刷新</button>
          <button class="btn btn-ghost btn-sm" id="logClear">${ic("i-broom")}清屏</button>
        </div></div>
    </div>
    <div class="card log-shell">
      <div class="log-head">
        ${ic("i-terminal")}<span class="path" id="logPath">${esc(L.source)} / ${esc(L.file || "—")}</span>
        <span id="logMeta"></span><span class="spacer"></span>
        <span class="stream-dot" id="streamDot"><i></i>静态</span>
      </div>
      <div class="log-body" id="logBody"></div>
      <div class="log-foot" id="logFoot"></div>
    </div>`;
  fillFileSelect();
  $("#logSource").addEventListener("change", e => {
    S.log.source = e.target.value; S.log.file = "";
    fillFileSelect(); closeStream(); loadTail().then(openStream);
  });
  $("#logFile").addEventListener("change", e => {
    S.log.file = e.target.value; closeStream(); loadTail().then(openStream);
  });
  $("#logTail").addEventListener("change", e => {
    S.log.tail = parseInt(e.target.value, 10);
    if (!S.log.live) loadTail();
  });
  let filterTimer = null;
  $("#logFilter").addEventListener("input", e => {
    S.log.filter = e.target.value.trim();
    clearTimeout(filterTimer);
    filterTimer = setTimeout(() => {
      if (S.log.live) openStream();
      else renderLogLines();
    }, 400);
  });
  $("#logLive").addEventListener("change", e => {
    S.log.live = e.target.checked;
    if (S.log.live) { openStream(); toast("已开启实时追踪（SSE 增量推送）", "info"); }
    else { closeStream(); loadTail(); }
  });
  $("#logScroll").addEventListener("change", e => {
    S.log.autoscroll = e.target.checked;
    if (S.log.autoscroll) { const b = $("#logBody"); b.scrollTop = b.scrollHeight; }
    renderLogLines();
  });
  $("#logRefresh").addEventListener("click", () => { loadTail(); toast("已刷新尾部日志", "success"); });
  $("#logClear").addEventListener("click", () => { S.log.lines = []; renderLogLines(); });
  loadTail().then(openStream);
}

async function refreshSources() {
  try {
    S.sources = await api("/api/logs/sources");
    if (S.page === "logs") {
      const sel = $("#logSource");
      if (sel && sel.options.length !== S.sources.length) {
        sel.innerHTML = S.sources.map(s => `<option value="${esc(s.id)}" ${s.id === S.log.source ? "selected" : ""}>${esc(s.label)}（${s.id}）</option>`).join("");
      }
      fillFileSelect();
    }
  } catch (e) { /* 静默 */ }
}

/* ---------- 审计 ---------- */
function renderAudit() {
  $("#content").innerHTML = `
    <div class="toolbar">
      <span class="search-wrap">${ic("i-search")}<input class="input" id="auditQuery" placeholder="按对象 / 操作 / 详情过滤…" value="${esc(S.auditQuery)}" style="width:280px"></span>
      <span style="font-size:12px;color:var(--text-3)" id="auditCount"></span>
      <span class="spacer"></span>
      <span style="font-size:12px;color:var(--text-3)">持久化于 <span class="mono">data/state.json</span> · 重启不丢失 · 上限 500 条</span>
    </div>
    <div class="card table-card"><div class="table-scroll">
      <table class="audit">
        <thead><tr><th style="width:150px">时间</th><th style="width:150px">对象</th><th style="width:80px">操作</th><th style="width:90px">结果</th><th>详情</th></tr></thead>
        <tbody id="auditBody"></tbody>
      </table>
    </div></div>`;
  $("#auditQuery").addEventListener("input", e => { S.auditQuery = e.target.value.trim(); renderAuditTable(); });
  renderAuditTable();
}

function renderAuditTable() {
  const body = $("#auditBody");
  if (!body) return;
  const q = S.auditQuery.toLowerCase();
  const list = S.audit.filter(a => !q || [a.time, a.target, a.action, a.result, a.detail].join(" ").toLowerCase().includes(q));
  const cnt = $("#auditCount");
  if (cnt) cnt.textContent = `共 ${S.audit.length} 条 · 匹配 ${list.length} 条`;
  body.innerHTML = list.length ? list.map(a => `<tr>
    <td class="mono">${esc(a.time)}</td>
    <td class="mono" style="color:var(--cyan)">${esc(a.target)}</td>
    <td>${esc(a.action)}</td>
    <td>${resultTag(a.result)}</td>
    <td class="detail">${esc(a.detail)}</td>
  </tr>`).join("") : `<tr><td colspan="5"><div class="empty-tip">暂无匹配的审计记录</div></td></tr>`;
}

/* ---------- 可观测中心 ---------- */
function sparkline(values) {
  const pts = (values || []).filter(v => v != null);
  if (pts.length < 2) return `<svg class="spark" viewBox="0 0 120 32"><line x1="0" y1="28" x2="120" y2="28" stroke="var(--line)" stroke-width="1"/></svg>`;
  const max = Math.max(...pts, 1);
  const step = 120 / (pts.length - 1);
  const poly = pts.map((v, i) => `${(i * step).toFixed(1)},${(30 - (v / max) * 26).toFixed(1)}`).join(" ");
  return `<svg class="spark" viewBox="0 0 120 32"><polyline points="${poly}" fill="none" stroke="var(--cyan)" stroke-width="1.6"/></svg>`;
}

const MON_METRIC = { cpu: "CPU 使用率", mem: "内存使用率", down: "目标停止" };

function monitorCard(t, animate, i) {
  const isApp = t.kind === "app";
  const mem = isApp ? (t.mem != null ? t.mem + "%" : "—") : (t.memMb != null ? t.memMb + " MiB" : "—");
  return `<div class="card hoverable svc-card ${animate ? "enter" : ""}" style="${animate ? `animation-delay:${i * 40}ms` : ""}">
    <div class="svc-head">
      <span class="svc-icon" style="color:${isApp ? "var(--cyan)" : "var(--indigo)"};border-color:${isApp ? "rgba(34,211,238,.27)" : "rgba(129,140,248,.3)"};background:${isApp ? "rgba(34,211,238,.08)" : "rgba(129,140,248,.1)"}">${ic(isApp ? "i-apps" : "i-layers")}</span>
      <div class="svc-title"><strong>${esc(t.label)}</strong><span class="mono">${esc(t.name)} · :${t.port}</span></div>
      ${badge(t.status)}
    </div>
    <div class="mon-metrics">
      <div class="mon-m"><span class="k">CPU</span><span class="v mono">${t.cpu != null ? t.cpu + "%" : "—"}</span></div>
      <div class="mon-m"><span class="k">${isApp ? "内存" : "容器内存"}</span><span class="v mono">${mem}</span></div>
      ${isApp ? `<div class="mon-m"><span class="k">RSS</span><span class="v mono">${t.rssMb != null ? t.rssMb + "M" : "—"}</span></div>
      <div class="mon-m"><span class="k">进程</span><span class="v mono">${t.procs != null ? t.procs : "—"}</span></div>
      <div class="mon-m"><span class="k">运行</span><span class="v mono">${esc(t.uptime || "—")}</span></div>
      <div class="mon-m"><span class="k">探针</span><span class="v mono">${t.latencyMs != null ? t.latencyMs + "ms" : "—"}</span></div>` : ""}
    </div>
    ${sparkline(t.trend)}
  </div>`;
}

function renderMonitor(animate) {
  const M = S.monitor;
  if (!M) { $("#content").innerHTML = `<div class="empty-tip">采集加载中…</div>`; return; }
  const alerts = M.alerts || [];
  $("#content").innerHTML = `
    <div class="toolbar">
      <span style="font-size:12.5px;color:var(--text-2)">进程指标(ps) + 容器指标(docker stats) + 健康探针 · 采集于 <span class="mono">${esc(M.collectedAt)}</span></span>
      <span class="spacer"></span>
      <button class="btn btn-ghost btn-sm" data-act="mon-clear-alerts">${ic("i-broom")}清空告警</button>
      <button class="btn btn-ghost btn-sm" data-act="refresh">${ic("i-refresh")}立即刷新</button>
    </div>
    <div class="card mon-rules">
      <div class="mon-rules-head">${ic("i-alert")}<strong>告警规则</strong><span style="font-size:12px;color:var(--text-3)">阈值越限即产生告警事件（持久化 data/monitor.json）</span>
        <span class="spacer"></span><button class="btn btn-primary btn-sm" data-act="mon-rules-save">保存规则</button></div>
      ${(M.rules || []).map(r => `<div class="mon-rule">
        <label class="mon-rule-en"><input type="checkbox" id="rule-en-${esc(r.id)}" ${r.enabled ? "checked" : ""}>${esc(MON_METRIC[r.metric] || r.metric)}</label>
        ${r.metric !== "down" ? `<span class="mon-rule-thr">阈值 <input class="input input-sm" type="number" id="rule-thr-${esc(r.id)}" value="${r.threshold}" style="width:80px"> %</span>` : `<span class="mon-rule-thr" style="color:var(--text-3)">端口未监听即告警</span>`}
      </div>`).join("")}
    </div>
    <div class="grid-3">${M.targets.map((t, i) => monitorCard(t, animate, i)).join("")}</div>
    <div class="card table-card" style="margin-top:16px"><div class="table-scroll">
      <table class="audit"><thead><tr><th style="width:150px">时间</th><th style="width:150px">对象</th><th style="width:90px">指标</th><th>告警内容</th></tr></thead>
      <tbody>${alerts.length ? alerts.map(a => `<tr><td class="mono">${esc(a.time)}</td><td class="mono" style="color:var(--cyan)">${esc(a.target)}</td><td>${esc(MON_METRIC[a.metric] || a.metric)}</td><td class="detail">${esc(a.message)}</td></tr>`).join("") : `<tr><td colspan="4"><div class="empty-tip">暂无告警 · 系统平稳</div></td></tr>`}</tbody></table>
    </div></div>`;
}

/* ---------- 灰度发布 ---------- */
const REL_STATUS = { IDLE: "无 canary", STARTING: "canary 启动中", UP: "canary 就绪", FAILED: "canary 失败", PROMOTING: "晋升中" };
const relBadge = st => `<span class="badge ${st === "UP" ? "running" : st === "FAILED" ? "stopped" : st === "IDLE" ? "stopped" : "starting"}"><i class="dot"></i>${REL_STATUS[st] || st}</span>`;
const KIND_TEXT = { jar: "单 jar·可灰度", multi: "多进程·维护窗口", script: "脚本·维护窗口" };

function releaseCard(r, animate, i) {
  const canUp = r.canaryStatus === "UP";
  const canStart = r.canarySupported && !r.busy && !canUp && r.canaryStatus !== "STARTING";
  const canStop = canUp || r.canaryStatus === "STARTING";
  const mtime = r.jarMtime ? new Date(Number(r.jarMtime)).toLocaleString("zh-CN", { hour12: false }) : "—";
  return `<div class="card hoverable svc-card ${animate ? "enter" : ""}" style="${animate ? `animation-delay:${i * 40}ms` : ""}">
    <div class="svc-head">
      <span class="svc-icon" style="color:var(--cyan);border-color:rgba(34,211,238,.27);background:rgba(34,211,238,.08)">${ic("i-rocket")}</span>
      <div class="svc-title"><strong>${esc(r.label)}</strong><span class="mono">${esc(r.name)} · 主:${r.port} / canary:${r.canaryPort}</span></div>
      ${badge(r.mainStatus)}
    </div>
    <div class="rel-meta">
      <div class="rel-row"><span class="k">形态</span><span class="v">${esc(KIND_TEXT[r.kind] || r.kind)}</span></div>
      <div class="rel-row"><span class="k">git</span><span class="v mono">${esc(r.git || "—")}</span></div>
      <div class="rel-row"><span class="k">jar 指纹</span><span class="v mono">${esc(r.jarSha || "—")}${r.jarMb ? " · " + r.jarMb + "M" : ""}</span></div>
      <div class="rel-row"><span class="k">构建时间</span><span class="v mono">${esc(mtime)}</span></div>
      <div class="rel-row"><span class="k">canary</span><span class="v">${relBadge(r.canaryStatus)}</span></div>
    </div>
    <div class="rel-actions">
      <button class="btn btn-primary btn-sm" data-act="rel-canary-start" data-name="${esc(r.name)}" ${canStart ? "" : "disabled"}>${ic("i-play")}起 canary</button>
      <button class="btn btn-ghost btn-sm" data-act="rel-canary-stop" data-name="${esc(r.name)}" ${canStop ? "" : "disabled"}>${ic("i-stop")}停</button>
      <button class="btn btn-ghost btn-sm" data-act="rel-promote" data-name="${esc(r.name)}" ${canUp && !r.busy ? "" : "disabled"}>${ic("i-up")}晋升</button>
      <button class="btn btn-ghost btn-sm" data-act="rel-rollback" data-name="${esc(r.name)}" ${r.canaryStatus !== "IDLE" ? "" : "disabled"}>${ic("i-refresh")}回滚</button>
    </div>
    ${r.canarySupported ? "" : `<div class="rel-note">多进程/脚本应用不支持端口偏移 canary，请走维护窗口（应用管理页停→启）。</div>`}
  </div>`;
}

function renderRelease(animate) {
  $("#content").innerHTML = `
    <div class="toolbar">
      <span style="font-size:12.5px;color:var(--text-2)">版本台账(jar 指纹/git) + canary 健康门 + 晋升/回滚 · canary 日志在 <span class="mono">data/release/</span></span>
      <span class="spacer"></span>
      <button class="btn btn-ghost btn-sm" data-act="refresh">${ic("i-refresh")}立即刷新</button>
    </div>
    <div class="grid-3">${S.release.map((r, i) => releaseCard(r, animate, i)).join("")}</div>
    <div class="card table-card" style="margin-top:16px"><div class="table-scroll">
      <table class="audit"><thead><tr><th style="width:150px">时间</th><th style="width:120px">应用</th><th style="width:90px">动作</th><th style="width:80px">结果</th><th>详情</th></tr></thead>
      <tbody>${S.releaseHist.length ? S.releaseHist.map(h => `<tr><td class="mono">${esc(h.time)}</td><td class="mono" style="color:var(--cyan)">${esc(h.app)}</td><td>${esc(h.action)}</td><td>${resultTag(h.result)}</td><td class="detail">${esc(h.detail)}</td></tr>`).join("") : `<tr><td colspan="5"><div class="empty-tip">暂无发布记录</div></td></tr>`}</tbody></table>
    </div></div>`;
}

/* ---------- 操作分发 ---------- */
async function doAction(act, name) {
  try {
    if (act === "refresh") { await refreshData(); toast("状态已刷新", "success"); return; }
    if (act === "app-log") {
      S.log.source = name; S.log.file = ""; S.log.live = false;
      location.hash = "#/logs";
      return;
    }
    if (act === "app-start") {
      const r = await api(`/api/apps/${name}/start`, "POST");
      toast(r.message, "success");
    } else if (act === "app-stop") {
      const a = S.apps.find(x => x.name === name);
      const ok = await confirmDialog("停止应用", `确认停止 ${name}（${a ? a.label : ""}）？将执行 ${a ? a.stopScript : "stop.sh"} 优雅停机，必要时按端口兜底。数据不会被删除。`, "确认停止");
      if (!ok) return;
      const r = await api(`/api/apps/${name}/stop`, "POST");
      toast(r.message, "success");
    } else if (act === "mw-start") {
      const r = await api(`/api/middleware/${name}/start`, "POST");
      toast(r.message, "success");
    } else if (act === "mw-stop") {
      const ok = await confirmDialog("停止中间件", `确认停止 ${name}？容器停止后数据卷（claim-shared-*）完整保留，再次启动数据不丢失。`, "确认停止");
      if (!ok) return;
      const r = await api(`/api/middleware/${name}/stop`, "POST");
      toast(r.message, "success");
    } else if (act === "mw-start-all") {
      const r = await api("/api/middleware/start-all", "POST");
      toast(r.message, "success");
    } else if (act === "mw-stop-all") {
      const ok = await confirmDialog("停止全部中间件", `确认停止全部 ${S.mw.length} 个共享中间件服务？依赖它们的应用（claim / cs / ride / payment）相关功能将受影响。数据卷完整保留，不删除任何数据。`, "全部停止");
      if (!ok) return;
      const r = await api("/api/middleware/stop-all", "POST");
      toast(r.message, "success");
    } else if (act === "mon-rules-save") {
      const rules = (S.monitor.rules || []).map(r => ({
        id: r.id, metric: r.metric,
        threshold: r.metric === "down" ? 0 : parseFloat(($(`#rule-thr-${r.id}`) || {}).value || r.threshold),
        enabled: !!($(`#rule-en-${r.id}`) || {}).checked
      }));
      await apiJson("/api/monitor/rules", rules);
      toast("告警规则已保存", "success");
    } else if (act === "mon-clear-alerts") {
      await api("/api/monitor/alerts/clear", "POST");
      toast("告警已清空", "success");
    } else if (act === "rel-canary-start") {
      const ok = await confirmDialog("启动 canary", `为 ${name} 在端口偏移 +1000 启动一个 canary 实例（独立日志/pid，不影响主实例）。通过健康门后方可晋升。确认启动？`, "启动 canary");
      if (!ok) return;
      const r = await api(`/api/release/${name}/canary/start`, "POST");
      toast(r.message, "success");
    } else if (act === "rel-canary-stop") {
      const ok = await confirmDialog("停止 canary", `确认销毁 ${name} 的 canary 实例？主实例不受影响。`, "停止 canary");
      if (!ok) return;
      const r = await api(`/api/release/${name}/canary/stop`, "POST");
      toast(r.message, "success");
    } else if (act === "rel-promote") {
      const ok = await confirmDialog("灰度晋升", `确认晋升 ${name}？将停止主实例→用当前 jar 重启主实例→销毁 canary。期间主服务短暂中断。确认晋升？`, "确认晋升");
      if (!ok) return;
      const r = await api(`/api/release/${name}/promote`, "POST");
      toast(r.message, "success");
    } else if (act === "rel-rollback") {
      const ok = await confirmDialog("灰度回滚", `确认回滚 ${name}？保留主实例旧版本，仅销毁 canary。`, "确认回滚");
      if (!ok) return;
      const r = await api(`/api/release/${name}/rollback`, "POST");
      toast(r.message, "success");
    }
    setTimeout(refreshData, 600);
  } catch (e) {
    toast(e.message, "error");
    refreshData();
  }
}

document.addEventListener("click", e => {
  const copyEl = e.target.closest("[data-copy]");
  if (copyEl) { copyText(copyEl.getAttribute("data-copy")); return; }
  const actEl = e.target.closest("[data-act]");
  if (actEl && !actEl.disabled) doAction(actEl.getAttribute("data-act"), actEl.getAttribute("data-name"));
});

/* ---------- 路由 ---------- */
const PAGES = {
  overview: { title: "总览", sub: "系统健康 · 共享拓扑 · 实时动态" },
  apps: { title: "应用管理", sub: "六个业务应用 · 脚本启停 · 端口探测" },
  middleware: { title: "共享中间件", sub: "Docker Compose 统一管理 · claim-shared 数据卷" },
  tools: { title: "运维工具", sub: "网页版运维工具入口 · 端口探活 · 新标签页打开" },
  monitor: { title: "可观测中心", sub: "进程/容器指标 · 健康探针 · 告警规则 · 趋势" },
  release: { title: "灰度发布", sub: "版本台账 · canary 健康门 · 晋升/回滚" },
  logs: { title: "日志中心", sub: "尾部读取 · SSE 实时追踪 · 关键字过滤" },
  audit: { title: "操作审计", sub: "全部启停操作留痕 · 持久化保存" }
};

function route() {
  const page = (location.hash.replace("#/", "") || "overview").split("?")[0];
  S.page = PAGES[page] ? page : "overview";
  if (S.page !== "logs") closeStream();
  $$("#nav a").forEach(a => a.classList.toggle("active", a.dataset.page === S.page));
  $("#pageTitle").textContent = PAGES[S.page].title;
  $("#pageSub").textContent = PAGES[S.page].sub;
  $("#refreshHint").style.display = S.page === "logs" ? "none" : "";
  if (S.page === "overview") renderOverview(true);
  else if (S.page === "apps") renderApps(true);
  else if (S.page === "middleware") renderMiddleware(true);
  else if (S.page === "tools") renderTools(true);
  else if (S.page === "monitor") { api("/api/monitor").then(d => { S.monitor = d; renderMonitor(true); }).catch(e => toast(e.message, "error")); }
  else if (S.page === "release") { Promise.all([api("/api/release"), api("/api/release/history")]).then(([r, h]) => { S.release = r; S.releaseHist = h; renderRelease(true); }).catch(e => toast(e.message, "error")); }
  else if (S.page === "logs") { refreshSources().then(renderLogs); }
  else if (S.page === "audit") renderAudit();
  window.scrollTo(0, 0);
}

window.addEventListener("hashchange", route);

setInterval(() => {
  const d = new Date();
  $("#clock").textContent = d.toLocaleTimeString("zh-CN", { hour12: false });
}, 1000);

setInterval(async () => {
  S.pollTick++;
  try {
    if (S.page === "logs") {
      if (S.pollTick % 3 === 0) await refreshSources();
      const d = await api("/api/docker-status");
      S.docker = d; updateChrome();
    } else {
      await refreshData();
    }
  } catch (e) { /* 服务暂不可达时静默等待下一轮 */ }
}, 5000);

(async function init() {
  try { await refreshData(); } catch (e) { toast("后端接口暂不可达：" + e.message, "error"); }
  await refreshSources();
  if (!S.sources.some(s => s.id === S.log.source && s.files.length)) {
    const withFiles = S.sources.find(s => s.files.length);
    if (withFiles) { S.log.source = withFiles.id; S.log.file = withFiles.files[0].name; }
  }
  route();
})();