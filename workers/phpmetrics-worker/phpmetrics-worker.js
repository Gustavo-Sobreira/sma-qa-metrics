const { exec } = require("child_process");
const axios = require("axios");
const fs = require("fs");
const { WEBHOOK_URL, PHPMETRICS_CMD, JSON_OUTPUT } = require("./config");

console.log("📊 PhpMetricsWorker iniciado...");

function runPhpMetrics() {
    exec(PHPMETRICS_CMD, (err) => {
        if (err) {
            console.error("❌ Erro executando phpmetrics:", err);
            return;
        }

        const data = JSON.parse(fs.readFileSync(JSON_OUTPUT, "utf8"));

        const payload = {
            type: "phpmetrics-finished",
            timestamp: new Date(),
            report: data
        };

        console.log("📤 Enviando evento PhpMetrics → Webhook");

        axios.post(`${WEBHOOK_URL}/event`, payload)
            .then(() => console.log("✔ Evento enviado"))
            .catch(err => console.error("❌ Erro enviando ao Webhook:", err.message));
    });
}

setInterval(runPhpMetrics, 45_000);
