# syntax=docker/dockerfile:1
# Build from the frontend directory: docker build -t ifp/frontend frontend

# The bundle is platform-independent: build natively, then assemble the nginx image for each target platform.
FROM --platform=$BUILDPLATFORM node:25-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:stable-alpine
# Pick up Alpine security fixes released after the base image was built.
USER root
RUN apk upgrade --no-cache
USER 101
COPY docker/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY docker/nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --chmod=0755 docker/40-ifp-runtime-config.sh /docker-entrypoint.d/40-ifp-runtime-config.sh
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
# Only config.json is rewritten at start-up; every other asset stays root-owned and read-only for the nginx user.
USER root
RUN chown 101:101 /usr/share/nginx/html/config.json
USER 101

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
