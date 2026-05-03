# SMA QA Metrics

Sistema multiagente em Java/JADE para analisar repositórios Git com SonarQube, métricas de evolução de commits, MongoDB e Gemini. O fluxo começa em um webhook HTTP, clona ou atualiza o repositório, executa análise estática, coleta métricas de projeto e gera um relatório final com LLM.

## Componentes

- `jade`: aplicação Java com os agentes JADE.
- `mongo`: banco usado pelo pipeline para status, logs, métricas e relatórios.
- `postgres`: banco usado pelo SonarQube.
- `sonarqube`: interface e API de análise estática.
- `sonar-worker`: worker HTTP que executa `sonar-scanner` no repositório clonado.

## Pré-requisitos

- Docker
- Docker Compose
- Git
- Acesso à internet para baixar imagens Docker, dependências Maven e repositórios Git
- Uma chave do Gemini, caso queira gerar o relatório final com LLM

## Estrutura Importante

```text
.
├── docker-compose.yml
├── .env
├── agentes_jade/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/br/uerj/multiagentes/
└── workers/sonar-worker/
    ├── Dockerfile
    ├── requirements.txt
    └── worker.py
```

## 1. Configure o `.env`

Crie ou ajuste o arquivo `.env` na raiz do projeto. Use `=` entre chave e valor.

```env
PORT_JADE=8080
PORT_WEBHOOK=8090
PORT_MONGO=27017
PORT_POSTGRES=5432
PORT_SONARQUBE=9000
PORT_SONAR_WORKER=9100

URI_MONGO=mongodb://mongo:27017
URI_SONAR_JDBC=jdbc:postgresql://postgres:5432/sonar
URL_SONAR=http://sonarqube:9000

POSTGRES_DB=sonar
POSTGRES_USER=sonar
POSTGRES_PASSWORD=troque_esta_senha

SONAR_TOKEN=cole_o_token_do_sonarqube_aqui

LLM_ENABLED=true
LLM_PROVIDER=gemini
GEMINI_API_KEY=cole_sua_chave_gemini_aqui
GEMINI_MODEL=gemini-2.5-flash
GEMINI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/models/
```

Não versionar tokens reais. Se algum token real já foi salvo em arquivo, gere um novo token no provedor correspondente.

## 2. Suba o SonarQube pela primeira vez

O SonarQube precisa iniciar antes de você gerar o token.

```bash
docker compose up -d postgres sonarqube
```

Aguarde o SonarQube ficar disponível:

```bash
docker compose ps
```

Acesse:

```text
http://localhost:9000
```

No primeiro acesso, use o login padrão do SonarQube:

```text
usuário: admin
senha: admin
```

O SonarQube pode pedir para trocar a senha. Depois gere um token em:

```text
My Account -> Security -> Generate Tokens
```

Copie o token gerado para `SONAR_TOKEN` no `.env`. Esse token precisa ter permissão para criar projetos e executar análises.

## 3. Suba o projeto completo

Depois de preencher o `.env`, suba todos os serviços:

```bash
docker compose up -d --build
```

Confira se os containers estão rodando:

```bash
docker compose ps
```

Serviços esperados:

- `jade`
- `mongo`
- `postgres`
- `sonarqube`
- `sonar-worker`

Para acompanhar logs:

```bash
docker compose logs -f jade
```

Ou, para o worker do Sonar:

```bash
docker compose logs -f sonar-worker
```

## 4. Use a interface web

Com o container `jade` rodando, abra:

```text
http://localhost:8090/
```

A interface permite iniciar uma análise, acompanhar o progresso do `CoordinatorAgent`, ver erros, consultar o relatório do LLM e navegar pelo dashboard de projetos já analisados.

## 5. Dispare uma análise via HTTP

Envie um repositório Git para o webhook:
(Repositório para testes rápidos, os links dos repositórios usados na pesquisa se encontram no final do arquivo)

```bash
curl -X POST http://localhost:8090/webhook \
  -H "Content-Type: application/json" \
  -d '{"repository":"https://github.com/JamilBine/Projetos-PHP.git"}'
```

Para analisar uma versão específica, envie `branch`, `version`, `git_ref` ou `ref`. O valor pode ser uma branch, tag ou qualquer ref aceita pelo Git:

```bash
curl -X POST http://localhost:8090/webhook \
  -H "Content-Type: application/json" \
  -d '{"repository":"https://github.com/joomla/joomla-cms.git","version":"5.3.4"}'
```

Isso é equivalente a clonar com:

```bash
git clone --branch 5.3.4 --single-branch https://github.com/joomla/joomla-cms.git
```

Também é aceito informar a branch pela URL do GitHub:

```bash
curl -X POST http://localhost:8090/webhook \
  -H "Content-Type: application/json" \
  -d '{"repository":"https://github.com/WordPress/WordPress/tree/6.9-branch"}'
```

A resposta terá um `run_id`:

```json
{"run_id":"..."}
```

Guarde esse valor. Ele identifica a execução no MongoDB.

## 6. Acompanhe o status no MongoDB

Entre no container do Mongo:

```bash
docker compose exec mongo mongosh
```

Selecione o banco:

```javascript
use analysis_results
```

Consulte o status:

```javascript
db.runStatus.find().sort({ created_at: -1 }).limit(5).pretty()
```

Consulte uma execução específica:

```javascript
db.runStatus.findOne({ run_id: "COLE_O_RUN_ID_AQUI" })
```

Coleções úteis:

- `runStatus`: fonte principal do status da execução.
- `logs`: eventos dos agentes.
- `code_metrics`: métricas coletadas do SonarQube.
- `sonar_metrics`: espelho de compatibilidade das métricas do SonarQube.
- `projectsReport`: métricas de commits e dados do projeto.
- `codeReport`: estado interno da análise de código.
- `llm_reports`: relatório final gerado pelo Gemini.

## 6. Verifique o relatório final

Depois que `runStatus.stage` estiver como `DONE`, consulte:

```javascript
db.llm_reports.findOne({ run_id: "COLE_O_RUN_ID_AQUI" })
```

Se a execução falhar, veja o motivo:

```javascript
db.runStatus.findOne(
  { run_id: "COLE_O_RUN_ID_AQUI" },
  { stage: 1, failed: 1, reason: 1, failed_at: 1 }
)
```

E veja os logs:

```javascript
db.logs.find({ run_id: "COLE_O_RUN_ID_AQUI" }).sort({ created_at: 1 }).pretty()
```

## Fluxo do Pipeline

1. `CoordinatorAgent` recebe `POST /webhook`.
2. Cria `run_id` e registra a execução em `runStatus`.
3. Envia `RUN_GIT` para `GitAgent`.
4. `GitAgent` clona ou atualiza o repositório em `/repos`.
5. Ao receber `GIT_DONE`, o coordenador dispara:
   - `START_CODE_ANALYSIS` para `CodeAnalyzerAgent`
   - `START_PROJECT_ANALYSIS` para `ProjectAnalyzerAgent`
6. `CodeAnalyzerAgent` solicita `RUN_SONAR` ao `SonarAgent`.
7. `SonarAgent` chama o `sonar-worker`, que cria ou reutiliza um projeto no SonarQube usando uma chave derivada do repositório.
8. `CodeAnalyzerAgent` busca as métricas na API do SonarQube e salva em `code_metrics`.
9. `ProjectAnalyzerAgent` solicita `RUN_PROJECT_ANALYZER` ao `GitLogAgent`.
10. `GitLogAgent` calcula métricas de commits e salva em `projectsReport`.
11. Quando código e projeto terminam sem falha, o coordenador envia `RUN_LLM`.
12. `LlmAgent` carrega os dados do Mongo, chama Gemini e salva o relatório em `llm_reports`.

## Métricas Coletadas do SonarQube

O pipeline coleta:

- `bugs`
- `reliability_rating`
- `vulnerabilities`
- `security_hotspots`
- `security_rating`
- `code_smells`
- `sqale_index`
- `sqale_debt_ratio`
- `coverage`
- `duplicated_lines_density`
- `duplicated_blocks`
- `ncloc`
- `complexity`

## Projeto Dinâmico no SonarQube

O projeto SonarQube é criado automaticamente para cada repositório. O `CoordinatorAgent` gera:

- `sonar_project_key`: chave estável baseada em dono e nome do repositório, como `sma:owner:repo`.
- `sonar_project_name`: nome visível do projeto, baseado no nome do repositório.

O `sonar-worker` usa esses valores para chamar:

```text
POST /api/projects/create
```

Se o projeto já existir, o worker segue normalmente e executa o scan. A variável `SONAR_PROJECT` continua funcionando apenas como fallback.

## Comandos Úteis

Parar os serviços:

```bash
docker compose down
```

Parar e remover volumes locais:

```bash
docker compose down -v
```

Recompilar apenas o JADE:

```bash
docker compose build jade
docker compose up -d jade
```

Rodar compilação Java localmente:

```bash
cd agentes_jade
mvn -DskipTests compile
```

Ver logs de todos os serviços:

```bash
docker compose logs -f
```

## Problemas Comuns

### SonarQube demora para subir

Na primeira execução, o SonarQube pode levar alguns minutos. Verifique:

```bash
docker compose logs -f sonarqube
```

### `SONAR_TOKEN` inválido

Gere um novo token no SonarQube e atualize o `.env`. Depois reinicie:

```bash
docker compose up -d jade sonar-worker
```

### O webhook não responde

Confira se `jade` está rodando e se a porta está correta:

```bash
docker compose ps jade
docker compose logs jade
```

Por padrão, o webhook fica em:

```text
http://localhost:8090/webhook
```

### O relatório LLM não aparece

Verifique se:

- `GEMINI_API_KEY` está preenchida.
- `LLM_ENABLED=true`.
- `runStatus.code_ok=true`.
- `runStatus.project_ok=true`.
- `runStatus.failed` não está `true`.

Depois consulte:

```javascript
db.logs.find({ run_id: "COLE_O_RUN_ID_AQUI", agent: "llm" }).pretty()
```

## Desenvolvimento

Pacote Java principal:

```text
br.uerj.multiagentes
```

Agentes principais:

- `CoordinatorAgent`
- `GitAgent`
- `CodeAnalyzerAgent`
- `SonarAgent`
- `ProjectAnalyzerAgent`
- `GitLogAgent`
- `LlmAgent`

Ao alterar agentes Java, rode:

```bash
cd agentes_jade
mvn -DskipTests compile
```

Depois reconstrua o container:

```bash
docker compose build jade
docker compose up -d jade
```


## CMS analisados
### WordPress
- Versões 6.9.x, 6.8.x, 6.7.x, 6.6.x e 6.5.x
- Pegamos as versões mais recentes de cada branch para análise, disponíveis em https://br.wordpress.org/download/releases/
```bash
curl -X POST http://localhost:8090/webhook \
  -H "Content-Type: application/json" \
  -d '{"repository":"https://github.com/WordPress/WordPress.git","branch":"6.9-branch"}'
```

### Drupal
- Versões 11.x, 11.0.x, 11.1.x, 11.2.x e 11.3.x
- Pegamos as versões mais recentes de cada branch para análise, disponíveis em https://www.drupal.org/project/drupal/releases
```bash
curl -X POST http://localhost:8090/webhook \
  -H "Content-Type: application/json" \
  -d '{"repository":"https://github.com/drupal/drupal.git","branch":"11.3.x"}'
```

### Joomla
- Versões 5.4.2, 5.4.3, 5.4.4, 5.4.5 e 6.0.0
- Pegamos as versões mais recentes de cada branch para análise, disponíveis em https://downloads.joomla.org/
```bash
curl -X POST http://localhost:8090/webhook \
  -H "Content-Type: application/json" \
  -d '{"repository":"https://github.com/joomla/joomla-cms.git","version":"5.3.4"}'
```
