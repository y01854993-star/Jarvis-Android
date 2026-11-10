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

  // مفاتيح الصفحة (Groq / OpenAI) بتتبعت للتطبيق
  function pushKeys() {
    try {
      B.setKeys(JSON.stringify({
        gpt: (typeof keys !== "undefined" && keys.gpt) || "",
        groq: (typeof keys !== "undefined" && keys.groq) || "",
        name: (typeof userName !== "undefined" && userName) || ""
      }));
    } catch (e) {}
  }
  const origSave = window.saveKeys;
  window.saveKeys = function () { if (origSave) origSave(); pushKeys(); };
  pushKeys();

  window.jarvisEvent = function (m) {
    if (m.type === "say" && window.addLog) addLog(m.c, m.t);
    else if (m.type === "log" && window.addLog) addLog("", m.text);
    else if (m.type === "state" && window.setState) setState(m.c, m.t);
    else if (m.type === "status") { const n = $("micNote"); if (n) n.textContent = m.call || ""; }
  };
})();
