from flask import Flask, request, jsonify
import subprocess
import os
import json
import time
import base64
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

app = Flask(__name__)

@app.route("/scan", methods=["POST"])
def scan():
    data = request.get_json(silent=True) or {}
    repo_path = data.get("repo_path")

    if not repo_path or not os.path.isdir(repo_path):
        return jsonify({
            "ok": False,
            "exit_code": -2,
            "stdout": "",
            "stderr": "",
            "error": "Invalid repo_path"
        }), 400

    print(f"[sonar-worker] Running scan for: {repo_path}")

    URL_SONAR = os.getenv("URL_SONAR", os.getenv("SONAR_HOST_URL", "http://sonarqube:9000"))
    sonar_token = os.getenv("SONAR_TOKEN") or data.get("sonar_token")
    sonar_project = data.get("sonar_project_key") or data.get("sonar_project") or os.getenv("SONAR_PROJECT") or project_from_path(repo_path)
    sonar_project_name = data.get("sonar_project_name") or sonar_project

    if not sonar_token:
        return jsonify({
            "ok": False,
            "exit_code": -3,
            "stdout": "",
            "stderr": "",
            "error": "Missing SONAR_TOKEN (env) or sonar_token (request)"
        }), 400

    created, create_message = ensure_sonar_project(URL_SONAR, sonar_token, sonar_project, sonar_project_name)
    if not created:
        return jsonify({
            "ok": False,
            "exit_code": -4,
            "stdout": "",
            "stderr": "",
            "error": create_message,
            "project_key": sonar_project,
            "project_name": sonar_project_name
        }), 500

    dst_prop = os.path.join(repo_path, "sonar-project.properties")

    with open(dst_prop, "w", encoding="utf-8") as f:
        f.write(f"sonar.host.url={URL_SONAR}\n")
        f.write(f"sonar.token={sonar_token}\n")
        f.write("\n")
        f.write(f"sonar.projectKey={sonar_project}\n")
        f.write(f"sonar.projectName={sonar_project_name}\n")
        f.write("sonar.projectVersion=1.0.0\n")
        f.write("\n")
        f.write("sonar.sources=.\n")
        f.write("sonar.sourceEncoding=UTF-8\n")
        f.write("sonar.exclusions=**/vendor/**,**/node_modules/**,**/.git/**\n")

    print(f"[sonar-worker] sonar-project.properties gerado em {dst_prop} (projectKey={sonar_project})")

    cmd = [
        "sonar-scanner",
        f"-Dsonar.projectBaseDir={repo_path}",
    ]

    started_at = time.time()

    try:
        result = subprocess.run(
            cmd,
            cwd=repo_path,          
            capture_output=True,
            text=True
        )

        duration_ms = int((time.time() - started_at) * 1000)

        payload = {
            "ok": result.returncode == 0,
            "exit_code": int(result.returncode),
            "stdout": result.stdout or "",
            "stderr": result.stderr or "",
            "duration_ms": duration_ms,
            "project_key": sonar_project,
            "project_name": sonar_project_name
        }

        print(f"[sonar-worker] Scan finished: ok={payload['ok']} exit_code={payload['exit_code']} duration_ms={duration_ms}")

        try:
            sma_dir = os.path.join(repo_path, ".sma", "sonar")
            os.makedirs(sma_dir, exist_ok=True)
            with open(os.path.join(sma_dir, "worker-last.json"), "w", encoding="utf-8") as wf:
                json.dump(payload, wf, ensure_ascii=False, indent=2)
        except Exception as _:
            pass

        status = 200 if payload["ok"] else 500
        return jsonify(payload), status

    except Exception as e:
        duration_ms = int((time.time() - started_at) * 1000)

        payload = {
            "ok": False,
            "exit_code": -1,
            "stdout": "",
            "stderr": "",
            "error": str(e),
            "duration_ms": duration_ms,
            "project_key": sonar_project,
            "project_name": sonar_project_name
        }

        print(f"[sonar-worker] Exception: {payload['error']}")

        try:
            sma_dir = os.path.join(repo_path, ".sma", "sonar")
            os.makedirs(sma_dir, exist_ok=True)
            with open(os.path.join(sma_dir, "worker-last.json"), "w", encoding="utf-8") as wf:
                json.dump(payload, wf, ensure_ascii=False, indent=2)
        except Exception as _:
            pass

        return jsonify(payload), 500


@app.route("/", methods=["GET"])
def home():
    return "sonar-worker OK"


def ensure_sonar_project(base_url, token, project_key, project_name):
    form = urlencode({
        "project": project_key,
        "name": project_name,
    }).encode("utf-8")

    auth = base64.b64encode(f"{token}:".encode("utf-8")).decode("ascii")
    req = Request(
        base_url.rstrip("/") + "/api/projects/create",
        data=form,
        method="POST",
        headers={
            "Authorization": f"Basic {auth}",
            "Content-Type": "application/x-www-form-urlencoded",
        },
    )

    try:
        with urlopen(req, timeout=30) as resp:
            if 200 <= resp.status < 300:
                return True, "created"
            body = resp.read().decode("utf-8", errors="replace")
            return False, f"Project create failed: http={resp.status} body={body[:500]}"
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        lower = body.lower()
        if e.code == 400 and ("already" in lower or "exist" in lower):
            return True, "already exists"
        return False, f"Project create failed: http={e.code} body={body[:500]}"
    except URLError as e:
        return False, f"Project create failed: {e.reason}"
    except Exception as e:
        return False, f"Project create failed: {e}"


def project_from_path(repo_path):
    name = os.path.basename(os.path.normpath(repo_path)) or "repository"
    safe = "".join(ch if ch.isalnum() or ch in "-_.:" else "-" for ch in name)
    safe = safe.strip("-_.:") or "repository"
    return f"sma:{safe}"


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=9100)
