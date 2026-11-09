(function () {
  const $ = (...ids) => ids.map(i => document.getElementById(i)).find(Boolean);
  const waEl = () => $("whatsapp-status", "wa-status", "whatsapp");
  const callEl = () => $("call-status", "status-text", "state-text");
  const logEl = () => $("log-screen", "system-logs", "terminal", "logs");

  window.jarvisEvent = function (m) {
    if (m.type === "log") {
      const box = logEl(); if (!box) return;
      const d = document.createElement("div"); d.textContent = m.text;
      box.appendChild(d);
      while (box.childNodes.length > 200) box.removeChild(box.firstChild);
      box.scrollTop = box.scrollHeight;
    }
    if (m.type === "status") {
      if (waEl()) waEl().textContent = m.whatsapp;
      if (callEl()) callEl().textContent = m.call;
      document.body.classList.toggle("live-call", !!m.live);
      document.documentElement.style.setProperty("--pulse-speed", m.live ? "0.6s" : "2.4s");
    }
    if (m.type === "speaking") {
      document.body.classList.add("jarvis-speaking");
      setTimeout(() => document.body.classList.remove("jarvis-speaking"), 2500);
    }
  };
})();
