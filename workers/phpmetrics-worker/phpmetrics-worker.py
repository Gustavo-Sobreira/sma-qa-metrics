from flask import Flask, request, jsonify
import subprocess
import tempfile
import os
import json
import re

app = Flask(__name__)

# expressão regular para capturar caminhos de arquivos .php
FILE_REGEX = re.compile(r"(/[^\s]+\.php)")

@app.post("/scan")
def scan():
    data = request.get_json()
    repo_path = data.get("repo_path")

    if not repo_path or not os.path.isdir(repo_path):
        return jsonify({
            "ok": False,
            "exit_code": 1,
            "stdout": "",
            "stderr": f"Directory {repo_path} does not exist",
            "report_json": None,
            "last_file_analyzed": None
        })

    tmp_path = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=".json") as tmp:
            tmp_path = tmp.name

        # -----------------------------------------------------------
        # 🔥 Rodar phpmetrics com debug ativado
        # -----------------------------------------------------------
        cmd = [
            "phpmetrics",
            "--report-json=" + tmp_path,
            "--git",
            "--metrics",
            "--quiet=0",
            "--level=default",
            "--exclude=vendor",
            "--debug",
            repo_path
        ]

        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )

        stdout, stderr = proc.communicate()

        # -----------------------------------------------------------
        # 🔥 Detectar o último arquivo analisado no stderr
        # -----------------------------------------------------------
        last_file = None
        matches = FILE_REGEX.findall(stderr)
        if matches:
            last_file = matches[-1]  # último arquivo encontrado

        # -----------------------------------------------------------
        # Ler JSON gerado (se válido)
        # -----------------------------------------------------------
        report_json = None
        if tmp_path and os.path.exists(tmp_path):
            try:
                with open(tmp_path, "r") as f:
                    report_json = json.load(f)
            except Exception as e:
                stderr += f"\nFailed to parse JSON: {str(e)}"

        return jsonify({
            "ok": (proc.returncode == 0),
            "exit_code": proc.returncode,
            "stdout": stdout,
            "stderr": stderr,
            "last_file_analyzed": last_file,
            "report_json": report_json
        })

    except Exception as e:
        return jsonify({
            "ok": False,
            "exit_code": 1,
            "stdout": "",
            "stderr": str(e),
            "report_json": None,
            "last_file_analyzed": None
        })

    finally:
        if tmp_path and os.path.exists(tmp_path):
            os.remove(tmp_path)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=9200)
