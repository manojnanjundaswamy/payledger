# syntax=docker/dockerfile:1.7
#
# Static build served by nginx, which also forwards /api, /oauth2, /login/oauth2 and /logout to the
# gateway: the browser sees one origin, exactly like `npm run dev` with its proxy.
#   docker build -t payledger/web .

FROM --platform=$BUILDPLATFORM node:26-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.31-alpine
LABEL org.opencontainers.image.title="payledger-web" \
      org.opencontainers.image.description="PayLedger web app (React)" \
      org.opencontainers.image.licenses="MIT"
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
# Where the gateway is, from inside the container.
ENV GATEWAY_URL=http://gateway:8000
# Keycloak, for the sign-in pages when the app is hosted on one address.
ENV KEYCLOAK_URL=http://keycloak:8080
# The DNS server nginx asks for the gateway's address (Docker's built-in one; in Kubernetes, the cluster DNS).
ENV NGINX_RESOLVER=127.0.0.11
# Only these are substituted into the template, so nginx's own $variables stay as they are.
ENV NGINX_ENVSUBST_FILTER="^(GATEWAY_URL|KEYCLOAK_URL|NGINX_RESOLVER)$"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
