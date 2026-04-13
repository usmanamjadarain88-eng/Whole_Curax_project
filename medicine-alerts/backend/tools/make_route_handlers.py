"""Generate utils/route_handlers.py from api_server.py (no Flask)."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# Flask-era source kept for regeneration (see backend/tools/_flask_api_server_snapshot.py).
SRC = ROOT / "tools" / "_flask_api_server_snapshot.py"
DST = ROOT / "utils" / "route_handlers.py"


def extract_jsonify_arg(s: str, start: int) -> tuple[str, int]:
    """start is first char inside the opening `(` of `jsonify(` — match that closing `)`."""
    depth = 1
    i = start
    begin = start
    while i < len(s):
        c = s[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return s[begin:i], i + 1
        i += 1
    raise ValueError("unbalanced parens")


def fix_parenthesized_jsonify_tuples(text: str) -> str:
    """(jsonify(...), 400) -> (400, ...) anywhere in file."""
    search = "(jsonify("
    pos = 0
    while True:
        i = text.find(search, pos)
        if i == -1:
            break
        arg_start = i + len(search)
        try:
            inner, after = extract_jsonify_arg(text, arg_start)
        except ValueError:
            pos = i + 1
            continue
        rest = text[after:].lstrip()
        m = re.match(r",\s*(\d+)\s*\)", rest)
        if not m:
            pos = i + 1
            continue
        code = m.group(1)
        end = after + m.end()
        text = text[:i] + f"({code}, {inner})" + text[end:]
        pos = i
    return text


def fix_return_jsonify(text: str) -> str:
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith('return "", 204', i):
            out.append("return (204, None)")
            i += len('return "", 204')
            continue
        if text.startswith("return jsonify(", i):
            j = i + len("return jsonify(")
            inner, after = extract_jsonify_arg(text, j)
            rest = text[after:].lstrip()
            if rest.startswith(","):
                # Do not consume newlines — only same-line spaces after status code
                m = re.match(r",\s*(\d+)[ \t]*", rest)
                if m:
                    code = m.group(1)
                    k = after + m.end()
                    if k < len(text) and text[k] == ")":
                        k += 1
                    out.append(f"return ({code}, {inner})")
                    i = k
                    continue
                m_var = re.match(r",\s*(code)\b[ \t]*", rest)
                if m_var:
                    k = after + m_var.end()
                    out.append(f"return (code, {inner})")
                    i = k
                    continue
            out.append(f"return (200, {inner})")
            i = after
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


def patch_route_signatures(text: str) -> str:
    lines = text.splitlines(keepends=True)
    out = []
    for line in lines:
        if re.match(r"^def delete_admin_user\(user_id\):\s*$", line):
            out.append("def delete_admin_user(user_id, body, query, headers):\n")
            continue
        if re.match(r"^def update_medicine\(medicine_id\):\s*$", line):
            out.append("def update_medicine(medicine_id, body, query, headers):\n")
            continue
        if re.match(r"^def delete_medicine\(medicine_id\):\s*$", line):
            out.append("def delete_medicine(medicine_id, body, query, headers):\n")
            continue
        m = re.match(r"^def ([a-zA-Z0-9_]+)\(\):\s*$", line)
        if m:
            name = m.group(1)
            if name.startswith("_"):
                out.append(line)
                continue
            out.append(f"def {name}(body, query, headers):\n")
            continue
        out.append(line)
    return "".join(out)


def main() -> None:
    raw = SRC.read_text(encoding="utf-8")
    start = raw.index('@app.route("/")')
    end = raw.index("# ---- Backend alert scheduler")
    chunk = raw[start:end]

    chunk = re.sub(r"^\s*@app\.route\([^\n]+\)\s*\n", "", chunk, flags=re.MULTILINE)
    chunk = chunk.replace("request.get_json() or {}", "body")
    chunk = chunk.replace("request.get_json(silent=True) or {}", "body")
    chunk = chunk.replace("request.args.get", "query.get")
    chunk = chunk.replace("request.headers.get", "headers.get")
    chunk = chunk.replace("_trigger_alert_checks_for_admin", "trigger_alert_checks_for_admin")

    chunk = fix_parenthesized_jsonify_tuples(chunk)
    chunk = fix_return_jsonify(chunk)
    chunk = chunk.replace(
        "return jsonify(settings or {}) if ok else (jsonify({\"message\": \"failed\"}), 500)",
        "return (200, settings or {}) if ok else (500, {\"message\": \"failed\"})",
    )

    chunk = patch_route_signatures(chunk)

    header = (
        '"""\nStateless route logic for Vercel serverless.\n'
        "Returns (status_code, body); body is dict|list|str|None (204).\n"
        '"""\nfrom __future__ import annotations\n\n'
        "import asyncio\nimport json\nimport os\nimport threading\nimport time\n\n"
        "from central_db import EmailAlreadyUsedError\n\n"
        "from utils.databus import notify_databus\n"
        "from utils.db import get_db\n"
        "from utils.scheduler_shim import trigger_alert_checks_for_admin\n\n"
    )

    DST.write_text(header + chunk, encoding="utf-8")
    print("Wrote", DST, "bytes", len(header + chunk))


if __name__ == "__main__":
    main()
