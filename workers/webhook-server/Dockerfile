FROM node:18-alpine
WORKDIR /app

COPY package*.json ./
RUN npm install

COPY workers/webhook-server .

CMD ["node", "webhook-server.js"]
