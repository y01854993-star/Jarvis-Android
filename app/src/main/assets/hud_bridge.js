(function () {
  const $ = id => document.getElementById(id);
  const B = window.AndroidBridge;

  // العقل والمايك والتنفيذ بقوا في التطبيق (Gemini)، والصفحة للعرض والكتابة فقط
  window.handle = function (t) { if (t && B) B.sendText(t); };
  window.toggle = function () { if (B) B.toggleListen(); };
  const hud = $("hud"); if (hud) hud.onclick = window.toggle;
  const setup = $("setupPanel"); if (setup) setup.style.display = "none";
  const vAi = $("vAi"); if (vAi) vAi.textContent = "GEMINI READY";
  const rAi = $("rAi"); if (rAi) rAi.className = "row on";

  window.jarvisEvent = function (m) {
    if (m.type === "say" && window.addLog) addLog(m.c, m.t);
    else if (m.type === "log" && window.addLog) addLog("", m.text);
    else if (m.type === "state" && window.setState) setState(m.c, m.t);
    else if (m.type === "status" && vAi) { vAi.textContent = m.call; if (rAi) rAi.className = "row on"; }
  };
})();
