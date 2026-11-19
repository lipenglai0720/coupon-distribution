
"use strict";

const $ = selector => document.querySelector(selector);
const money = cents => (Number(cents) / 100).toFixed(2);
const escapeHtml = value => String(value ?? "").replace(/[&<>"']/g,
  character => ({"&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;", "'":"&#39;"})[character]);
const statuses = {
  ACTIVE: "进行中", ENDED: "已结束", AVAILABLE: "可使用", LOCKED: "已锁定",
  USED: "已核销", EXPIRED: "已过期", PENDING: "待处理", SUCCESS: "成功", FAILED: "失败",
  PROCESSING: "处理中", COMPLETED: "已完成", PARSE_FAILED: "解析失败",
  COMPLETED_WITH_FAILURES: "部分失败"
};
const label = code => statuses[code] || code;
let token = sessionStorage.getItem("couponSession");
let identity;
let features;
let coupons = [];
let toastTimer;

function toast(message) {
  $("#toast").textContent = message;
  $("#toast").hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { $("#toast").hidden = true; }, 4500);
}

async function api(path, method = "GET", data) {
  const headers = {};
  if (token) headers.Authorization = "Bearer " + token;
  if (features?.demoMode && !token) headers["X-User-Id"] = $("#user").value;
  const multipart = data instanceof FormData;
  if (data && !multipart) headers["Content-Type"] = "application/json";
  const response = await fetch("/api" + path, {
    method, headers, body: multipart ? data : data === undefined ? undefined : JSON.stringify(data)
  });
  const body = await response.text();
  const decoded = body ? JSON.parse(body) : null;
  if (!response.ok) {
    if (response.status === 401 && path !== "/auth/login") clearSession();
    throw Error(decoded?.message || (response.status === 401 ? "请先登录" : "请求失败"));
  }
  return decoded;
}

function selectTab(id) {
  document.querySelectorAll("#workspace section").forEach(section => { section.hidden = section.id !== id; });
  document.querySelectorAll("[data-tab]").forEach(button => {
    button.classList.toggle("active", button.dataset.tab === id);
    button.setAttribute("aria-current", button.dataset.tab === id ? "page" : "false");
  });
}

function clearSession() {
  token = null;
  identity = null;
  sessionStorage.removeItem("couponSession");
  $("#workspace").hidden = true;
  $("#sessionBar").hidden = true;
  $("#login").hidden = false;
  for (const selector of ["#templateList", "#walletList", "#claimHistory", "#taskResult", "#orderResult", "#recentTasks"]) {
    $(selector).replaceChildren();
  }
  $("#batchForm").reset();
  $("#queryTaskForm").reset();
  $("#retryTask").hidden = true;
  $("#redemptionForm").reset();
  coupons = [];
}

function merchant() { return features.demoMode || identity?.role === "MERCHANT"; }
function customer() { return features.demoMode || identity?.role === "CUSTOMER"; }

async function openWorkspace() {
  $("#login").hidden = true;
  $("#workspace").hidden = false;
  $("#sessionBar").hidden = false;
  $("#logout").hidden = !token;
  $("#demoIdentity").hidden = !features.demoMode;
  const actor = identity ? (merchant() ? "商家 " : "用户 ") + identity.subjectId + " · " + identity.username : "本地演示";
  $("#actor").textContent = actor;
  document.querySelectorAll("[data-merchant]").forEach(element => { element.hidden = !merchant(); });
  document.querySelectorAll("[data-customer]").forEach(element => { element.hidden = !customer(); });
  const merchantInput = $("#createForm [name=merchantId]");
  merchantInput.readOnly = !features.demoMode;
  if (identity?.role === "MERCHANT") merchantInput.value = identity.subjectId;
  selectTab("templates");
  await refresh();
}

function renderTemplate(template) {
  const id = escapeHtml(template.id);
  const active = template.status === "ACTIVE";
  const claim = customer() ? '<button data-claim="' + id + '"' +
      (!active || template.remaining_stock < 1 ? " disabled" : "") + '>领取</button>' : "";
  const end = merchant() && active
    ? '<button class="secondary" data-end="' + id + '">结束活动</button>' : "";
  return '<article class="coupon"><span class="pill">#' + id + ' · ' + escapeHtml(label(template.status))
    + '</span><h3>' + escapeHtml(template.name) + '</h3><p>商家 ' + escapeHtml(template.merchant_id)
    + ' · 剩余 ' + escapeHtml(template.remaining_stock) + ' / ' + escapeHtml(template.total_stock)
    + '</p><div class="value">¥' + money(template.discount_cents) + ' <small>满 ¥'
    + money(template.threshold_cents) + ' 可用</small></div><div class="actions">' + claim + end + '</div></article>';
}

function renderCoupon(coupon) {
  const id = escapeHtml(coupon.id);
  let actions = "";
  if (coupon.effective_status === "AVAILABLE") actions = '<button data-lock="' + id + '">下单锁券</button>';
  if (coupon.status === "LOCKED") {
    actions = '<button class="secondary" data-action="unlock" data-id="' + id + '">取消锁券</button>';
    if (features.demoMode) actions += '<button data-action="redeem" data-id="' + id + '">支付核销</button>';
  }
  if (coupon.status === "USED" && features.demoMode) {
    actions = '<button data-action="refund" data-id="' + id + '">全额退款退券</button>';
  }
  return '<article class="coupon"><span class="pill">' + escapeHtml(label(coupon.effective_status))
    + '</span><h3>' + escapeHtml(coupon.name) + '</h3><p>券编号 ' + id
    + (coupon.order_id ? ' · 订单 ' + escapeHtml(coupon.order_id) : '') + '</p>'
    + '<div class="value">¥' + money(coupon.discount_cents) + '</div><div class="actions">' + actions + '</div></article>';
}

async function refresh() {
  const [templates, status, wallet, claims] = await Promise.all([
    api("/templates"), api("/status"), customer() ? api("/wallet") : [], customer() ? api("/claims") : []
  ]);
  features = status;
  coupons = wallet;
  $("#templateCount").textContent = templates.length;
  $("#walletCount").textContent = wallet.length;
  $("#templateList").innerHTML = templates.map(renderTemplate).join("")
    || '<div class="empty">暂无优惠券</div>';
  $("#walletList").innerHTML = wallet.map(renderCoupon).join("") || '<div class="empty">暂无领取记录</div>';
  $("#claimHistory").textContent = claims.map(claim => [
    claim.request_id, label(claim.state), claim.error
  ].filter(Boolean).join(" · ")).join("\n") || "暂无异步领取记录";
  $("#statusJson").textContent = [
    "身份模式：" + (status.demoMode ? "本地演示" : "账号登录"),
    "异步发券：" + (status.mqEnabled ? "已启用" : "未启用"),
    "领券预占：" + (status.redisEnabled ? "已启用" : "未启用")
  ].join("\n");
}

async function claim(templateId) {
  const asyncClaim = features.redisEnabled && features.mqEnabled;
  const request = await api(asyncClaim ? "/claims/async" : "/claims", "POST", {
    templateId, requestId: crypto.randomUUID()
  });
  if (!asyncClaim) { toast("领取成功"); return; }
  let result = request;
  for (let attempt = 0; attempt < 40 && result.state === "PENDING"; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 300));
    result = await api("/claims/" + encodeURIComponent(request.request_id));
  }
  toast(result.state === "SUCCESS" ? "领取成功" :
    result.state === "FAILED" ? result.error : "仍在处理中，请查看领取记录");
}

async function run(button, task) {
  button.disabled = true;
  try { await task(); } catch (error) { toast(error.message); }
  finally { button.disabled = false; }
}

document.querySelectorAll("[data-tab]").forEach(button => {
  button.onclick = () => {
    selectTab(button.dataset.tab);
    if (button.dataset.tab === "batch") refreshTasks().catch(error => toast(error.message));
  };
});

$("#workspace").addEventListener("click", event => {
  const button = event.target.closest("[data-claim], [data-end], [data-lock], [data-action]");
  if (!button) return;
  run(button, async () => {
    if (button.dataset.claim) await claim(Number(button.dataset.claim));
    if (button.dataset.end) {
      if (!confirm("结束后将停止新的领取，已发券可继续在有效期内使用。")) return;
      await api("/templates/" + button.dataset.end + "/end", "POST");
      toast("活动已结束");
    }
    if (button.dataset.lock) {
      const coupon = coupons.find(entry => entry.id === button.dataset.lock);
      const orderId = prompt("请输入订单号，供商家查询核销", "ORDER-" + Date.now());
      if (!orderId) return;
      const amount = prompt("请输入订单金额（元）", money(Math.max(1, coupon.threshold_cents)));
      if (amount === null) return;
      const amountCents = Math.round(Number(amount) * 100);
      if (!Number.isSafeInteger(amountCents) || amountCents < 1) throw Error("请输入有效订单金额");
      await api("/coupons/" + coupon.id + "/lock", "POST", {
        orderId, merchantId: coupon.merchant_id, amountCents
      });
      toast("已锁券，订单号 " + orderId);
    }
    if (button.dataset.action) {
      const coupon = coupons.find(entry => entry.id === button.dataset.id);
      await api("/coupons/" + coupon.id + "/" + button.dataset.action, "POST", {orderId: coupon.order_id});
      toast("状态已更新");
    }
    await refresh();
  });
});

function form(selector, handler) {
  $(selector).onsubmit = event => {
    event.preventDefault();
    const button = event.submitter || event.currentTarget.querySelector("button");
    const data = Object.fromEntries(new FormData(event.currentTarget));
    run(button, () => handler(data, button));
  };
}

form("#createForm", async data => {
  const now = Date.now();
  await api("/templates", "POST", {
    name: data.name, merchantId: Number(data.merchantId), stock: Number(data.stock),
    thresholdCents: Math.round(Number(data.threshold) * 100), discountCents: Math.round(Number(data.discount) * 100),
    validFrom: now, validTo: now + Number(data.days) * 86400000
  });
  toast("模板已发布");
  await refresh();
});

async function queryTask() {
  const task = await api("/tasks/" + encodeURIComponent($("#taskId").value.trim()));
  $("#retryTask").hidden = task.status !== "PARSE_FAILED";
  $("#taskResult").textContent = [
    "任务状态：" + label(task.status),
    ...Object.entries(task.counts).map(([key, count]) => label(key) + "：" + count),
    "无效文件行：" + task.importErrors,
    ...(task.parse_error ? ["解析错误：" + task.parse_error] : []),
    ...task.errors.slice(0, 10).map(error => "第" + error.source_row + "行：" + error.error),
    ...task.recipients.filter(row => row.error).slice(0, 10).map(row => row.user_id + "：" + row.error)
  ].join("\n");
}

async function refreshTasks() {
  const tasks = await api("/tasks");
  const list = $("#recentTasks");
  list.replaceChildren();
  for (const task of tasks) {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.className = "secondary";
    button.textContent = task.name + " · " + new Date(task.created_at).toLocaleString("zh-CN");
    button.onclick = () => run(button, async () => {
      $("#taskId").value = task.id;
      await queryTask();
    });
    item.append(button);
    list.append(item);
  }
  if (!tasks.length) list.textContent = "暂无分发任务";
}

form("#batchForm", async data => {
  const file = $("#csvFile").files[0];
  let task;
  if (file) {
    const multipart = new FormData();
    multipart.append("templateId", data.templateId);
    multipart.append("file", file);
    task = await api("/tasks/file", "POST", multipart);
  } else {
    const userIds = data.users.trim().split(/[\s,，]+/).map(Number);
    if (userIds.some(id => !Number.isSafeInteger(id) || id < 1)) throw Error("请输入有效的用户编号");
    task = await api("/tasks", "POST", {templateId: Number(data.templateId), userIds});
  }
  $("#taskId").value = task.id;
  await Promise.all([queryTask(), refreshTasks()]);
  toast("分发任务已提交");
});
form("#queryTaskForm", queryTask);
$("#retryTask").onclick = () => run($("#retryTask"), async () => {
  await api("/tasks/" + encodeURIComponent($("#taskId").value.trim()) + "/retry", "POST");
  await queryTask();
});

form("#redemptionForm", async (data, button) => {
  const path = "/redemptions/" + encodeURIComponent(data.order.trim());
  let coupon;
  if (button.value === "query") coupon = await api(path);
  else {
    if (!confirm("确认更新该订单的优惠券状态？")) return;
    coupon = await api(path + "/" + button.value, "POST");
  }
  $("#orderResult").textContent = [
    "订单号：" + data.order.trim(), "用户编号：" + coupon.user_id,
    "券编号：" + coupon.id, "状态：" + label(coupon.status)
  ].join("\n");
});

form("#login", async data => {
  $("#loginError").textContent = "";
  try {
    const session = await api("/auth/login", "POST", data);
    identity = session.identity;
    token = session.token;
    sessionStorage.setItem("couponSession", token);
    $("#login").reset();
    await openWorkspace();
  } catch (error) {
    $("#loginError").textContent = error.message;
  }
});
$("#logout").onclick = () => run($("#logout"), async () => {
  await api("/auth/logout", "POST");
  clearSession();
});
$("#refresh").onclick = () => run($("#refresh"), refresh);
$("#user").onchange = () => run($("#refresh"), refresh);

(async function start() {
  try {
    features = await api("/status");
    if (features.demoMode) return await openWorkspace();
    if (token) {
      identity = await api("/auth/me");
      await openWorkspace();
    }
  } catch (error) { toast(error.message); }
})();
