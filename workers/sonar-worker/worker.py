from flask import Flask, request, jsonify
import subprocess
import os
import json
import time

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
    sonar_project = os.getenv("SONAR_PROJECT") or data.get("sonar_project") or "sma-sonar-agent"

    if not sonar_token:
        return jsonify({
            "ok": False,
            "exit_code": -3,
            "stdout": "",
            "stderr": "",
            "error": "Missing SONAR_TOKEN (env) or sonar_token (request)"
        }), 400

    dst_prop = os.path.join(repo_path, "sonar-project.properties")

    with open(dst_prop, "w", encoding="utf-8") as f:
        f.write(f"sonar.host.url={URL_SONAR}\n")
        f.write(f"sonar.token={sonar_token}\n")
        f.write("\n")
        f.write(f"sonar.projectKey={sonar_project}\n")
        f.write(f"sonar.projectName={sonar_project}\n")
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
            "duration_ms": duration_ms
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
            "duration_ms": duration_ms
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


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=9100)
