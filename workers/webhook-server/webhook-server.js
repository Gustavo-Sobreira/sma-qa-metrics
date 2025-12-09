const express = require("express");
const bodyParser = require("body-parser");
const axios = require("axios");
const { JADE_ENDPOINT } = require("./config");

const app = express();
app.use(bodyParser.json());

console.log("🔃 Webhook iniciado...");

app.post("/event", async (req, res) => {
    const payload = req.body;

    console.log("📥 Webhook recebeu evento:");
    console.log(JSON.stringify(payload, null, 2));

    if (!payload.type) {
        return res.status(400).send("Missing 'type'");
    }

    let targetAgent = null;

    switch (payload.type) {
        case "sonar-finished":
            targetAgent = "SonarAgent";
            break;
        case "phpmetrics-finished":
            targetAgent = "PhpAgent";
            break;
        default:
            return res.status(400).send("Unknown event type");
    }

    try {
        await axios.post(`${JADE_ENDPOINT}/message`, {
            to: targetAgent,
            payload
        });

        console.log(`Enviado ao JADE → ${targetAgent}`);
        res.status(200).send("OK");
    } catch (err) {
        console.error("Erro enviando ao JADE:", err.message);
        res.status(500).send("Error");
    }
});

app.listen(4000, () => {
    console.log("Webhook escutando em http://localhost:4000/event");
});
