FROM node:22-alpine

WORKDIR /app
COPY server ./server
COPY player ./player
COPY admin ./admin
COPY media ./media
# Ubuntu-agent + installatiescript (te downloaden via /ubuntu/)
COPY ubuntu ./ubuntu
# oude SEM-TV pagina's blijven bereikbaar onder /legacy (en leveren de fonts voor de player)
COPY html ./html

ENV PORT=80 \
    DATA_DIR=/data \
    WEB_ROOT=/app

VOLUME /data
EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:80/api/health || exit 1

CMD ["node", "server/server.js"]
