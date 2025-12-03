FROM node:18-alpine
WORKDIR /app

COPY package*.json ./
RUN npm install

COPY workers/phpmetrics-worker .

CMD ["node", "phpmetrics-worker.js"]
