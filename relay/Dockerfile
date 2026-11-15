FROM node:22-alpine
WORKDIR /app
COPY package.json server.js ./
USER node
EXPOSE 31887
CMD ["node", "server.js"]
