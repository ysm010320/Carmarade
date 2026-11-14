# 카마라드 방 서버. Node 24(내장 SQLite) + ws.
FROM node:24-slim
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY src ./src
# 컨테이너 안에서는 모든 주소에서 받되, 바깥 공개는 HTTPS 프록시(Caddy)를 통해서만 한다.
ENV HOST=0.0.0.0 PORT=8787 CARMARADE_DB=/data/carmarade.db NODE_ENV=production
RUN mkdir -p /data && chown node:node /data
VOLUME ["/data"]
EXPOSE 8787
USER node
CMD ["node", "src/main.js"]
