/* Jarvis web engine: runs the HUD page in a desktop/phone browser (Chrome recommended). */
(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const LS = { get: (k) => localStorage.getItem(k) || "", set: (k, v) => localStorage.setItem(k, v) };
  const IDLE = "TAP JARVIS TO SPEAK";

  function K() {
    let n = LS.get("jv_name");
    if (!n || n === "يا فندم") n = "Boss";
    return { gem: LS.get("k_gem"), gpt: LS.get("k_gpt"), groq: LS.get("k_groq"), fish: LS.get("k_fish"), fishv: LS.get("k_fishv"), name: n };
  }
  const hasKey = () => { const k = K(); return !!(k.gem || k.gpt || k.groq); };
  const log = (c, t) => { if (window.addLog) window.addLog(c, t); };
  const state = (c, t) => { if (window.setState) window.setState(c, t); };

  // ------------------------------------------------------------ settings panel (keys)
  const panel = $("setupPanel");
  function field(id, label, ph, type) {
    const d = document.createElement("div");
    d.className = "ig";
    d.innerHTML = '<label>' + label + '</label><input type="' + (type || "text") + '" id="' + id + '" placeholder="' + ph + '" autocomplete="off" spellcheck="false">';
    panel.insertBefore(d, $("saveBtn"));
  }
  if (panel && !$("kGem")) {
    field("kGem", "Gemini Key (مجاني - يكفي لكل حاجة)", "AIza...");
    field("kFish", "Fish Audio Key (اختياري - صوت Jarvis MCU)", "Fish API key");
    field("kFishV", "Fish Voice ID (من رابط fish.audio/m/...)", "voice id");
  }
  const setv = (id, v) => { const e = $(id); if (e) e.value = v; };
  setv("kGem", LS.get("k_gem")); setv("kFish", LS.get("k_fish")); setv("kFishV", LS.get("k_fishv"));
  setv("kGpt", LS.get("k_gpt")); setv("kGroq", LS.get("k_groq")); setv("kName", K().name);

  const langB = $("langBtn");
  if (langB && !$("keyBtn")) {
    const kb = document.createElement("button");
    kb.id = "keyBtn"; kb.textContent = "⚙";
    kb.setAttribute("style", "background:transparent;border:1px solid var(--cy);color:var(--cy);border-radius:12px;padding:2px 9px;font-size:14px;font-family:Orbitron;cursor:pointer;margin-inline-end:5px;flex-shrink:0");
    kb.onclick = () => {
      const open = panel.style.display === "none";
      panel.style.display = open ? "block" : "none";
      if (open) panel.scrollIntoView({ behavior: "smooth", block: "center" });
    };
    langB.parentNode.insertBefore(kb, langB.nextSibling);
  }
  if (panel && !$("resetKeys")) {
    const rb = document.createElement("button");
    rb.id = "resetKeys"; rb.textContent = "RESET ALL KEYS";
    rb.setAttribute("style", "background:#f87171;color:#000;border:none;border-radius:8px;width:100%;padding:10px;font-weight:bold;margin-top:8px;font-family:Orbitron;font-size:12px;cursor:pointer");
    rb.onclick = () => {
      ["k_gpt", "k_groq", "k_gem", "k_fish", "k_fishv"].forEach((k) => localStorage.removeItem(k));
      ["kGpt", "kGroq", "kGem", "kFish", "kFishV"].forEach((i) => setv(i, ""));
      if (typeof keys !== "undefined") { keys.gpt = ""; keys.groq = ""; }
      clearBad(); window.checkKeys(); log("j", "All keys cleared.");
    };
    panel.appendChild(rb);
  }
  window.checkKeys = function () {
    const k = K();
    const c = [k.gem, k.gpt, k.groq].filter(Boolean).length;
    if (panel) panel.style.display = c ? "none" : "block";
    if ($("vAi")) $("vAi").textContent = c ? c + " Models Ready" : "Missing";
    if ($("rAi")) $("rAi").className = c ? "row on" : "row";
  };
  window.saveKeys = function () {
    const val = (id) => ($(id) ? $(id).value.trim() : "");
    LS.set("k_gpt", val("kGpt")); LS.set("k_groq", val("kGroq")); LS.set("k_gem", val("kGem"));
    LS.set("k_fish", val("kFish")); LS.set("k_fishv", val("kFishV"));
    LS.set("jv_name", val("kName") || "Boss");
    if (typeof keys !== "undefined") { keys.gpt = val("kGpt"); keys.groq = val("kGroq"); }
    clearBad(); window.checkKeys(); log("j", "Keys saved. Welcome " + K().name + ".");
  };
  window.checkKeys();

  // ------------------------------------------------------------ provider health (skip dead keys)
  const bad = {};
  const isBad = (p) => bad[p] && Date.now() - bad[p] < 600000;
  function markBad(p, e) {
    if (e && (e.status === 401 || e.status === 402 || e.status === 403 ||
      (e.status === 429 && /credit|quota|billing/i.test(e.body || "")))) bad[p] = Date.now();
  }
  function clearBad() { for (const k in bad) delete bad[k]; }
  function why(e) {
    if (e.status === 429 && /credit|quota|billing/i.test(e.body || "")) return "الرصيد خلص";
    if (e.status === 429) return "وصلت حد الاستخدام";
    if (e.status === 401 || e.status === 403) return "المفتاح غلط";
    if (e.status) return "خطأ " + e.status;
    return "مفيش اتصال";
  }
  async function post(url, headers, body, raw) {
    const r = await fetch(url, { method: "POST", headers: Object.assign({ "Content-Type": "application/json" }, headers), body: JSON.stringify(body) });
    if (!r.ok) { const e = new Error("HTTP " + r.status); e.status = r.status; e.body = await r.text().catch(() => ""); throw e; }
    return r;
  }

  // ------------------------------------------------------------ memory / history
  const history = [];
  const mem = () => { try { return JSON.parse(LS.get("jv_mem") || "[]"); } catch (e) { return []; } };
  function addMem(f) {
    f = (f || "").trim(); if (!f) return;
    const m = mem(); if (m.some((x) => x.toLowerCase() === f.toLowerCase())) return;
    m.push(f); while (m.length > 40) m.shift(); LS.set("jv_mem", JSON.stringify(m));
  }
  function pushHist(u, r) { history.push({ u, r }); while (history.length > 16) history.shift(); }

  // ------------------------------------------------------------ text helpers
  function normAr(s) {
    let o = "";
    for (const ch of (s || "").toLowerCase()) {
      let c = ch;
      if ("أإآٱ".includes(c)) c = "ا"; else if (c === "ى") c = "ي"; else if (c === "ة") c = "ه"; else if (c === "ؤ") c = "و"; else if (c === "ئ") c = "ي";
      if (/[\u064B-\u065F\u0640\u0670]/.test(c)) continue;
      if (/\p{L}/u.test(c)) o += c; else if (/\s|-|_/.test(c)) o += " ";
    }
    return o.trim().replace(/\s+/g, " ");
  }
  function langNote(t) {
    let ar = 0, lat = 0;
    for (const c of t) { if (c >= "\u0600" && c <= "\u06FF") ar++; else if (/[a-zA-Z]/.test(c)) lat++; }
    if (ar > 0 && ar >= lat) return "اللغة: عربي مصري. رد (reply و speech) بالعربي.";
    if (lat > 0) return "Language: English. Reply (reply and speech) in English only.";
    return "";
  }
  function isNoThanks(t) {
    const s = normAr(t); if (!s) return false;
    const strong = ["no thanks", "no thank you", "nothing else", "thats all", "that is all", "im good", "nope", "لا شكرا", "لا مفيش", "مفيش حاجه", "كفايه", "خلاص", "مش عايز", "مش محتاج", "لا يا"];
    if (strong.some((x) => s.includes(x))) return true;
    const w = s.split(" ");
    return w.length <= 2 && w.some((x) => ["no", "nope", "thanks", "thx", "لا", "شكرا"].includes(x));
  }
  function isYes(t) {
    const w = normAr(t).split(" ");
    if (w.some((x) => ["no", "nope", "cancel", "stop", "لا", "الغي", "بلاش", "متبعتش", "وقف"].includes(x))) return false;
    return w.some((x) => ["yes", "yeah", "yep", "sure", "ok", "okay", "send", "go", "ايوه", "ايوا", "اه", "نعم", "تمام", "ابعت", "ارسل", "اوك", "ماشي", "طبعا", "يلا", "اكيد"].includes(x));
  }
  function parseLoose(t) {
    const s = (t || "").trim().replace(/^```json/, "").replace(/^```/, "").replace(/```$/, "").trim();
    try { return JSON.parse(s); } catch (e) { return { reply: s, actions: [] }; }
  }

  // ------------------------------------------------------------ brain
  function sysPrompt(userText) {
    const k = K();
    const now = new Date().toLocaleString("ar-EG", { dateStyle: "full", timeStyle: "short" });
    const facts = mem().map((f) => "- " + f).join("\n") || "(لسه مفيش)";
    return 'أنت J.A.R.V.I.S مساعد شخصي ذكي جدا شغال على متصفح اللابتوب. بتناديه دايما "' + k.name + '" بالإنجليزي زي ما هي (مش في كل جملة).\n' +
      'شخصيتك: رسمي، خفيف الدم، واثق، ومباشر.\n' +
      'قاعدة اللغة (مهمة جدا): رد دايما بنفس لغة آخر جملة قالها المستخدم. لو كلمك عربي رد بالعامية المصرية، ولو كلمك English رد English بس.\n' +
      'الكلام جاي من تسجيل صوتي، فصحّح الأسماء والحروف الغلط من السياق.\n\n' +
      'السياق دلوقتي:\n- الوقت: ' + now + '\n- حاجات عارفها عن المستخدم:\n' + facts + '\n\n' +
      'ارجع JSON فقط بالشكل ده:\n{"thought":"تحليل قصير لنية المستخدم","reply":"...","speech":"...","actions":[{"type":"..."}],"remember":"","done":false}\n\n' +
      'الأوامر الممكنة في actions (ممكن أكتر من واحد بالترتيب):\n' +
      '- {"type":"open_url","url":"https://..."}: فتح أي موقع\n' +
      '- {"type":"web_search","query":"..."}\n' +
      '- {"type":"youtube_search","query":"..."}\n' +
      '- {"type":"navigate","place":"..."}: خريطة جوجل\n' +
      '- {"type":"whatsapp","phone":"رقم دولي من غير + (اختياري)","message":"..."}: واتساب ويب\n' +
      '- {"type":"email","to":"...","subject":"...","body":"..."}\n' +
      '- {"type":"timer","seconds":N,"label":"..."}\n' +
      '- {"type":"copy","text":"..."}: نسخ نص للحافظة\n' +
      '- {"type":"create_project","project_prompt":"..."}: لما يطلب موقع أو تطبيق، وصف مفصل بالإنجليزي، بيفتح لوفابل\n\n' +
      'قواعد:\n' +
      '- للأوامر: reply قصير. للأسئلة والشرح: جاوب بعمق ووضوح (من 4 لـ 8 جمل) من غير رموز أو تنسيق لأنها هتتقال بصوت.\n' +
      '- speech: نفس reply بالظبط (نفس اللغة) بس معدّل للنطق السليم: الأرقام والتواريخ بالحروف، تشكيل خفيف للعربي، من غير رموز أو روابط.\n' +
      '- لو محتاج معلومة حديثة (أخبار، طقس، أسعار) استخدم web_search وقول إنك فتحت البحث.\n' +
      '- لو الطلب مش واضح اسأل سؤال واحد قصير.\n' +
      '- remember: حط معلومة قصيرة لو المستخدم قال حاجة عن نفسه تستحق تفتكرها، غير كده "".\n' +
      '- done: true بس لو المستخدم قال إنه خلص (لا شكرا، كفاية، خلاص).\n' +
      '- متخترعش حاجة، ولو معرفتش قول معرفش.';
  }

  async function geminiCall(body) {
    const k = K(); let last;
    for (const m of ["gemini-2.5-flash", "gemini-flash-latest"]) {
      try {
        const r = await post("https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent", { "x-goog-api-key": k.gem }, body);
        return await r.json();
      } catch (e) { last = e; if (e.status !== 400 && e.status !== 404) throw e; }
    }
    throw last;
  }
  async function chatJSON(sys, user) {
    const k = K(); const errs = []; const order = ["gemini", "openai", "groq"]; let skipped = false;
    for (const p of order) {
      const have = p === "gemini" ? k.gem : p === "openai" ? k.gpt : k.groq;
      if (!have) continue;
      if (isBad(p)) { skipped = true; errs.push(p + ": معطّل (مفتاح غلط أو رصيد خلص)"); continue; }
      try {
        if (p === "gemini") {
          const contents = [];
          history.slice(-12).forEach((h) => { contents.push({ role: "user", parts: [{ text: h.u }] }); contents.push({ role: "model", parts: [{ text: h.r }] }); });
          contents.push({ role: "user", parts: [{ text: user }] });
          const d = await geminiCall({ systemInstruction: { parts: [{ text: sys }] }, contents, generationConfig: { responseMimeType: "application/json", temperature: 0.4 } });
          return parseLoose(d.candidates[0].content.parts[0].text);
        }
        const url = p === "openai" ? "https://api.openai.com/v1/chat/completions" : "https://api.groq.com/openai/v1/chat/completions";
        const models = p === "openai" ? ["gpt-4o", "gpt-4o-mini"] : ["llama-3.3-70b-versatile", "llama-3.1-8b-instant"];
        const msgs = [{ role: "system", content: sys }];
        history.slice(-12).forEach((h) => { msgs.push({ role: "user", content: h.u }); msgs.push({ role: "assistant", content: h.r }); });
        msgs.push({ role: "user", content: user });
        let lastE;
        for (const m of models) {
          try {
            const r = await post(url, { Authorization: "Bearer " + have }, { model: m, response_format: { type: "json_object" }, messages: msgs, temperature: 0.4 });
            const d = await r.json();
            return parseLoose(d.choices[0].message.content);
          } catch (e) { lastE = e; if (e.status !== 400 && e.status !== 404) throw e; }
        }
        throw lastE;
      } catch (e) { markBad(p, e); errs.push(p + ": " + why(e)); }
    }
    if (errs.length) throw new Error(errs.join(" | "));
    throw new Error("حط مفتاح Gemini أو Groq أو OpenAI من زر ⚙");
  }

  // ------------------------------------------------------------ audio context + TTS
  let AC = null, cur = null;
  function audioCtx() {
    if (!AC) AC = new (window.AudioContext || window.webkitAudioContext)();
    if (AC.state === "suspended") AC.resume();
    return AC;
  }
  function playBuffer(buf) {
    return new Promise((res) => {
      const ac = audioCtx(); const src = ac.createBufferSource();
      src.buffer = buf; src.connect(ac.destination); cur = src;
      src.onended = () => { if (cur === src) cur = null; res(); };
      src.start();
    });
  }
  function stopAudio() { try { if (cur) { cur.onended = null; cur.stop(); cur = null; } } catch (e) {} try { speechSynthesis.cancel(); } catch (e) {} }
  const ttsDead = {}; const phraseCache = {};
  function pcmToBuffer(bytes, rate) {
    const n = Math.floor(bytes.length / 2); const dv = new DataView(bytes.buffer, bytes.byteOffset, n * 2);
    const b = audioCtx().createBuffer(1, n, rate); const ch = b.getChannelData(0);
    for (let i = 0; i < n; i++) ch[i] = dv.getInt16(i * 2, true) / 32768;
    return b;
  }
  async function cloudTts(text) {
    const k = K(); const arabic = /[\u0600-\u06FF]/.test(text);
    const order = [];
    if (k.fish && k.fishv) order.push("fish");
    if (k.gem) order.push("gemini");
    if (k.gpt) order.push("openai");
    for (const p of order) {
      if (ttsDead[p]) continue;
      try {
        if (p === "fish") {
          const r = await post("https://api.fish.audio/v1/tts", { Authorization: "Bearer " + k.fish, model: "s2-pro" }, { text, reference_id: k.fishv, format: "wav", latency: "balanced" });
          return await audioCtx().decodeAudioData(await r.arrayBuffer());
        }
        if (p === "gemini") {
          const d = await geminiTtsCall(text);
          const b64 = d.candidates[0].content.parts[0].inlineData.data;
          const bin = atob(b64); const bytes = new Uint8Array(bin.length);
          for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
          return pcmToBuffer(bytes, 24000);
        }
        if (p === "openai") {
          const r = await post("https://api.openai.com/v1/audio/speech", { Authorization: "Bearer " + k.gpt }, { model: "gpt-4o-mini-tts", voice: "onyx", input: text, response_format: "mp3" });
          return await audioCtx().decodeAudioData(await r.arrayBuffer());
        }
      } catch (e) {
        if ([400, 401, 402, 403, 404, 429].includes(e.status) || e instanceof TypeError) {
          ttsDead[p] = true; log("err", "صوت " + p + " مش شغال (" + (e.status || "مفيش اتصال/CORS") + ")، هستخدم البديل");
        }
      }
    }
    return null;
  }
  async function geminiTtsCall(text) {
    const k = K();
    return await (await post("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent", { "x-goog-api-key": k.gem }, {
      contents: [{ parts: [{ text: "Say in a calm, deep, confident assistant voice: " + text }] }],
      generationConfig: { responseModalities: ["AUDIO"], speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: "Charon" } } } }
    })).json();
  }
  function browserSpeak(text) {
    return new Promise((res) => {
      if (!("speechSynthesis" in window)) return res();
      const u = new SpeechSynthesisUtterance(text);
      u.lang = /[\u0600-\u06FF]/.test(text) ? "ar-EG" : "en-US";
      u.onend = u.onerror = () => res();
      speechSynthesis.cancel(); speechSynthesis.speak(u);
    });
  }
  async function speak(text, shown, cache) {
    log("j", shown || text); state("speaking", "SPEAKING");
    const clean = text.replace(/[*_#`]/g, "");
    let buf = cache ? phraseCache[clean] : null;
    if (!buf) { buf = await cloudTts(clean); if (buf && cache) phraseCache[clean] = buf; }
    if (buf) await playBuffer(buf); else await browserSpeak(clean);
    await sleep(250);
  }
  async function prewarm() {
    if (!hasKey()) return;
    for (const t of ["Yes, Boss", "Anything else, Boss?", "As you wish, Boss."]) {
      if (phraseCache[t]) continue;
      try { const b = await cloudTts(t); if (b) phraseCache[t] = b; } catch (e) {}
    }
  }

  // ------------------------------------------------------------ hearing (record + transcribe, or Web Speech)
  let cancelRec = null, listening = false;
  function recordUtterance(maxWait) {
    return navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } }).then((stream) => {
      const ac = audioCtx(); const src = ac.createMediaStreamSource(stream);
      const sp = ac.createScriptProcessor(4096, 1, 1);
      const chunks = []; let pre = []; let started = false, silent = 0, time = 0, noise = 0, thr = 0.02, cancelled = false;
      const frameMs = (4096 / ac.sampleRate) * 1000;
      return new Promise((resolve) => {
        const finish = () => {
          try { sp.disconnect(); src.disconnect(); } catch (e) {}
          stream.getTracks().forEach((t) => t.stop());
          resolve(started && !cancelled ? { chunks, rate: ac.sampleRate } : null);
        };
        sp.onaudioprocess = (e) => {
          const d = new Float32Array(e.inputBuffer.getChannelData(0));
          let s = 0; for (let i = 0; i < d.length; i++) s += d[i] * d[i];
          const rms = Math.sqrt(s / d.length); time += frameMs;
          if (time <= frameMs * 3) { noise = Math.max(noise, rms); thr = Math.min(0.08, Math.max(0.015, noise * 2.5)); pre.push(d); return; }
          if (rms > thr) { if (!started) { started = true; chunks.push(...pre); } silent = 0; } else if (started) silent += frameMs;
          if (started) chunks.push(d); else { pre.push(d); if (pre.length > 4) pre.shift(); }
          if (cancelled || (!started && time > maxWait) || (started && silent >= 1200) || time > 16000) finish();
        };
        cancelRec = () => { cancelled = true; };
        src.connect(sp); sp.connect(ac.destination);
      });
    });
  }
  function toWav16k(rec) {
    let n = 0; rec.chunks.forEach((c) => (n += c.length));
    const all = new Float32Array(n); let o = 0; rec.chunks.forEach((c) => { all.set(c, o); o += c.length; });
    const ratio = rec.rate / 16000; const len = Math.floor(all.length / ratio); const pcm = new Int16Array(len);
    for (let i = 0; i < len; i++) { const x = all[Math.floor(i * ratio)]; pcm[i] = Math.max(-1, Math.min(1, x)) * 32767; }
    const buf = new ArrayBuffer(44 + len * 2); const v = new DataView(buf);
    const w = (p, s) => { for (let i = 0; i < s.length; i++) v.setUint8(p + i, s.charCodeAt(i)); };
    w(0, "RIFF"); v.setUint32(4, 36 + len * 2, true); w(8, "WAVE"); w(12, "fmt "); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, 16000, true); v.setUint32(28, 32000, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true); w(36, "data"); v.setUint32(40, len * 2, true);
    for (let i = 0; i < len; i++) v.setInt16(44 + i * 2, pcm[i], true);
    return new Blob([buf], { type: "audio/wav" });
  }
  function b64(blob) {
    return new Promise((res) => { const r = new FileReader(); r.onloadend = () => res(String(r.result).split(",")[1]); r.readAsDataURL(blob); });
  }
  async function transcribe(wav) {
    const k = K(); const errs = [];
    if (k.gem && !isBad("gemini")) {
      try {
        const d = await geminiCall({ contents: [{ parts: [{ text: "Transcribe this audio exactly as spoken. Output only the transcript, in the original language (Arabic stays Arabic, English stays English). If there is no speech, output nothing." }, { inlineData: { mimeType: "audio/wav", data: await b64(wav) } }] }] });
        return ((d.candidates[0].content.parts[0].text) || "").trim();
      } catch (e) { markBad("gemini", e); errs.push("gemini: " + why(e)); }
    }
    for (const p of ["groq", "openai"]) {
      const key = p === "groq" ? k.groq : k.gpt; if (!key || isBad(p)) continue;
      try {
        const fd = new FormData(); fd.append("file", wav, "a.wav"); fd.append("model", p === "groq" ? "whisper-large-v3-turbo" : "whisper-1"); fd.append("response_format", "json");
        const r = await fetch(p === "groq" ? "https://api.groq.com/openai/v1/audio/transcriptions" : "https://api.openai.com/v1/audio/transcriptions", { method: "POST", headers: { Authorization: "Bearer " + key }, body: fd });
        if (!r.ok) { const e = new Error("HTTP " + r.status); e.status = r.status; e.body = await r.text().catch(() => ""); throw e; }
        return ((await r.json()).text || "").trim();
      } catch (e) { markBad(p, e); errs.push(p + ": " + why(e)); }
    }
    throw new Error(errs.join(" | ") || "مفيش مفتاح");
  }
  function webSpeechOnce() {
    return new Promise((res) => {
      const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
      if (!SR) { log("err", "المتصفح ده مش بيدعم التعرف على الصوت. استخدم Chrome."); return res(null); }
      const r = new SR(); r.lang = typeof currentLang !== "undefined" ? currentLang : "ar-EG"; r.interimResults = false; r.maxAlternatives = 1;
      let done = false; const fin = (v) => { if (done) return; done = true; try { r.abort(); } catch (e) {} res(v); };
      r.onresult = (e) => fin(e.results[0][0].transcript);
      r.onerror = (e) => { if (e.error === "not-allowed") log("err", "اسمح للمتصفح بالمايك"); fin(null); };
      r.onend = () => fin(null);
      cancelRec = () => fin(null);
      r.start();
    });
  }
  async function listenText(maxWait) {
    listening = true; state("listening", "LISTENING");
    try {
      const k = K();
      if (!(k.gem || k.groq || k.gpt)) return await webSpeechOnce();
      const rec = await recordUtterance(maxWait);
      if (!rec) return null;
      state("thinking", "THINKING");
      const txt = await transcribe(toWav16k(rec));
      return txt || null;
    } catch (e) {
      log("err", e.name === "NotAllowedError" ? "اسمح للمتصفح بالمايك" : e.message);
      return null;
    } finally { listening = false; }
  }

  // ------------------------------------------------------------ actions
  function openTab(url) {
    const w = window.open(url, "_blank");
    if (!w) {
      const a = '<a href="' + url + '" target="_blank" rel="noopener" style="color:#22d3ee;text-decoration:underline">اضغط هنا للفتح</a>';
      const box = $("log"); if (box) { box.innerHTML += '<div class="j">› ' + a + "</div>"; box.scrollTop = box.scrollHeight; }
    }
    return true;
  }
  async function confirmSend(target, message) {
    if (!voiceMode) return true;
    await speak("To " + target + ": " + message + ". Shall I send it, Boss?", null, false);
    const a = await listenText(7000);
    return !!a && isYes(a);
  }
  async function runAction(a) {
    const enc = encodeURIComponent;
    switch (a.type) {
      case "open_url": return /^https?:\/\//i.test(a.url || "") && openTab(a.url) ? null : "اللينك مش صحيح";
      case "web_search": return a.query && openTab("https://www.google.com/search?q=" + enc(a.query)) ? null : "معرفتش أبحث";
      case "youtube_search": return a.query && openTab("https://www.youtube.com/results?search_query=" + enc(a.query)) ? null : "معرفتش أفتح يوتيوب";
      case "navigate": return a.place && openTab("https://www.google.com/maps/search/" + enc(a.place)) ? null : "معرفتش أفتح الخريطة";
      case "whatsapp": {
        if (!a.message) return "مفيش رسالة";
        if (!(await confirmSend(a.phone || "WhatsApp", a.message))) return "تمام، الغيتها";
        const ph = (a.phone || "").replace(/\D/g, "");
        openTab("https://wa.me/" + ph + "?text=" + enc(a.message)); return null;
      }
      case "email": openTab("mailto:" + (a.to || "") + "?subject=" + enc(a.subject || "") + "&body=" + enc(a.body || "")); return null;
      case "timer": {
        const s = Math.max(1, parseInt(a.seconds, 10) || 60); const label = a.label || "Timer";
        setTimeout(() => { speak("Time is up, Boss. " + label, label, false); }, s * 1000); return null;
      }
      case "copy": try { await navigator.clipboard.writeText(a.text || ""); return null; } catch (e) { return "معرفتش أنسخ"; }
      case "create_project": {
        const p = (a.project_prompt || "").slice(0, 1500); if (!p) return "ناقصني وصف المشروع";
        openTab("https://lovable.dev/?autosubmit=true#prompt=" + enc(p)); return null;
      }
      default: return null;
    }
  }
  async function act(res, userText) {
    let reply = (res.reply || "").trim(); const modelReply = reply;
    if (res.remember) addMem(res.remember);
    const list = Array.isArray(res.actions) ? res.actions : [];
    for (let i = 0; i < list.length; i++) {
      const err = await runAction(list[i] || {});
      if (err) { reply = err; break; }
      if (i < list.length - 1) await sleep(700);
    }
    if (reply) {
      pushHist(userText, reply);
      const sp = (res.speech || "").trim();
      await speak(reply === modelReply && sp ? sp : reply, reply, false);
    }
    return !!res.done;
  }
  async function think(text) {
    const note = langNote(text);
    try { return await chatJSON(sysPrompt(text), note ? text + "\n\n[" + note + "]" : text); }
    catch (e) { log("err", e.message); return null; }
  }

  // ------------------------------------------------------------ conversation session
  let sessionActive = false, voiceMode = false, busy = false;
  async function runSession() {
    if (!hasKey()) { log("err", "حط مفتاح Gemini أو Groq أو OpenAI من زر ⚙"); if (panel) panel.style.display = "block"; return; }
    busy = true; sessionActive = true; voiceMode = true; audioCtx();
    try {
      await speak("Yes, Boss", null, true);
      for (let round = 0; sessionActive && round < 8; round++) {
        const text = await listenText(round === 0 ? 9000 : 7000);
        if (!text || !sessionActive) break;
        if (isNoThanks(text)) { await speak("As you wish, Boss.", null, true); break; }
        state("thinking", "THINKING"); log("u", text);
        const res = await think(text); if (!res) break;
        const done = await act(res, text);
        if (done || !sessionActive) break;
        await speak("Anything else, Boss?", null, true);
      }
    } finally { sessionActive = false; voiceMode = false; busy = false; state("", IDLE); }
  }
  function cancelSession() { sessionActive = false; if (cancelRec) cancelRec(); stopAudio(); }
  window.toggle = function () { if (sessionActive) cancelSession(); else if (!busy) runSession(); };
  window.handle = async function (t) {
    t = (t || "").trim(); if (!t || busy) return;
    if (!hasKey()) { log("err", "حط مفتاح Gemini أو Groq أو OpenAI من زر ⚙"); if (panel) panel.style.display = "block"; return; }
    busy = true; voiceMode = false; audioCtx();
    try { state("thinking", "THINKING"); log("u", t); const res = await think(t); if (res) await act(res, t); }
    finally { busy = false; state("", IDLE); }
  };
  const hud = $("hud"); if (hud) hud.onclick = window.toggle;
  document.addEventListener("keydown", (e) => {
    if (e.code === "Space" && !/INPUT|TEXTAREA|SELECT/.test((document.activeElement || {}).tagName || "")) { e.preventDefault(); window.toggle(); }
  });
  const note = $("micNote"); if (note) note.textContent = "اضغط على الدايرة أو المسطرة (Space) عشان تتكلم";
  setTimeout(prewarm, 2500);
})();
