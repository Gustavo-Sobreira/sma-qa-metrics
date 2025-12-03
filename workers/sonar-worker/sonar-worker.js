const axios = require("axios");
const { SONAR_URL, PROJECT_KEY, WEBHOOK_URL } = require("./config");

console.log("🔧 SonarWorker iniciado...");

async function checkSonar() {
    try {
        const res = await axios.get(
            `${SONAR_URL}/api/measures/component?component=${PROJECT_KEY}&metricKeys=bugs,vulnerabilities,code_smells,coverage`
        );

        const measures = res.data.component.measures;

        const payload = {
            type: "sonar-finished",
            timestamp: new Date(),
            project: PROJECT_KEY,
            measures
        };

        console.log("📤 Enviando evento Sonar → Webhook");
        await axios.post(`${WEBHOOK_URL}/event`, payload);

        console.log("✔ Evento enviado");
    } catch (err) {
        console.error("❌ Erro SonarWorker:", err.message);
    }
}

setInterval(checkSonar, 30_000);
