"""Model access for the skill hunter: Claude first, Gemini as fallback. Never invents output when no key exists."""
import json, os, re, urllib.error, urllib.request


def _post(url, payload, headers, timeout=240):
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def parse_json(text):
    text = re.sub(r"^```(?:json)?|```$", "", text.strip(), flags=re.M).strip()
    a, b = text.find("{"), text.rfind("}")
    return json.loads(text[a:b + 1])


def ask_claude(prompt, max_tokens=6000):
    key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    if not key:
        raise RuntimeError("ANTHROPIC_API_KEY not set")
    model = os.environ.get("AUTOFIX_CLAUDE_MODEL", "").strip() or "claude-sonnet-5-5"
    d = _post("https://api.anthropic.com/v1/messages",
              {"model": model, "max_tokens": max_tokens, "temperature": 0.2, "messages": [{"role": "user", "content": prompt}]},
              {"x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json"})
    return parse_json(d["content"][0]["text"]), f"claude:{model}"


def ask_gemini(prompt, max_tokens=6000):
    key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not key:
        raise RuntimeError("GEMINI_API_KEY not set")
    last = ""
    for m in [x for x in (os.environ.get("AUTOFIX_MODEL", "").strip(), "gemini-2.5-flash", "gemini-2.0-flash") if x]:
        try:
            d = _post(f"https://generativelanguage.googleapis.com/v1beta/models/{m}:generateContent?key={key}",
                      {"contents": [{"parts": [{"text": prompt}]}],
                       "generationConfig": {"responseMimeType": "application/json", "temperature": 0.2, "maxOutputTokens": max_tokens}},
                      {"Content-Type": "application/json"})
            return parse_json(d["candidates"][0]["content"]["parts"][0]["text"]), f"gemini:{m}"
        except urllib.error.HTTPError as e:
            last = f"{m}: HTTP {e.code}"
        except Exception as e:  # noqa: BLE001
            last = f"{m}: {type(e).__name__}"
    raise RuntimeError(last or "gemini failed")


def have_model():
    return bool(os.environ.get("ANTHROPIC_API_KEY", "").strip() or os.environ.get("GEMINI_API_KEY", "").strip())


def ask_model(prompt, max_tokens=6000):
    errors = []
    for fn in (ask_claude, ask_gemini):
        try:
            return fn(prompt, max_tokens)
        except Exception as e:  # noqa: BLE001
            errors.append(f"{fn.__name__}: {e}")
    raise RuntimeError("no model available -> " + "; ".join(errors))
