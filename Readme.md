# SMA QA Metrics

Este projeto implementa uma infraestrutura de análise de qualidade de software baseada em **SonarQube** integrada a um serviço de orquestração (Jade) e a um **worker** dedicado ao disparo de varreduras. O objetivo é automatizar a coleta de métricas de qualidade (bugs, vulnerabilidades, code smells, cobertura, duplicações) e fornecer insumos para estudos sobre engenharia de software, avaliação de código-fonte e governança técnica.

## Arquitetura e componentes
A solução é composta por serviços Docker interligados:

- **Jade (orquestrador)**: recebe eventos via webhook e coordena o fluxo de análise.
- **SonarQube**: plataforma principal de análise estática e governança de qualidade.
- **Sonar Worker**: expõe um endpoint HTTP interno para executar o `sonar-scanner` sobre repositórios montados em volume compartilhado.
- **PostgreSQL**: banco de dados persistente do SonarQube.
- **MongoDB**: persistência auxiliar para o Jade.

Os serviços são definidos em `docker-compose.yml`, incluindo portas, redes, volumes e dependências de saúde entre containers.【F:docker-compose.yml†L1-L160】

## Requisitos
- Docker e Docker Compose instalados.
- Chaves e variáveis de ambiente para o SonarQube e para o provedor de LLM (padrão: Gemini).

## Configuração

### 1) Variáveis de ambiente
O projeto utiliza um arquivo `.env` para configurar serviços essenciais como PostgreSQL e SonarQube. No mínimo, mantenha:

- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
- `SONAR_TOKEN`, `SONAR_PROJECT`

Exemplo de `.env` presente no repositório para referência de formato.【F:.env†L1-L5】

> **Nota acadêmica:** para ambientes de pesquisa, recomenda-se substituir tokens por valores locais e não versionados.

### 2) SonarQube
O SonarQube roda em `http://localhost:9000` (porta padrão). Ao iniciar pela primeira vez, configure o token de autenticação no próprio SonarQube e replique-o em `SONAR_TOKEN` no `.env`.

### 3) Sonar Scanner
O **sonar-worker** executa o `sonar-scanner` dentro do container. Ele gera automaticamente um `sonar-project.properties` no repositório analisado, com base nas variáveis de ambiente e no payload recebido. O arquivo contém:

- URL do SonarQube (`sonar.host.url`)
- Token (`sonar.token`)
- Chave/nome do projeto (`sonar.projectKey`/`sonar.projectName`)
- Diretório base e exclusões padrão

Essas regras são construídas dinamicamente no worker (`worker.py`).【F:workers/sonar-worker/worker.py†L25-L83】

## Como iniciar a ferramenta

1) Suba a infraestrutura:

```bash
docker compose down
# opcional: limpar recursos antigos
# docker system prune -f

docker compose --env-file .env up -d
```

2) Verifique saúde dos serviços:

- SonarQube: `http://localhost:9000`
- Jade (API): `http://localhost:8080`
- Sonar Worker: `http://localhost:9100`

As portas padrão podem ser ajustadas via `.env` e já estão expostas no `docker-compose.yml`.【F:docker-compose.yml†L24-L116】

## Fluxo de uso (webhook)
Após o stack estar ativo, dispare a análise enviando um repositório via webhook:

```bash
curl -X POST http://localhost:8090/webhook \
  -H "Content-Type: application/json" \
  -d '{"repository":"https://github.com/JamilBine/Projetos-PHP.git"}'
```

ou

```bash
curl -X POST http://localhost:8090/webhook \
  -H "Content-Type: application/json" \
  -d '{"repository":"https://github.com/WordPress/WordPress.git"}'
```

Esses exemplos ilustram o formato de payload esperado pelo endpoint de webhook.【F:Readme.md†L66-L81】

## Configuração de análise do Sonar (avançado)
O worker cria o arquivo `sonar-project.properties` no repositório analisado. Caso deseje sobrescrever comportamentos, utilize as variáveis abaixo:

- `URL_SONAR`: URL base do SonarQube (padrão `http://sonarqube:9000`).
- `SONAR_TOKEN`: token de autenticação (obrigatório).
- `SONAR_PROJECT`: chave do projeto no SonarQube.

Essas variáveis são lidas pelo worker antes de executar o `sonar-scanner`.【F:workers/sonar-worker/worker.py†L31-L50】

## Organização do repositório

- `docker-compose.yml`: define toda a infraestrutura local (serviços, dependências e redes).【F:docker-compose.yml†L1-L160】
- `workers/sonar-worker/`: container responsável por executar análises e gerar o `sonar-project.properties` dinamicamente.【F:workers/sonar-worker/worker.py†L1-L133】
- `agentes_jade/`: serviço principal de orquestração (Jade).

## Observações finais
Esta base permite o uso experimental em disciplinas de qualidade de software, engenharia de requisitos e governança de código. Recomenda-se registrar os resultados no SonarQube e correlacioná-los com métricas de evolução do repositório (como churn, número de commits e severidade de issues) para análises acadêmicas quantitativas.
