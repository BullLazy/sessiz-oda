FROM node:24-alpine

WORKDIR /app
COPY --chown=node:node package.json package-lock.json server.js ./

ENV NODE_ENV=production
ENV PORT=8080
EXPOSE 8080

USER node
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD node -e "fetch('http://127.0.0.1:8080/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server.js"]

