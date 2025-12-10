###############################
# Stage 1 — pegar phpmetrics.phar
###############################
FROM phpmetrics/phpmetrics:latest AS phpmetrics

###############################
# Stage 2 — ambiente Python moderno
###############################
FROM python:3.11-slim

# Instala dependências para rodar o comando php
RUN apt-get update && \
    apt-get install -y php-cli && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copia o binário phpmetrics do stage anterior
COPY --from=phpmetrics /usr/local/bin/phpmetrics /usr/local/bin/phpmetrics

# Copia o worker Flask
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY phpmetrics-worker.py .

EXPOSE 9200

CMD ["python3", "phpmetrics-worker.py"]
