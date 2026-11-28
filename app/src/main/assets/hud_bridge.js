(function () {
  const $ = id => document.getElementById(id);
  const B = window.AndroidBridge;
  if (!B) return;

  // The Android engine owns microphone, model keys, and task execution.
  window.handle = function (t) { if (t) B.sendText(t); };
  window.toggle = function () { B.toggleListen(typeof currentLang !== "undefined" ? currentLang : "ar-EG"); };
  const hud = $("hud"); if (hud) hud.onclick = window.toggle;

  const panel = $("setupPanel");
  if (panel && !$("kGem")) {
    const div = document.createElement("div");
    div.className = "ig";
    div.innerHTML = '<label>Gemini Key (صوت أفضل - مجاني)</label><input type="password" id="kGem" autocomplete="new-password" spellcheck="false" placeholder="AIza...">';
    panel.insertBefore(div, $("saveBtn"));
  }
  if (panel && !$("kFish")) {
    const d2 = document.createElement("div");
    d2.className = "ig";
    d2.innerHTML = '<label>Fish Audio Key (صوت Jarvis MCU)</label><input type="password" id="kFish" autocomplete="new-password" placeholder="Fish API key">' +
      '<label style="margin-top:6px">Fish Voice ID (من رابط الصوت fish.audio/m/...)</label><input type="text" id="kFishV" placeholder="مثال: 9f79a93aa44b...">';
    panel.insertBefore(d2, $("saveBtn"));
  }

  const secretKeys = ["k_gpt", "k_groq", "k_gem", "k_fish", "k_ds", "k_kimi", "k_gh", "k_freekey"];
  function valueFrom(id, key, legacy) {
    const field = $(id);
    return (field && field.value.trim()) || legacy || localStorage.getItem(key) || "";
  }
  function clearWebSecrets() {
    secretKeys.forEach(k => localStorage.removeItem(k));
    ["kGpt", "kGroq", "kGem", "kFish", "kDs", "kKimi", "kGh"].forEach(id => {
      const field = $(id); if (field) field.value = "";
    });
    if (typeof keys !== "undefined") {
      ["gpt", "groq", "gem"].forEach(k => { if (k in keys) keys[k] = ""; });
    }
    localStorage.setItem("jarvis_keys_configured", "1");
  }

  function pushKeys() {
    try {
      const payload = {
        gpt: valueFrom("kGpt", "k_gpt", typeof keys !== "undefined" ? keys.gpt : ""),
        groq: valueFrom("kGroq", "k_groq", typeof keys !== "undefined" ? keys.groq : ""),
        gem: valueFrom("kGem", "k_gem", typeof keys !== "undefined" ? keys.gem : ""),
        fish: valueFrom("kFish", "k_fish", ""),
        fishv: valueFrom("kFishV", "k_fishv", ""),
        ds: valueFrom("kDs", "k_ds", ""),
        kimi: valueFrom("kKimi", "k_kimi", ""),
        gh: valueFrom("kGh", "k_gh", ""),
        ghrepo: $("kGhRepo") ? $("kGhRepo").value.trim() : (localStorage.getItem("k_ghrepo") || ""),
        freekey: localStorage.getItem("k_freekey") || "",
        freeurl: localStorage.getItem("k_freeurl") || "",
        wake: localStorage.getItem("k_wake") === "1",
        perf: localStorage.getItem("k_perf") || "balanced",
        lock: localStorage.getItem("k_lock") === "1",
        speed: parseFloat(localStorage.getItem("k_speed") || "1"),
        pitch: parseFloat(localStorage.getItem("k_pitch") || "1"),
        patience: parseInt(localStorage.getItem("k_pat") || "1200", 10),
        localon: localStorage.getItem("k_localon") === "1",
        localurl: localStorage.getItem("k_localurl") || "http://127.0.0.1:8080/v1",
        localonly: localStorage.getItem("k_localonly") !== "0",
        sttdev: localStorage.getItem("k_sttdev") === "1",
        name: (typeof userName !== "undefined" && userName) || ""
      };
      const suppliedSecret = [payload.gpt, payload.groq, payload.gem, payload.fish, payload.ds, payload.kimi, payload.gh, payload.freekey].some(Boolean);
      const saved = B.setKeys(JSON.stringify(payload));
      if (!saved) {
        if (window.addLog) addLog("err", "مقدرتش أحفظ المفاتيح بأمان. جرّب تاني؛ ما اتحفظتش كنص عادي.");
        return false;
      }
      if (suppliedSecret) clearWebSecrets();
      return true;
    } catch (e) {
      if (window.addLog) addLog("err", "تعذر حفظ الإعدادات بأمان.");
      return false;
    }
  }

  const origSave = window.saveKeys;
  window.saveKeys = function () {
    if (origSave) origSave();
    if (pushKeys() && window.checkKeys) window.checkKeys();
  };
  pushKeys();

  // Only a non-secret marker is kept in WebView storage; actual keys live in Android Keystore-backed preferences.
  window.checkKeys = function () {
    const hasConfigured = localStorage.getItem("jarvis_keys_configured") === "1" ||
      (typeof keys !== "undefined" && Object.values(keys).some(Boolean)) || B.hasSavedKeys();
    const p = $("setupPanel");
    if (p) p.style.display = hasConfigured ? "none" : "block";
    if ($("vAi")) $("vAi").textContent = hasConfigured ? "Saved securely" : "Missing";
    if ($("rAi")) $("rAi").className = hasConfigured ? "row on" : "row";
  };
  window.checkKeys();

  function openTaskHistory() {
    let rows = [];
    try { rows = JSON.parse(B.taskHistory() || "[]"); } catch (_) {}
    let overlay = $("taskHistoryOverlay");
    if (overlay) overlay.remove();
    overlay = document.createElement("div");
    overlay.id = "taskHistoryOverlay";
    overlay.setAttribute("style", "position:fixed;inset:0;z-index:99999;background:rgba(0,0,0,.82);display:flex;align-items:center;justify-content:center;padding:14px;direction:rtl");
    const card = document.createElement("section");
    card.setAttribute("style", "width:min(640px,100%);max-height:85vh;overflow:auto;background:#07172b;border:1px solid #00e5ff;border-radius:18px;padding:16px;color:#e8f7ff;box-shadow:0 0 28px #00e5ff55;font-family:inherit");
    const heading = document.createElement("div");
    heading.setAttribute("style", "display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px");
    const title = document.createElement("strong"); title.textContent = "سجل المهام";
    const close = document.createElement("button"); close.textContent = "إغلاق"; close.setAttribute("style", "min-height:36px;padding:4px 12px");
    close.onclick = () => overlay.remove(); heading.append(title, close); card.appendChild(heading);
    if (!rows.length) {
      const empty = document.createElement("p"); empty.textContent = "لسه مفيش مهام محفوظة."; card.appendChild(empty);
    } else {
      rows.forEach(item => {
        const entry = document.createElement("article");
        entry.setAttribute("style", "border-top:1px solid #23435c;padding:10px 2px");
        const status = document.createElement("div");
        const label = item.status === "completed" ? "اكتملت" : item.status === "cancelled" ? "أُلغيت" : "تعذر إكمالها";
        status.textContent = label + (item.ts ? " · " + new Date(item.ts).toLocaleString() : "");
        status.setAttribute("style", "color:" + (item.status === "completed" ? "#71e8ad" : item.status === "cancelled" ? "#f7d774" : "#ff9c9c") + ";font-size:12px;margin-bottom:5px");
        const request = document.createElement("div"); request.textContent = "طلبك: " + (item.request || "");
        const result = document.createElement("div"); result.textContent = "النتيجة: " + (item.result || "");
        result.setAttribute("style", "opacity:.82;margin-top:4px");
        entry.append(status, request, result); card.appendChild(entry);
      });
    }
    overlay.addEventListener("click", e => { if (e.target === overlay) overlay.remove(); });
    overlay.appendChild(card); document.body.appendChild(overlay);
  }
  window.openTaskHistory = openTaskHistory;
  const langButton = $("langBtn");
  if (langButton && !$("taskHistoryBtn")) {
    const historyButton = document.createElement("button");
    historyButton.id = "taskHistoryBtn"; historyButton.type = "button"; historyButton.textContent = "السجل";
    historyButton.setAttribute("aria-label", "عرض سجل المهام");
    historyButton.setAttribute("style", "background:transparent;border:1px solid var(--cy);color:var(--cy);border-radius:12px;padding:2px 9px;font-size:12px;font-family:inherit;cursor:pointer;margin-inline-end:5px;min-height:32px");
    historyButton.onclick = openTaskHistory;
    langButton.parentNode.insertBefore(historyButton, langButton);
  }

  window.jarvisEvent = function (m) {
    if (m.type === "say" && window.addLog) addLog(m.c, m.t);
    else if (m.type === "log" && window.addLog) addLog("", m.text);
    else if (m.type === "state" && window.setState) setState(m.c, m.t);
    else if (m.type === "status") { const n = $("micNote"); if (n) n.textContent = m.call || ""; }
    else if (m.type === "health") { const v = $("vAi"); if (v) v.textContent = m.text; const r = $("rAi"); if (r) r.className = m.ready > 0 ? "row on" : "row"; }
  };
})();
