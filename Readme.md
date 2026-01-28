# TODOS
[SonarScanner] - precisa mover o sonar-scanner para dentro do container



# Rodar
docker compose down
docker system prune -f
docker compose --env-file .env up -d


# Após subir
curl -X POST http://localhost:8090/webhook \
  -H "Content-Type: application/json" \
  -d '{"repository":"https://github.com/JamilBine/Projetos-PHP.git"}'

ou

curl -X POST http://localhost:8090/webhook \
  -H "Content-Type: application/json" \
  -d '{"repository":"https://github.com/WordPress/WordPress.git"}'