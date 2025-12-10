FROM python:3.11-slim

# -----------------------------
# Instalar dependências do sistema + JRE + sonar-scanner
# -----------------------------
RUN apt-get update && \
    apt-get install -y wget unzip openjdk-21-jre && \
    wget -q https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-5.0.1.3006-linux.zip && \
    unzip sonar-scanner-cli-5.0.1.3006-linux.zip -d /opt && \
    mv /opt/sonar-scanner-* /opt/sonar-scanner && \
    rm sonar-scanner-cli-5.0.1.3006-linux.zip && \
    apt-get clean

# sonar-scanner no PATH
ENV PATH="/opt/sonar-scanner/bin:${PATH}"

# Diretório compartilhado (montado via docker-compose)
RUN mkdir -p /repos

WORKDIR /app

# Requirements do worker Python
COPY requirements.txt .
RUN pip install -r requirements.txt

# Copiar worker + properties padrão
COPY worker.py /app/worker.py
COPY sonar-project.properties /app/sonar-project.properties

EXPOSE 9100

CMD ["python3", "/app/worker.py"]
