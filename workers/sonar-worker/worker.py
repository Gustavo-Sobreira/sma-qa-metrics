from flask import Flask, request, jsonify
import subprocess
import os
import shutil

app = Flask(__name__)

REPOS_BASE = "/repos"

@app.route("/scan", methods=["POST"])
def scan():
    data = request.json
    repo_path = data.get("repo_path")

    if not repo_path or not os.path.isdir(repo_path):
        return jsonify({"ok": False, "error": "Invalid repo_path"}), 400

    print(f"[sonar-worker] Running scan for: {repo_path}")

    # Copiar sonar-project.properties para dentro do repositório
    src_prop = "/app/sonar-project.properties"
    dst_prop = os.path.join(repo_path, "sonar-project.properties")
    shutil.copyfile(src_prop, dst_prop)

    print(f"[sonar-worker] sonar-project.properties configurado em {dst_prop}")

    # Executar sonar-scanner dentro do repositório
    cmd = [
        "sonar-scanner",
        f"-Dsonar.projectBaseDir={repo_path}"
    ]

    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True
        )

        return jsonify({
            "ok": result.returncode == 0,
            "exit_code": result.returncode,
            "stdout": result.stdout,
            "stderr": result.stderr
        })

    except Exception as e:
        return jsonify({"ok": False, "error": str(e)}), 500


@app.route("/", methods=["GET"])
def home():
    return "sonar-worker OK"


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=9100)
