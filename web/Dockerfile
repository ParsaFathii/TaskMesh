# TaskMesh web console — build with Vite, serve with nginx
# Build:  docker build -t taskmesh-web:0.1.0 ./web
# Run:    docker run -p 8081:80 taskmesh-web:0.1.0 (expects TASKMESH_API upstream via nginx proxy)

FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.31-alpine
LABEL org.opencontainers.image.title="TaskMesh Web Console" \
      org.opencontainers.image.description="TaskMesh operations console — queue lanes, live job monitoring" \
      org.opencontainers.image.version="0.1.0" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/ParsaFathii/TaskMesh"
COPY --from=build /src/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
