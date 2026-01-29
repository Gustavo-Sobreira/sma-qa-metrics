FROM python:3.11-slim

RUN apt-get update && \
    apt-get install -y wget unzip openjdk-21-jre && \
    wget -q https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-5.0.1.3006-linux.zip && \
    unzip sonar-scanner-cli-5.0.1.3006-linux.zip -d /opt && \
    mv /opt/sonar-scanner-* /opt/sonar-scanner && \
    rm sonar-scanner-cli-5.0.1.3006-linux.zip && \
    apt-get clean


ENV PATH="/opt/sonar-scanner/bin:${PATH}"


RUN mkdir -p /repos

WORKDIR /app


COPY requirements.txt .
RUN pip install -r requirements.txt

COPY worker.py /app/worker.py

EXPOSE 9100

CMD ["python3", "/app/worker.py"]
