(function () {
  const $ = id => document.getElementById(id);
  const B = window.AndroidBridge;
  if (!B) return;

  // العقل والمايك والتنفيذ في التطبيق؛ الصفحة للعرض والكتابة
  window.handle = function (t) { if (t) B.sendText(t); };
  window.toggle = function () {
    B.toggleListen(typeof currentLang !== "undefined" ? currentLang : "ar-EG");
  };
  const hud = $("hud"); if (hud) hud.onclick = window.toggle;

  // خانة مفتاح Gemini (للصوت)، والضغط على الساعة بيظهر/يخفي لوحة المفاتيح
  const panel = $("setupPanel");
  if (panel && !$("kGem")) {
    const div = document.createElement("div");
    div.className = "ig";
    div.innerHTML = '<label>Gemini Key (صوت أفضل - مجاني)</label><input type="text" id="kGem" autocomplete="off" spellcheck="false" placeholder="AIza...">';
    panel.insertBefore(div, $("saveBtn"));
    $("kGem").value = localStorage.getItem("k_gem") || "";
  }
  if (panel && !$("kFish")) {
    const d2 = document.createElement("div");
    d2.className = "ig";
    d2.innerHTML = '<label>Fish Audio Key (صوت Jarvis MCU)</label><input type="text" id="kFish" autocomplete="off" spellcheck="false" placeholder="Fish API key">' +
                   '<label style="margin-top:6px">Fish Voice ID (من رابط الصوت fish.audio/m/...)</label><input type="text" id="kFishV" placeholder="مثال: 9f79a93aa44b...">';
    panel.insertBefore(d2, $("saveBtn"));
    $("kFish").value = localStorage.getItem("k_fish") || "";
    $("kFishV").value = localStorage.getItem("k_fishv") || "";
  }

  // زرار ⚙ ظاهر في الهيدر لفتح/إغلاق لوحة المفاتيح
  const langB = $("langBtn");
  if (langB && !$("keyBtn")) {
    const kb = document.createElement("button");
    kb.id = "keyBtn";
    kb.textContent = "⚙";
    kb.setAttribute("style", "background:transparent;border:1px solid var(--cy);color:var(--cy);border-radius:12px;padding:2px 9px;font-size:14px;font-family:Orbitron;cursor:pointer;margin-inline-end:5px;flex-shrink:0");
    kb.onclick = function () {
      if (!panel) return;
      const open = panel.style.display === "none";
      panel.style.display = open ? "block" : "none";
      if (open) panel.scrollIntoView({ behavior: "smooth", block: "center" });
    };
    langB.parentNode.insertBefore(kb, langB.nextSibling);
  }
  if (panel && !$("resetKeys")) {
    const rb = document.createElement("button");
    rb.id = "resetKeys";
    rb.textContent = "RESET ALL KEYS";
    rb.setAttribute("style", "background:#f87171;color:#000;border:none;border-radius:8px;width:100%;padding:10px;font-weight:bold;margin-top:8px;font-family:Orbitron;font-size:12px;cursor:pointer");
    rb.onclick = function () {
      ["k_gpt", "k_groq", "k_gem", "k_fish", "k_fishv"].forEach(function (k) { localStorage.removeItem(k); });
      ["kGpt", "kGroq", "kGem", "kFish", "kFishV"].forEach(function (i) { const e = $(i); if (e) e.value = ""; });
      if (typeof keys !== "undefined") { keys.gpt = ""; keys.groq = ""; }
      pushKeys();
      if (window.addLog) addLog("j", "All keys cleared.");
    };
    panel.appendChild(rb);
  }
  const clock = $("clock");
  if (clock && panel) clock.onclick = function () {
    panel.style.display = (panel.style.display === "none") ? "block" : "none";
  };

  // مفاتيح الصفحة (Groq / OpenAI / Gemini) بتتبعت للتطبيق
  function pushKeys() {
    try {
      B.setKeys(JSON.stringify({
        gpt: (typeof keys !== "undefined" && keys.gpt) || "",
        groq: (typeof keys !== "undefined" && keys.groq) || "",
        gem: localStorage.getItem("k_gem") || "",
        fish: localStorage.getItem("k_fish") || "",
        fishv: localStorage.getItem("k_fishv") || "",
        ds: localStorage.getItem("k_ds") || "",
        kimi: localStorage.getItem("k_kimi") || "",
        gh: localStorage.getItem("k_gh") || "",
        ghrepo: localStorage.getItem("k_ghrepo") || "",
        name: (typeof userName !== "undefined" && userName) || ""
      }));
    } catch (e) {}
  }
  const origSave = window.saveKeys;
  window.saveKeys = function () {
    const g = $("kGem"); if (g) localStorage.setItem("k_gem", g.value.trim());
    const f = $("kFish"); if (f) localStorage.setItem("k_fish", f.value.trim());
    const fv = $("kFishV"); if (fv) localStorage.setItem("k_fishv", fv.value.trim());
    if (origSave) origSave();
    pushKeys();
  };
  pushKeys();

  // أي مفتاح (Gemini / Groq / OpenAI) يخلي الحالة "Ready" ويقفل اللوحة
  window.checkKeys = function () {
    const c = [localStorage.getItem("k_gem"), localStorage.getItem("k_gpt"), localStorage.getItem("k_groq")].filter(Boolean).length;
    const p = $("setupPanel"); if (p) p.style.display = c ? "none" : "block";
    if ($("vAi")) $("vAi").textContent = c ? c + " Models Ready" : "Missing";
    if ($("rAi")) $("rAi").className = c ? "row on" : "row";
  };
  window.checkKeys();

  window.jarvisEvent = function (m) {
    if (m.type === "say" && window.addLog) addLog(m.c, m.t);
    else if (m.type === "log" && window.addLog) addLog("", m.text);
    else if (m.type === "state" && window.setState) setState(m.c, m.t);
    else if (m.type === "status") { const n = $("micNote"); if (n) n.textContent = m.call || ""; }
  };
})();
