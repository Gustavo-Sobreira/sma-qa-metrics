# JADE CI/CD Agents (Coordinator, CodeAnalyzerAgent, GitLogAgent)

Este projeto contém uma solução completa para:

- GitHub Actions que notifica o CoordinatorAgent a cada push.
- CoordinatorAgent clona o repo, identifica o último commit, envia:
  - CodeAnalyzerAgent: apenas os arquivos alterados no último commit;
  - GitLogAgent: todo o git log do projeto.
- CodeAnalyzerAgent executa phpmetrics e sonar-scanner, salva os resultados em PostgreSQL.
- GitLogAgent analisa git log (commits por autor) e salva em PostgreSQL.
- Infra via docker-compose (Postgres, SonarQube, agents container).

## Como usar (resumido)

1. Construa e suba a infra:
   ```
   docker-compose up --build
   ```

2. Após o Postgres subir, crie o schema:
   ```
   docker exec -i $(docker-compose ps -q postgres) psql -U sonar -d agents_db < sql/schema.sql
   ```

3. Configure um webhook (no GitHub Actions ou via `curl`) para `http://<host>:8080/webhook`.
   Em GitHub Actions defina `COORDINATOR_URL` no Secrets apontando para este endpoint.

4. Ajustes:
   - Configure `SONAR_LOGIN` se precisar autenticar no SonarQube.
   - Verifique que `phpmetrics` e `sonar-scanner` estão instalados no container de `agents` (o Dockerfile tenta instalar).
