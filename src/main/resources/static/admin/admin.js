/*
 * 垃圾侠管理后台：零构建原生 JS（阶段 A 只做登录 + 框架）。
 *
 * 令牌放 sessionStorage（关闭标签页即失效），比 localStorage 更保守；
 * 服务端令牌本身也只有 2 小时且没有 refresh。
 */
const ADMIN_VERSION = "22";           // 与 index.html 里 admin.js?v=5 对应，改脚本时同步 +1
const TOKEN_KEY = "ljx.admin.token";
const $ = (id) => document.getElementById(id);

/**
 * 把错误显示到页面顶部。
 * 后台使用者通常不会打开浏览器控制台，所以任何未捕获的错误都必须显示出来，
 * 否则表现就是"点了没反应"，无法定位。
 */
function showBootError(message) {
  const box = $("boot-error");
  if (!box) return;
  box.hidden = false;
  box.textContent = message;
}

/** 执行轨迹：把最近几步动作与响应码显示在登录页，没有控制台也能看清卡在哪一步 */
const traceLines = [];
function trace(message) {
  traceLines.push(new Date().toLocaleTimeString() + "  " + message);
  while (traceLines.length > 8) traceLines.shift();
  const box = $("trace");
  if (box) box.textContent = traceLines.join(String.fromCharCode(10));
}

window.addEventListener("error", (event) => {
  showBootError(
    "脚本错误：" + (event.message || event.error) +
    " ｜ 位置：" + (event.filename || "?") + ":" + (event.lineno || 0),
  );
});
window.addEventListener("unhandledrejection", (event) => {
  showBootError("未处理的异步错误：" + (event.reason && event.reason.message ? event.reason.message : String(event.reason)));
});

/**
 * 令牌同时放内存与 sessionStorage：
 * - sessionStorage 让刷新页面后仍保持登录；
 * - 内存变量是兜底——个别浏览器/隐私策略下 sessionStorage 可能被禁用，
 *   只依赖它会导致"登录成功却立刻被判未登录"（本轮踩过类似现象，故双写）。
 */
let memoryToken = null;

function token() {
  if (memoryToken) return memoryToken;
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

function setToken(value) {
  memoryToken = value || null;
  try {
    if (value) sessionStorage.setItem(TOKEN_KEY, value);
    else sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    // sessionStorage 不可用时忽略：内存里那份仍能保证本次会话正常
  }
}

/** 统一请求：自动带令牌；令牌失效时回到登录页 */
async function api(method, path, body) {
  const headers = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const current = token();
  if (current) headers.Authorization = "Bearer " + current;

  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  let payload;
  try {
    payload = await res.json();
  } catch {
    throw new Error("服务器返回异常（HTTP " + res.status + "）");
  }

  // 1103 令牌失效 / 1104 未认证 → 一律退回登录
  trace(method + " " + path + " → code=" + payload.code);
  if (payload.code === 1103 || payload.code === 1104) {
    setToken(null);
    showLogin("登录状态已失效，请重新登录");
    throw new Error(payload.message);
  }
  if (payload.code !== 0) {
    // 带上 code，便于对照 ErrorCode：1103 令牌失效 / 1104 未认证 / 1102 密码错 …
    throw new Error((payload.message || "请求失败") + "（" + payload.code + "）");
  }
  return payload.data;
}

function showLogin(message) {
  $("app-view").hidden = true;
  $("login-view").hidden = false;
  const box = $("login-error");
  box.hidden = !message;
  box.textContent = message || "";
  $("password").value = "";
  // 表单内的小字容易被忽略：有错误时同时顶到页面横幅上
  if (message) {
    showBootError("登录失败：" + message);
  } else {
    const banner = $("boot-error");
    if (banner) banner.hidden = true;
  }
}

function showApp(admin) {
  $("login-view").hidden = true;
  $("app-view").hidden = false;
  $("admin-name").textContent = admin.username;
  const info = $("overview-info");
  info.innerHTML = "";
  const rows = [
    ["管理员", admin.username],
    ["管理员 ID", String(admin.id)],
    ["API 基地址", location.origin],
  ];
  for (const [k, v] of rows) {
    const dt = document.createElement("dt");
    dt.textContent = k;
    const dd = document.createElement("dd");
    dd.textContent = v;
    info.append(dt, dd);
  }
}

// ---------- 登录 ----------
$("login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const btn = $("login-btn");
  btn.disabled = true;
  btn.textContent = "登录中…";
  $("login-error").hidden = true;
  try {
    trace("提交登录表单…");
    const data = await api("POST", "/api/admin/auth/login", {
      username: $("username").value.trim(),
      password: $("password").value,
    });
    trace("拿到令牌：" + (data && data.token ? "有" : "无（异常）"));
    setToken(data.token);
    const me = await api("GET", "/api/admin/me");
    trace("校验通过，进入后台：" + me.username);
    showApp(me);
    if (me.mustChangePassword) {
      // 首次创建 / 被重置的账号：改密码之前除本接口外的管理调用都会被后端拒绝（1604），
      // 所以这里必须弹一个不可随意关闭的窗口，避免管理员以为"登录了就能用"。
      trace("该账号必须先修改密码");
      openPasswordModal(true);
    }
  } catch (error) {
    trace("登录流程中断：" + error.message);
    showLogin(error.message);
  } finally {
    btn.disabled = false;
    btn.textContent = "登录";
  }
});

// ---------- 退出 ----------
$("logout-btn").addEventListener("click", async () => {
  try {
    await api("POST", "/api/admin/auth/logout");
  } catch {
    // 登出失败不影响本地清理
  }
  setToken(null);
  showLogin("");
});

// ---------- 公告 ----------
async function loadAnnouncements() {
  try {
    renderAnnouncements(await api("GET", "/api/admin/announcements"));
    $("ann-hint").textContent = "";
  } catch (error) {
    $("ann-hint").textContent = error.message;
  }
}

function renderAnnouncements(list) {
  const tbody = $("ann-table").querySelector("tbody");
  tbody.replaceChildren();
  if (list.length === 0) {
    const tr = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = 4;
    td.className = "empty";
    td.textContent = "暂无公告（玩家端底部会显示默认的 QQ 群文案）";
    tr.append(td);
    tbody.append(tr);
    return;
  }
  for (const item of list) {
    const tr = document.createElement("tr");

    const content = document.createElement("td");
    content.textContent = item.content;

    const sort = document.createElement("td");
    sort.textContent = String(item.sortOrder);

    const state = document.createElement("td");
    state.textContent = item.enabled ? "已启用" : "已关闭";

    const actions = document.createElement("td");
    actions.className = "actions";
    const toggle = document.createElement("button");
    toggle.textContent = item.enabled ? "关闭" : "启用";
    toggle.addEventListener("click", () => patchAnnouncement(item, { enabled: !item.enabled }));
    const rename = document.createElement("button");
    rename.textContent = "改内容";
    rename.addEventListener("click", () => {
      const next = window.prompt("新的公告内容", item.content);
      if (next !== null && next.trim() !== "") patchAnnouncement(item, { content: next.trim() });
    });
    const del = document.createElement("button");
    del.textContent = "删除";
    del.className = "danger";
    del.addEventListener("click", () => {
      if (window.confirm("确定删除这条公告？")) removeAnnouncement(item.id);
    });
    actions.append(toggle, rename, del);

    tr.append(content, sort, state, actions);
    tbody.append(tr);
  }
}

async function patchAnnouncement(item, patch) {
  try {
    // 后端语义：null 表示字段不变，所以这里只提交要改的字段
    await api("PUT", "/api/admin/announcements/" + item.id, patch);
    await loadAnnouncements();
  } catch (error) {
    $("ann-hint").textContent = error.message;
  }
}

async function removeAnnouncement(id) {
  try {
    await api("DELETE", "/api/admin/announcements/" + id);
    await loadAnnouncements();
  } catch (error) {
    $("ann-hint").textContent = error.message;
  }
}

$("ann-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const content = $("ann-content").value.trim();
  if (!content) return;
  const sortOrder = Number($("ann-sort").value) || 0;
  try {
    await api("POST", "/api/admin/announcements", { content, sortOrder, enabled: true });
    $("ann-content").value = "";
    await loadAnnouncements();
  } catch (error) {
    $("ann-hint").textContent = error.message;
  }
});

// ---------- CDK（按批次归类） ----------
const cdkExpanded = new Set();   // 记住展开的批次，刷新后保持展开状态

async function loadCdk() {
  try {
    renderBatches(await api("GET", "/api/admin/cdk/batches"));
    $("cdk-hint").textContent = "";
  } catch (error) {
    $("cdk-hint").textContent = error.message;
  }
}

function cell(text, className) {
  const td = document.createElement("td");
  td.textContent = text;
  if (className) td.className = className;
  return td;
}

function renderBatches(list) {
  const tbody = $("cdk-table").querySelector("tbody");
  tbody.replaceChildren();
  if (list.length === 0) {
    const tr = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = 8;
    td.className = "empty";
    td.textContent = "还没有兑换码，用上面的表单批量生成";
    tr.append(td);
    tbody.append(tr);
    return;
  }
  for (const batch of list) {
    const tr = document.createElement("tr");
    tr.append(
      cell(batch.batchNo, "mono"),
      cell(batch.createdAt ? batch.createdAt.replace("T", " ").slice(0, 16) : "—"),
      cell(String(batch.total)),
      cell(String(batch.coins)),
      cell(batch.totalUses === 0 ? "不限次" : "单码 " + batch.totalUses + " 次"),
      cell(batch.usedCount + " / " + batch.enabledCount + " 启用"),
      cell(batch.expiresAt ? batch.expiresAt.slice(0, 10) : "永久"),
    );

    const actions = document.createElement("td");
    actions.className = "actions";
    const toggle = document.createElement("button");
    toggle.textContent = cdkExpanded.has(batch.batchNo) ? "收起" : "展开";
    toggle.addEventListener("click", () => {
      if (cdkExpanded.has(batch.batchNo)) cdkExpanded.delete(batch.batchNo);
      else cdkExpanded.add(batch.batchNo);
      void loadCdk();
    });
    const copy = document.createElement("button");
    copy.textContent = "复制全部";
    copy.addEventListener("click", () => copyBatch(batch.batchNo));
    actions.append(toggle, copy);
    tr.append(actions);

    tbody.append(tr);
    if (cdkExpanded.has(batch.batchNo)) {
      void appendDetail(tbody, batch.batchNo);
    }
  }
}

/** 展开某批：按需拉明细，避免一次把上千个码都查出来 */
async function appendDetail(tbody, batchNo) {
  const row = document.createElement("tr");
  row.className = "detail-row";
  const holder = document.createElement("td");
  holder.colSpan = 8;
  const inner = document.createElement("div");
  inner.className = "inner";
  inner.textContent = "正在加载该批明细…";
  holder.append(inner);
  row.append(holder);
  tbody.append(row);
  try {
    const list = await api("GET", "/api/admin/cdk/batches/" + encodeURIComponent(batchNo));
    inner.replaceChildren(renderDetail(list));
  } catch (error) {
    inner.textContent = error.message;
  }
}

function renderDetail(list) {
  const table = document.createElement("table");
  table.className = "table";
  const thead = document.createElement("thead");
  const headRow = document.createElement("tr");
  for (const label of ["兑换码", "钻石", "次数", "状态", "操作"]) {
    headRow.append(cell(label));
  }
  thead.append(headRow);
  const tbody = document.createElement("tbody");
  for (const item of list) {
    const tr = document.createElement("tr");
    tr.append(
      cell(item.code, "mono"),
      cell(String(item.coins)),
      cell(item.totalUses === 0 ? "不限次" : item.usedUses + "/" + item.totalUses),
      cell(item.enabled ? "启用" : "已停用"),
    );
    const actions = document.createElement("td");
    actions.className = "actions";
    const toggle = document.createElement("button");
    toggle.textContent = item.enabled ? "停用" : "启用";
    toggle.addEventListener("click", () => cdkToggle(item));
    const del = document.createElement("button");
    del.textContent = "删除";
    del.className = "danger";
    del.addEventListener("click", () => {
      if (window.confirm("确定删除该兑换码？（已有人领取过则不允许删除，只能停用）")) {
        cdkDelete(item);
      }
    });
    actions.append(toggle, del);
    tr.append(actions);
    tbody.append(tr);
  }
  table.append(thead, tbody);
  return table;
}

/** 复制整批码（需要先拉一次该批明细） */
async function copyBatch(batchNo) {
  try {
    const list = await api("GET", "/api/admin/cdk/batches/" + encodeURIComponent(batchNo));
    const text = list.map((one) => one.code).join(String.fromCharCode(10));
    $("cdk-batch-no").textContent = batchNo;
    $("cdk-codes").value = text;
    $("cdk-batch").hidden = false;
    try {
      await navigator.clipboard.writeText(text);
      $("cdk-hint").textContent = "已复制 " + list.length + " 个兑换码（批次 " + batchNo + "）";
    } catch {
      $("cdk-codes").select();
      $("cdk-hint").textContent = "自动复制不可用，已全选，请按 Ctrl+C 复制";
    }
  } catch (error) {
    $("cdk-hint").textContent = error.message;
  }
}

async function cdkToggle(item) {
  try {
    await api("PUT", "/api/admin/cdk/" + item.id, { enabled: !item.enabled });
    await loadCdk();
  } catch (error) {
    $("cdk-hint").textContent = error.message;
  }
}

async function cdkDelete(item) {
  try {
    await api("DELETE", "/api/admin/cdk/" + item.id);
    await loadCdk();
  } catch (error) {
    $("cdk-hint").textContent = error.message;
  }
}

$("cdk-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const body = {
    count: Number($("cdk-count").value) || 1,
    coins: Number($("cdk-coins").value) || 1,
    totalUses: Number($("cdk-uses").value) || 0,
    validDays: $("cdk-days").value ? Number($("cdk-days").value) : null,
  };
  try {
    const batch = await api("POST", "/api/admin/cdk/batches", body);
    $("cdk-batch-no").textContent = batch.batchNo;
    $("cdk-codes").value = batch.items.map((one) => one.code).join(String.fromCharCode(10));
    $("cdk-batch").hidden = false;
    cdkExpanded.add(batch.batchNo);
    $("cdk-hint").textContent = "";
    await loadCdk();
  } catch (error) {
    $("cdk-hint").textContent = error.message;
  }
});

$("cdk-copy").addEventListener("click", async () => {
  const text = $("cdk-codes").value;
  const total = text ? text.split(String.fromCharCode(10)).length : 0;
  try {
    await navigator.clipboard.writeText(text);
    $("cdk-hint").textContent = "已复制 " + total + " 个兑换码，发给玩家即可";
  } catch {
    $("cdk-codes").select();
    $("cdk-hint").textContent = "自动复制不可用，已全选，请按 Ctrl+C 复制";
  }
});

// ---------- 数据库（只看现状 / 试连 / 生成片段） ----------
async function loadDatabase() {
  try {
    const status = await api("GET", "/api/admin/database");

    // ① 当前用的是什么库：后端已经把"类型 + 风险 + 建议"算好，这里只负责展示
    $("db-kind-badge").textContent = status.kind;
    $("db-kind-hint").textContent = status.kindHint;
    const card = $("db-kind");
    card.className = "kind-card "
      + (status.kind === "MySQL" ? "ok" : (status.persistent ? "warn" : "danger"));

    // 两列明细
    const box = $("db-status");
    box.replaceChildren();
    const rows = [
      ["JDBC URL", status.jdbcUrl || "—"],
      ["用户名", status.username || "—"],
      ["驱动类", status.driverClass || "—"],
      ["数据库产品", (status.productName || "—") + " " + (status.productVersion || "")],
      ["数据是否持久", status.persistent ? "是（重启不丢）" : "否 —— 重启后数据全部丢失"],
      ["生效 profile", status.profiles || "(默认)"],
      ["Flyway 版本", status.flywayVersion || "—"],
      ["已应用迁移数", String(status.appliedMigrations)],
      ["连接池使用", "活动 " + status.poolActive + " / 空闲 " + status.poolIdle
        + " / 共 " + status.poolTotal + " / 上限 " + status.poolMax],
      ["连接池状态", status.poolState],
    ];
    for (const [label, value] of rows) {
      const dt = document.createElement("dt");
      dt.textContent = label;
      const dd = document.createElement("dd");
      dd.textContent = value;
      box.append(dt, dd);
    }
    $("db-hint").textContent = "";
  } catch (error) {
    $("db-hint").textContent = error.message;
  }
}

function dbRequest() {
  return {
    host: $("db-host").value.trim(),
    port: Number($("db-port").value) || 3306,
    database: $("db-name").value.trim(),
    username: $("db-user").value.trim(),
    password: $("db-pass").value,
  };
}

$("db-test").addEventListener("click", async () => {
  const payload = dbRequest();
  if (!payload.host || !payload.database || !payload.username) {
    $("db-hint").textContent = "请先填写主机、数据库名与用户名";
    return;
  }
  $("db-hint").textContent = "正在测试连接…（最多等 5 秒）";
  try {
    const result = await api("POST", "/api/admin/database/test", payload);
    $("db-hint").textContent = (result.ok ? "✓ " : "✗ ") + result.message
      + "（耗时 " + result.elapsedMs + " ms）";
  } catch (error) {
    $("db-hint").textContent = error.message;
  }
});

$("db-snippet").addEventListener("click", async () => {
  try {
    const result = await api("POST", "/api/admin/database/snippet", dbRequest());
    $("db-snippet-text").value = result.yaml;
    $("db-snippet-box").hidden = false;
    $("db-hint").textContent = result.note;
  } catch (error) {
    $("db-hint").textContent = error.message;
  }
});

$("db-copy").addEventListener("click", async () => {
  const text = $("db-snippet-text").value;
  try {
    await navigator.clipboard.writeText(text);
    $("db-hint").textContent = "已复制配置片段";
  } catch {
    $("db-snippet-text").select();
    $("db-hint").textContent = "自动复制不可用，已全选，请按 Ctrl+C 复制";
  }
});

// ---------- 商品管理 ----------
async function loadShop() {
  try {
    const [items, plans, topCards, vips] = await Promise.all([
      api("GET", "/api/admin/shop/items"),
      api("GET", "/api/admin/shop/vip-plans"),
      api("GET", "/api/admin/shop/effects/top-cards"),
      api("GET", "/api/admin/shop/effects/vips"),
    ]);
    renderShopItems(items);
    renderVipPlans(plans);
    renderEffects(topCards, vips);
    $("shop-hint").textContent = "";
  } catch (error) {
    $("shop-hint").textContent = error.message;
  }
}

function numInput(value, step) {
  const input = document.createElement("input");
  input.type = "number";
  input.className = "num";
  input.min = "0";
  if (step) input.step = String(step);
  // 空值代表"永久"（后端约定 durationDays=null）
  input.value = value === null || value === undefined ? "" : String(value);
  return input;
}

function renderShopItems(items) {
  const tbody = $("shop-table").querySelector("tbody");
  tbody.replaceChildren();
  for (const item of items) {
    const tr = document.createElement("tr");
    const name = cell(item.name);

    const priceCell = document.createElement("td");
    const priceInput = numInput(item.priceCoins);
    priceCell.append(priceInput);

    const daysCell = document.createElement("td");
    const daysInput = numInput(item.durationDays);
    daysCell.append(daysInput);

    tr.append(name, priceCell, daysCell, cell(item.enabled ? "上架中" : "已下架"));

    const actions = document.createElement("td");
    actions.className = "actions";
    const save = document.createElement("button");
    save.textContent = "保存";
    save.addEventListener("click", () => patchShopItem(item, {
      priceCoins: Number(priceInput.value) || 0,
      // 留空 = 永久：转成 0 交给后端会变成"0 天立刻过期"，所以这里语义上仍传天数，
      // 由后端 update() 只在非 null 时写入；空串时传 null 表示"不变"不合适，
      // 因此这里用 0 表示永久（见 V??：后端把 <=0 视为永久）
      durationDays: daysInput.value.trim() === "" ? 0 : Number(daysInput.value) || 0,
    }));
    const shelf = document.createElement("button");
    shelf.textContent = item.enabled ? "下架" : "上架";
    shelf.addEventListener("click", () => patchShopItem(item, { enabled: !item.enabled }));
    actions.append(save, shelf);

    tr.append(actions);
    tbody.append(tr);
  }
}

function renderVipPlans(plans) {
  const tbody = $("plan-table").querySelector("tbody");
  tbody.replaceChildren();
  for (const plan of plans) {
    const tr = document.createElement("tr");
    tr.append(cell(plan.name + " LV" + plan.level));

    const priceCell = document.createElement("td");
    const priceInput = numInput(plan.priceCoins);
    priceCell.append(priceInput);

    const daysCell = document.createElement("td");
    const daysInput = numInput(plan.durationDays);
    daysCell.append(daysInput);

    const capCell = document.createElement("td");
    const capInput = numInput(plan.packageMaxMb);
    capCell.append(capInput);

    tr.append(priceCell, daysCell, capCell,
      cell(plan.borderUrl || "—", "mono"), cell(plan.iconUrl || "—", "mono"));

    const actions = document.createElement("td");
    actions.className = "actions";
    if (plan.level === 0) {
      actions.append(cell("默认档位"));
    } else {
      const save = document.createElement("button");
      save.textContent = "保存";
      save.addEventListener("click", async () => {
        try {
          await api("PUT", "/api/admin/shop/vip-plans/" + plan.level, {
            priceCoins: Number(priceInput.value) || 0,
            durationDays: daysInput.value.trim() === "" ? 0 : Number(daysInput.value) || 0,
            packageMaxMb: Number(capInput.value) || 0,
          });
          await loadShop();
          $("shop-hint").textContent = "已保存（时长即购买后的有效期，立刻生效）";
        } catch (error) {
          $("shop-hint").textContent = error.message;
        }
      });
      actions.append(save);
    }
    tr.append(actions);
    tbody.append(tr);
  }
}

function renderEffects(topCards, vips) {
  const tbody = $("effect-table").querySelector("tbody");
  tbody.replaceChildren();
  if (topCards.length === 0 && vips.length === 0) {
    const tr = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = 5;
    td.className = "empty";
    td.textContent = "当前没有生效中的效果";
    tr.append(td);
    tbody.append(tr);
    return;
  }
  for (const card of topCards) {
    tbody.append(effectRow("置顶卡", card.roomName + "（#" + card.roomId + "）", card.ownerName,
      card.expiresAt, () => editEffectExpiry("top-cards", card.roomId, card.expiresAt)));
  }
  for (const vip of vips) {
    tbody.append(effectRow("VIP", vip.vipName + "（" + vip.username + "）", "账号#" + vip.accountId,
      vip.expiresAt, () => editEffectExpiry("vips", vip.accountId, vip.expiresAt)));
  }
}

function effectRow(type, target, owner, expiresAt, onEdit) {
  const tr = document.createElement("tr");
  tr.append(
    cell(type),
    cell(target),
    cell(owner),
    cell(expiresAt ? String(expiresAt).replace("T", " ").slice(0, 16) : "永久"),
  );
  const actions = document.createElement("td");
  actions.className = "actions";
  const edit = document.createElement("button");
  edit.textContent = "改到期";
  edit.addEventListener("click", onEdit);
  const revoke = document.createElement("button");
  revoke.textContent = "撤销";
  revoke.className = "danger";
  revoke.addEventListener("click", () => {
    if (window.confirm("确定立即撤销这个效果？")) {
      void onEdit(true);
    }
  });
  actions.append(edit, revoke);
  tr.append(actions);
  return tr;
}

/** 传 revoke=true 或把到期时间留空即撤销 */
async function editEffectExpiry(kind, id, expiresAt, revoke) {
  let value = null;
  if (revoke !== true) {
    const input = window.prompt("到期时间（格式 2026-12-31T00:00:00）；留空 = 立即撤销",
      String(expiresAt || "").slice(0, 19));
    if (input === null) return;
    value = input.trim() === "" ? null : input.trim();
  }
  try {
    await api("PUT", "/api/admin/shop/effects/" + kind + "/" + id, { expiresAt: value });
    await loadShop();
  } catch (error) {
    $("shop-hint").textContent = error.message;
  }
}

// ---------- 等级 ----------
async function loadLevels() {
  try {
    const data = await api("GET", "/api/admin/levels");
    $("level-daily-exp").value = String(data.dailyLoginExp);
    renderLevels(data.levels);
    $("level-hint").textContent = "";
  } catch (error) {
    $("level-hint").textContent = error.message;
  }
}

function renderLevels(levels) {
  const tbody = $("level-table").querySelector("tbody");
  tbody.replaceChildren();
  for (const cfg of levels) {
    const tr = document.createElement("tr");
    tr.append(cell("LV" + cfg.level));

    // 经验与上传上限都在表格里直接改（原先用连续两个 prompt 问，第二个弹窗很容易被忽略，
    // 看起来就像"只能改经验"）
    const expCell = document.createElement("td");
    if (cfg.top) {
      expCell.append(document.createTextNode("已封顶（不再升级）"));
    } else {
      const expInput = numInput(cfg.expToNext);
      expInput.title = "升到下一级需要多少经验";
      expCell.append(expInput);
    }
    tr.append(expCell);

    const capCell = document.createElement("td");
    const capInput = numInput(cfg.uploadMb);
    capInput.title = "该等级客户端压缩包上传上限（MB）";
    capCell.append(capInput);
    tr.append(capCell);

    tr.append(cell(cfg.note || "—"));

    const actions = document.createElement("td");
    actions.className = "actions";
    const save = document.createElement("button");
    save.textContent = "保存";
    save.addEventListener("click", async () => {
      const body = { uploadMb: Number(capInput.value) || 0 };
      if (!cfg.top) {
        body.expToNext = Number(expCell.querySelector("input").value) || 0;
      }
      try {
        await api("PUT", "/api/admin/levels/" + cfg.level, body);
        await loadLevels();
        $("level-hint").textContent = "LV" + cfg.level + " 已保存（配额是读时取配置，立刻生效）";
      } catch (error) {
        $("level-hint").textContent = error.message;
      }
    });
    actions.append(save);
    tr.append(actions);
    tbody.append(tr);
  }
}

$("level-daily-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    await api("PUT", "/api/admin/levels/daily-exp", {
      exp: Number($("level-daily-exp").value) || 0,
    });
    $("level-hint").textContent = "每日登录经验已保存";
    await loadLevels();
  } catch (error) {
    $("level-hint").textContent = error.message;
  }
});

$("level-add-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    await api("POST", "/api/admin/levels", {
      level: Number($("level-new-level").value) || 0,
      expToNext: Number($("level-new-exp").value) || 0,
      uploadMb: Number($("level-new-upload").value) || 0,
    });
    await loadLevels();
    $("level-hint").textContent = "已新增等级";
  } catch (error) {
    $("level-hint").textContent = error.message;
  }
});

// ---------- 存储 ----------
function formatBytes(bytes) {
  if (bytes === null || bytes === undefined || bytes < 0) return "未知";
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + " MB";
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + " GB";
}

async function loadStorage() {
  try {
    const info = await api("GET", "/api/admin/storage");
    const box = $("storage-info");
    box.replaceChildren();
    const rows = [
      ["存储根目录", info.dir],
      ["是否临时目录", info.tempDir ? "是（重启会丢包）" : "否（持久）"],
      ["已上传压缩包", info.packageFiles + " 个"],
      ["占用空间", formatBytes(info.packageBytes)],
      ["房间总数", String(info.roomCount)],
      ["孤儿包", info.orphanFiles + " 个（房间已删除但文件还在）"],
      ["磁盘剩余", formatBytes(info.freeBytes)],
    ];
    for (const [label, value] of rows) {
      const dt = document.createElement("dt");
      dt.textContent = label;
      const dd = document.createElement("dd");
      dd.textContent = value;
      box.append(dt, dd);
    }

    const alertBox = $("storage-alert");
    if (info.tempDir) {
      alertBox.hidden = false;
      $("storage-alert-text").textContent =
        "当前存储在系统临时目录：" + info.dir +
        "。服务器重启或系统清理会删除这里的客户端压缩包，请把环境变量 LJX_STORAGE_DIR 指向数据盘后重启后端。";
    } else {
      alertBox.hidden = true;
    }
  } catch (error) {
    $("storage-info").replaceChildren();
    const dt = document.createElement("dt");
    dt.textContent = "读取失败";
    const dd = document.createElement("dd");
    dd.textContent = error.message;
    $("storage-info").append(dt, dd);
  }
}

// ---------- 邮件（SMTP） ----------
async function loadMail() {
  try {
    const cfg = await api("GET", "/api/admin/mail");
    $("mail-enabled").value = String(cfg.enabled);
    $("mail-host").value = cfg.host || "";
    $("mail-port").value = String(cfg.port || 465);
    $("mail-username").value = cfg.username || "";
    $("mail-from").value = cfg.from || "";
    $("mail-ssl").value = String(cfg.sslEnabled);
    // 密码永不回显：只提示是否已设置
    $("mail-password").value = "";
    $("mail-password").placeholder = cfg.passwordSet ? "已设置（留空保持不变）" : "未设置";

    const card = $("mail-source");
    const text = $("mail-source-text");
    if (cfg.configured) {
      card.className = "kind-card ok";
      text.textContent = "当前生效来源：" + cfg.source
        + "（验证码会真实发送）。改完保存立刻生效，不需要重启后端。";
    } else {
      card.className = "kind-card warn";
      text.textContent = "尚未配置 SMTP：验证码会打印到后端日志。"
        + "在下面填好后点保存即生效（QQ 邮箱填授权码，不是登录密码）。";
    }
    $("mail-hint").textContent = cfg.envProvided
      ? "检测到环境变量里已提供 SMTP 凭据，会优先使用环境变量，后台这里的配置不生效。"
      : "";
  } catch (error) {
    $("mail-hint").textContent = error.message;
  }
}

$("mail-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const password = $("mail-password").value;
  try {
    await api("PUT", "/api/admin/mail", {
      enabled: $("mail-enabled").value === "true",
      host: $("mail-host").value.trim(),
      port: Number($("mail-port").value) || 465,
      username: $("mail-username").value.trim(),
      // 留空表示保持原值（面板不回显密码，所以不能要求每次重填）
      password: password === "" ? null : password,
      from: $("mail-from").value.trim(),
      sslEnabled: $("mail-ssl").value === "true",
    });
    await loadMail();
    $("mail-hint").textContent = "已保存并立刻生效";
  } catch (error) {
    $("mail-hint").textContent = error.message;
  }
});

$("mail-test-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const to = $("mail-test-to").value.trim();
  if (!to) {
    $("mail-hint").textContent = "请先填写收件地址";
    return;
  }
  $("mail-hint").textContent = "正在发送…";
  try {
    const res = await api("POST", "/api/admin/mail/test", { to });
    $("mail-hint").textContent = (res.ok ? "✓ " : "✗ ") + res.message;
  } catch (error) {
    $("mail-hint").textContent = error.message;
  }
});

// ---------- 公告轮播间隔 ----------
async function loadRotateSeconds() {
  try {
    const seconds = await api("GET", "/api/admin/announcements/rotate-seconds");
    $("ann-rotate").value = String(seconds);
    $("ann-rotate-hint").textContent =
      "当前：玩家端底部每条公告停留 " + seconds + " 秒（客户端下次拉取公告即生效）";
  } catch (error) {
    $("ann-rotate-hint").textContent = error.message;
  }
}

$("ann-rotate-save").addEventListener("click", async () => {
  const seconds = Number($("ann-rotate").value);
  if (!Number.isFinite(seconds) || seconds < 3 || seconds > 120) {
    $("ann-rotate-hint").textContent = "轮播间隔需在 3-120 秒之间";
    return;
  }
  try {
    const saved = await api("PUT", "/api/admin/announcements/rotate-seconds", { seconds });
    $("ann-rotate-hint").textContent = "已保存：每条公告停留 " + saved + " 秒";
  } catch (error) {
    $("ann-rotate-hint").textContent = error.message;
  }
});

// ---------- 修改密码 ----------
// 是否处于"必须改密码"的强制模式：此时不允许取消 / 点遮罩关闭
let passwordChangeForced = false;

function openPasswordModal(forced) {
  passwordChangeForced = !!forced;
  $("pwd-old").value = "";
  $("pwd-new").value = "";
  $("pwd-new2").value = "";
  $("pwd-hint").textContent = forced
    ? "当前账号必须先修改密码才能使用后台（除本操作外的管理接口都会被拒绝）"
    : "";
  $("pwd-hint").className = forced ? "hint warn" : "hint";
  $("pwd-modal-title").textContent = forced ? "请先修改管理员密码" : "修改管理员密码";
  $("pwd-cancel").hidden = forced;        // 强制模式下没有"取消"
  $("pwd-modal").hidden = false;
  $("pwd-old").focus();
}

$("change-pwd-btn").addEventListener("click", () => openPasswordModal(false));

$("pwd-cancel").addEventListener("click", () => {
  if (passwordChangeForced) return;
  $("pwd-modal").hidden = true;
});

// 点遮罩关闭（强制模式下无效）
$("pwd-modal").addEventListener("click", (event) => {
  if (passwordChangeForced) return;
  if (event.target === $("pwd-modal")) $("pwd-modal").hidden = true;
});

$("pwd-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const oldPassword = $("pwd-old").value;
  const newPassword = $("pwd-new").value;
  const again = $("pwd-new2").value;
  if (newPassword.length < 8) {
    $("pwd-hint").textContent = "新密码至少 8 位";
    return;
  }
  if (newPassword !== again) {
    $("pwd-hint").textContent = "两次输入的新密码不一致";
    return;
  }
  try {
    await api("PUT", "/api/admin/password", { oldPassword, newPassword });
    $("pwd-modal").hidden = true;
    if (passwordChangeForced) {
      passwordChangeForced = false;
      alert("密码已修改，现在可以正常使用后台了。已签发的令牌最长 2 小时后失效。");
      // 重新拉一次 /me，把面板按"已改密码"的状态加载出来
      const me = await api("GET", "/api/admin/me");
      showApp(me);
      return;
    }
    alert("密码已修改。已签发的令牌最长 2 小时后失效，请留意其他设备上的登录状态。");
  } catch (error) {
    $("pwd-hint").textContent = error.message;
  }
});

// ---------- 面板切换 ----------
$("nav").addEventListener("click", (event) => {
  const button = event.target.closest(".nav-item");
  if (!button) return;
  for (const item of document.querySelectorAll(".nav-item")) {
    item.classList.toggle("active", item === button);
  }
  for (const panel of document.querySelectorAll(".panel")) {
    panel.hidden = panel.id !== "panel-" + button.dataset.panel;
  }
  // 切到公告面板时拉一次最新数据（其他面板后续阶段同样在这里懒加载）
  if (button.dataset.panel === "announcement") {
    void loadRotateSeconds();
  }
  if (button.dataset.panel === "announcement") void loadAnnouncements();
  if (button.dataset.panel === "shop") void loadShop();
  if (button.dataset.panel === "level") void loadLevels();
  if (button.dataset.panel === "cdk") void loadCdk();
  if (button.dataset.panel === "mail") void loadMail();
  if (button.dataset.panel === "storage") void loadStorage();
  if (button.dataset.panel === "database") void loadDatabase();
});

// ---------- 启动：有令牌就先验一次 ----------
(async () => {
  document.body.dataset.adminReady = "1";  // 能在控制台/DOM 里看到，用于确认 JS 已执行
  const verBox = $("ver");
  if (verBox) verBox.textContent = "v" + ADMIN_VERSION;
  trace("脚本已加载 v" + ADMIN_VERSION + "，令牌=" + (token() ? "有" : "无"));
  if (!token()) {
    showLogin("");
    return;
  }
  try {
    showApp(await api("GET", "/api/admin/me"));
  } catch {
    // api() 内部已切回登录页
  }
})();
