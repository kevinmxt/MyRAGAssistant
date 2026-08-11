"""LightRAG bridge — persistent child process with JSON line protocol.

Java (LightRagBridge) spawns this process and communicates as follows:
  - stdin:   one JSON request per line, e.g. {"cmd": "query", "text": "...", "mode": "hybrid"}
  - stdout:  one JSON response per line, e.g. {"ok": true, "result": [...]}
  - stderr:  all logs / library prints (redirected here to keep stdout clean)

Commands: init, insert, query, exit.
"""

import json
import os
import sys

# ---- protocol channel setup ----
# 1. Preserve a copy of the original stdout fd for JSON protocol output.
# 2. Redirect fd 1 to /dev/null at OS level — prevents C extensions
#    (PyTorch, HuggingFace, ONNX, etc.) from writing to the Java protocol pipe.
# 3. Redirect Python-level sys.stdout to stderr as a fallback.
_protocol_fd = os.dup(1)
_devnull = os.open(os.devnull, os.O_WRONLY)
os.dup2(_devnull, 1)
os.close(_devnull)
sys.stdout = sys.stderr
_protocol = os.fdopen(_protocol_fd, "w", encoding="utf-8", buffering=1)


def emit(obj):
    _protocol.write(json.dumps(obj, ensure_ascii=False) + "\n")
    _protocol.flush()


from lightrag import LightRAG, QueryParam  # noqa: E402

_rag = None  # singleton LightRAG instance


def init_lightrag(working_dir, embedding_model_path, api_key, base_url, model_name):
    """Initialize LightRAG instance. Called from Java at bridge startup."""
    global _rag
    os.makedirs(working_dir, exist_ok=True)

    # LLM function for LightRAG — reuse the OpenAI-compatible API config
    def llm_model_func(prompt, system_prompt=None, **kwargs):
        import requests
        headers = {"Authorization": "Bearer " + api_key, "Content-Type": "application/json"}
        messages = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": prompt})
        resp = requests.post(base_url + "/chat/completions", json={
            "model": model_name, "messages": messages, "max_tokens": 4096,
        }, headers=headers, timeout=120)
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]

    _rag = LightRAG(
        working_dir=working_dir,
        llm_model_func=llm_model_func,
        embedding_model_name_or_path=embedding_model_path,
    )


def handle(req):
    cmd = req.get("cmd")
    if cmd == "init":
        init_lightrag(
            req.get("workingDir", ""),
            req.get("embeddingModelPath", ""),
            req.get("apiKey", ""),
            req.get("baseUrl", ""),
            req.get("modelName", ""),
        )
        return True
    if cmd == "insert":
        if _rag is None:
            return False
        docs = req.get("docs") or {}
        for name, text in docs.items():
            _rag.insert(text, track_id=name)
        return True
    if cmd == "query":
        if _rag is None:
            return []
        param = QueryParam(mode=req.get("mode", "hybrid"))
        result = _rag.query(req.get("text", ""), param=param)
        # LightRAG query returns a string, return as single-element list
        return [result] if result else []
    if cmd == "exit":
        return "bye"
    raise ValueError("unknown command: " + cmd)


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            result = handle(req)
            emit({"ok": True, "result": result})
            if req.get("cmd") == "exit":
                return
        except Exception as e:  # noqa: BLE001 — protocol must never crash mid-line
            emit({"ok": False, "error": str(e)})
            if '"cmd": "exit"' in line:
                return


if __name__ == "__main__":
    main()
