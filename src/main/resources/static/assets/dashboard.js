const $ = (s) => document.querySelector(s);
let adminKey = "",
  editing = null,
  clients = [],
  events = [],
  busy = false;
function message(el, text) {
  $(el).textContent = text;
}
async function admin(path, options = {}) {
  const r = await fetch(path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      "X-Admin-Key": adminKey,
      ...options.headers,
    },
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok)
    throw Error(data.message || data.error || `Request failed (${r.status})`);
  return data;
}
async function health() {
  try {
    const r = await fetch("/actuator/health");
    if (!r.ok) throw Error();
    message("#health", "API online");
    $("#health").classList.add("online");
  } catch {
    message("#health", "API offline");
  }
}
health();
function render() {
  const total = events.length;
  message("#total", total);
  message("#allowed", events.filter((e) => e.status === 200).length);
  message("#rejected", events.filter((e) => e.status === 429).length);
  const times = events.map((e) => e.ms).sort((a, b) => a - b);
  message(
    "#p95",
    times.length
      ? `${Math.round(times[Math.ceil(times.length * 0.95) - 1])} ms`
      : "—",
  );
  const chart = $("#chart");
  chart.replaceChildren();
  if (!events.length) {
    chart.innerHTML =
      '<div class="chart-empty">Your traffic will appear here.<span>Run a burst to see the limiter in action.</span></div>';
  }
  events.slice(-60).forEach((e) => {
    const bar = document.createElement("div");
    bar.className =
      "bar" +
      (e.status === 429 ? " limited" : e.status !== 200 ? " error" : "");
    bar.title = `${e.status}: ${e.reason} · ${e.ms.toFixed(1)} ms`;
    chart.append(bar);
  });
  chart.setAttribute(
    "aria-label",
    `${events.filter((e) => e.status === 200).length} allowed and ${events.filter((e) => e.status === 429).length} limited requests in session`,
  );
  const tbody = $("#events");
  tbody.replaceChildren();
  if (!total) {
    tbody.innerHTML =
      '<tr><td colspan="5" class="empty">No activity yet. Send your first request above.</td></tr>';
  }
  events
    .slice(-12)
    .reverse()
    .forEach((e) => {
      const row = document.createElement("tr");
      [
        e.time,
        e.status || "Network error",
        e.reason,
        e.remaining ?? "—",
        `${e.ms.toFixed(1)} ms`,
      ].forEach((v, i) => {
        const td = document.createElement("td");
        td.textContent = v;
        if (i === 1) td.className = e.status === 200 ? "green" : "amber";
        row.append(td);
      });
      tbody.append(row);
    });
}
async function send() {
  const start = performance.now();
  let event;
  try {
    const key = $("#api-key").value.trim();
    const response = await fetch("/api/test", {
      headers: key ? { "X-API-Key": key } : {},
    });
    const data = await response.json();
    const reason =
      response.headers.get("X-RateLimit-Reason") ||
      data.message ||
      data.error ||
      "Request error";
    event = {
      status: response.status,
      reason,
      remaining: response.headers.get("X-RateLimit-Remaining"),
      ms: performance.now() - start,
      time: new Date().toLocaleTimeString(),
    };
    message("#remaining", event.remaining ?? "—");
    const limit = Number(response.headers.get("X-RateLimit-Limit"));
    $("#quota-fill").style.width = limit
      ? `${Math.min(100, (Number(event.remaining) / limit) * 100)}%`
      : "0";
    message(
      "#quota-detail",
      response.headers.get("X-RateLimit-Policy")
        ? `${response.headers.get("X-RateLimit-Policy")} · limit ${limit} · retry ${response.headers.get("Retry-After") || 0}s`
        : "Authentication or connection error",
    );
    message(
      "#decision",
      `${event.status} · ${response.headers.get("X-RateLimit-Type") || "ERROR"} · ${reason}`,
    );
    $("#decision").className =
      "decision " + (event.status === 200 ? "ok" : "blocked");
  } catch (e) {
    event = {
      status: 0,
      reason: "Unable to reach API",
      ms: performance.now() - start,
      time: new Date().toLocaleTimeString(),
    };
    message(
      "#decision",
      "Unable to reach the API. Check the server and try again.",
    );
  }
  events.push(event);
  if (events.length > 1000) events.shift();
  render();
}
async function run(n) {
  if (busy) return;
  busy = true;
  $("#send-one").disabled = $("#send-burst").disabled = true;
  try {
    await Promise.all(Array.from({ length: n }, send));
  } finally {
    busy = false;
    $("#send-one").disabled = $("#send-burst").disabled = false;
  }
}
$("#send-one").onclick = () => run(1);
$("#send-burst").onclick = () => run(20);
$("#clear").onclick = () => {
  events = [];
  render();
  message("#decision", "Session cleared. Server quotas have not been reset.");
  message("#remaining", "—");
  $("#quota-fill").style.width = "0";
};
$("#admin-open").onclick = () => $("#admin-dialog").showModal();
$("#close-admin").onclick = () => $("#admin-dialog").close();
$("#unlock-form").onsubmit = async (e) => {
  e.preventDefault();
  adminKey = $("#admin-key").value;
  try {
    await loadClients();
    $("#admin-key").value = "";
    $("#admin-dialog").close();
    message("#admin-open", "Admin unlocked ✓");
  } catch (err) {
    adminKey = "";
    message("#unlock-error", err.message);
  }
};
async function loadClients() {
  clients = await admin("/api/admin/clients");
  const list = $("#client-list");
  list.className = "";
  list.replaceChildren();
  if (!clients.length) {
    const p = document.createElement("p");
    p.className = "muted";
    p.textContent = "No clients yet. Create your first policy below.";
    list.append(p);
  }
  for (const c of clients) {
    const row = document.createElement("div");
    row.className = "client-row";
    const name = document.createElement("strong");
    name.textContent = c.name;
    const meta = document.createElement("small");
    meta.textContent = `${c.burstLimit} / ${c.burstSeconds}s burst · ${c.sustainedLimit} / ${c.sustainedSeconds}s sustained · ${c.enabled ? "Active" : "Disabled"}`;
    const edit = document.createElement("button");
    edit.className = "ghost";
    edit.textContent = "Edit";
    edit.onclick = () => {
      editing = c.id;
      $("#burst-seconds").disabled = $("#sustained-seconds").disabled = true;
      $("#client-name").value = c.name;
      $("#burst-limit").value = c.burstLimit;
      $("#burst-seconds").value = c.burstSeconds;
      $("#sustained-limit").value = c.sustainedLimit;
      $("#sustained-seconds").value = c.sustainedSeconds;
      $("#client-enabled").checked = c.enabled;
      message("#form-title", "Edit client policy");
      $("#policy-form").scrollIntoView({ block: "center" });
    };
    const rotate = document.createElement("button");
    rotate.className = "ghost";
    rotate.textContent = "Rotate key";
    rotate.onclick = async () => {
      if (
        !confirm(
          `Replace the key for ${c.name}? The old key will stop working.`,
        )
      )
        return;
      try {
        const d = await admin(`/api/admin/clients/${c.id}/rotate`, {
          method: "POST",
        });
        showKey(d.apiKey);
      } catch (err) {
        message("#policy-message", err.message);
      }
    };
    row.append(name, meta, edit, rotate);
    list.append(row);
  }
  $("#policy-form").hidden = false;
}
$("#refresh-clients").onclick = () =>
  loadClients().catch((e) => message("#policy-message", e.message));
function resetForm() {
  editing = null;
  $("#burst-seconds").disabled = $("#sustained-seconds").disabled = false;
  $("#policy-form").reset();
  message("#form-title", "Create a client");
}
$("#cancel-edit").onclick = resetForm;
$("#policy-form").onsubmit = async (e) => {
  e.preventDefault();
  const button = e.submitter;
  button.disabled = true;
  try {
    const input = {
      name: $("#client-name").value,
      burstLimit: Number($("#burst-limit").value),
      burstSeconds: Number($("#burst-seconds").value),
      sustainedLimit: Number($("#sustained-limit").value),
      sustainedSeconds: Number($("#sustained-seconds").value),
      enabled: $("#client-enabled").checked,
    };
    const data = await admin(
      "/api/admin/clients" + (editing ? "/" + editing : ""),
      { method: editing ? "PUT" : "POST", body: JSON.stringify(input) },
    );
    if (data.apiKey) showKey(data.apiKey);
    resetForm();
    await loadClients();
    message(
      "#policy-message",
      "Policy saved. Previously consumed permits remain in their windows.",
    );
  } catch (err) {
    message("#policy-message", err.message);
  } finally {
    button.disabled = false;
  }
};
function showKey(key) {
  $("#new-key").value = key;
  $("#key-dialog").showModal();
}
$("#close-key").onclick = () => {
  $("#new-key").value = "";
  $("#key-dialog").close();
};
$("#use-key").onclick = () => {
  $("#api-key").value = $("#new-key").value;
  $("#new-key").value = "";
  $("#key-dialog").close();
  $("#playground").scrollIntoView();
};
