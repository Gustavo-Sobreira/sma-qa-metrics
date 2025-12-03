# Multi-Agent CI Metrics - Infra (Etapa 1)

Este repositório contém a infraestrutura (docker-compose e workers) para o sistema multiagente que você solicitou.
Etapa 1: infra + docker-compose + Dockerfiles.

## Serviços principais
- mongo (27017)
- postgres (5432)
- sonarqube (9000)
- sonar-worker (9100) - aceita POST /scan
- phpmetrics-worker (9200) - aceita POST /scan
- jade (8080) - container JADE (ainda espera o jar com os agentes; será gerado na Etapa 2)

## Como subir
1. Construa e suba:
   ```bash
   docker compose build
   docker compose up -d
