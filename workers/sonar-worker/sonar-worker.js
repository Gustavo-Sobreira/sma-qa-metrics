const axios = require("axios");
const { SONAR_URL, PROJECT_KEY, WEBHOOK_URL, SONAR_TOKEN } = require("./config");

console.log("SonarWorker iniciado...");

async function checkSonar() {
    try {

        const axiosSonar = axios.create({
            baseURL: SONAR_URL,
            timeout: 10000,
            auth: {
                username: SONAR_TOKEN,
                password: ""
            }
        });

        const res = await axiosSonar.get(
            `/api/measures/component`,
            {
                params: {
                    component: PROJECT_KEY,
                    metricKeys: "bugs,vulnerabilities,code_smells,coverage"
                }
            }
        );

        const measures = res.data.component.measures;

        const payload = {
            type: "sonar-finished",
            timestamp: new Date(),
            project: PROJECT_KEY,
            measures
        };

        console.log("Enviando evento Sonar → Webhook");
        
        await axios.post(WEBHOOK_URL, payload);

        console.log("Evento enviado");

    } catch (err) {
        console.error("Erro SonarWorker:", err.message);
    }
}

setInterval(checkSonar, 30_000);
