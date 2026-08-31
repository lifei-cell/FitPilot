FROM node:24-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html tsconfig.json vite.config.ts ./
COPY src ./src
COPY .openai ./.openai
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.31.4-alpine3.24-slim@sha256:d668aa123a6ec3216ba5ae6b398ae8001d5e81d3142d3659e20354fd0c3c3125
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
