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
    div.innerHTML = '<label>Gemini Key (صوت أفضل - مجاني)</label><input type="password" id="kGem" placeholder="AIza...">';
    panel.insertBefore(div, $("saveBtn"));
    $("kGem").value = localStorage.getItem("k_gem") || "";
  }
  if (panel && !$("kFish")) {
    const d2 = document.createElement("div");
    d2.className = "ig";
    d2.innerHTML = '<label>Fish Audio Key (صوت Jarvis MCU)</label><input type="password" id="kFish" placeholder="Fish API key">' +
                   '<label style="margin-top:6px">Fish Voice ID (من رابط الصوت fish.audio/m/...)</label><input type="text" id="kFishV" placeholder="مثال: 9f79a93aa44b...">';
    panel.insertBefore(d2, $("saveBtn"));
    $("kFish").value = localStorage.getItem("k_fish") || "";
    $("kFishV").value = localStorage.getItem("k_fishv") || "";
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

  window.jarvisEvent = function (m) {
    if (m.type === "say" && window.addLog) addLog(m.c, m.t);
    else if (m.type === "log" && window.addLog) addLog("", m.text);
    else if (m.type === "state" && window.setState) setState(m.c, m.t);
    else if (m.type === "status") { const n = $("micNote"); if (n) n.textContent = m.call || ""; }
  };
})();
