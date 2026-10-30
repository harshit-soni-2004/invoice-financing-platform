#!/usr/bin/env bash
# Installs and starts the platform on an Ubuntu server with a public IP, e.g. an Oracle Cloud Always Free Ampere VM.
# Re-run it to update to the newest images from main. See deploy/oracle/README.md.
#
#   curl -fsSL https://raw.githubusercontent.com/harshit-soni-2004/invoice-financing-platform/main/deploy/oracle/setup.sh | bash
#
# Optional environment: DOMAIN (default: <public-ip>.sslip.io), REPO_URL, BRANCH (default main), IMAGE_TAG (default latest).
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/harshit-soni-2004/invoice-financing-platform.git}"
BRANCH="${BRANCH:-main}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
DIR="${DIR:-$HOME/invoice-financing-platform}"
PROJECT=ifp

log() { printf '\n==> %s\n' "$*"; }

log "Installing Docker, git and the amd64 emulator (MailHog has no Arm image)"
if ! command -v docker >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com | sudo sh
fi
sudo systemctl enable --now docker
sudo apt-get update -qq
sudo apt-get install -y -qq git curl openssl >/dev/null
if [ "$(uname -m)" = "aarch64" ]; then
    sudo apt-get install -y -qq qemu-user-static binfmt-support >/dev/null
fi

log "Opening ports 80 and 443 in the host firewall"
# Oracle's Ubuntu images reject inbound traffic except SSH in iptables, in addition to the VCN security list.
for rule in "-p tcp --dport 80" "-p tcp --dport 443" "-p udp --dport 443"; do
    # shellcheck disable=SC2086
    sudo iptables -C INPUT -m state --state NEW $rule -j ACCEPT 2>/dev/null \
        || sudo iptables -I INPUT 1 -m state --state NEW $rule -j ACCEPT
done
if command -v netfilter-persistent >/dev/null 2>&1; then
    sudo netfilter-persistent save >/dev/null
fi

log "Fetching the code ($BRANCH)"
if [ -d "$DIR/.git" ]; then
    git -C "$DIR" fetch -q origin "$BRANCH"
    git -C "$DIR" checkout -q "$BRANCH"
    git -C "$DIR" merge -q --ff-only "origin/$BRANCH"
else
    git clone -q --branch "$BRANCH" "$REPO_URL" "$DIR"
fi
cd "$DIR"

PUBLIC_IP="$(curl -fsS https://api.ipify.org)"
DOMAIN="${DOMAIN:-${PUBLIC_IP//./-}.sslip.io}"
OWNER="$(printf '%s' "$REPO_URL" | sed -E 's#.*github\.com[:/]([^/]+)/.*#\1#' | tr '[:upper:]' '[:lower:]')"
ENV_FILE="$DIR/.env.public"

secret() { openssl rand -base64 24 | tr '+/' '-_' | tr -d '='; }
set_env() {
    if grep -q "^$1=" "$ENV_FILE"; then
        sed -i "s#^$1=.*#$1=$2#" "$ENV_FILE"
    else
        printf '%s=%s\n' "$1" "$2" >>"$ENV_FILE"
    fi
}

if [ ! -f "$ENV_FILE" ]; then
    log "Creating $ENV_FILE with new random secrets"
    umask 077
    : >"$ENV_FILE"
    for name in MYSQL_ROOT_PASSWORD AUTH_DB_PASSWORD INVOICE_DB_PASSWORD FINANCING_DB_PASSWORD NOTIFICATION_DB_PASSWORD \
                AUDIT_DB_MIGRATOR_PASSWORD AUDIT_DB_PASSWORD RABBITMQ_PASSWORD REDIS_PASSWORD ADMIN_BOOTSTRAP_PASSWORD; do
        set_env "$name" "$(secret)"
    done
    set_env RABBITMQ_USER ifp
    set_env ADMIN_BOOTSTRAP_EMAIL admin@ifp-demo.test
    set_env ADMIN_BOOTSTRAP_DISPLAY_NAME "Demo Admin"
fi

APP="https://app.$DOMAIN"
API="https://api.$DOMAIN"
AUTH="https://auth.$DOMAIN"
set_env COMPOSE_PROJECT_NAME "$PROJECT"
set_env IFP_IMAGE_REGISTRY "ghcr.io/$OWNER/ifp"
set_env IFP_IMAGE_TAG "$IMAGE_TAG"
set_env APP_HOST "app.$DOMAIN"
set_env API_HOST "api.$DOMAIN"
set_env AUTH_HOST "auth.$DOMAIN"
set_env AUTH_ISSUER_URI "$AUTH"
set_env IFP_PUBLIC_API_URL "$API"
set_env AUTH_SPA_REDIRECT_URIS "$APP/auth/callback"
set_env AUTH_SPA_POST_LOGOUT_REDIRECT_URIS "$APP/"
set_env AUTH_SPA_ALLOWED_ORIGINS "$APP"
set_env GATEWAY_CORS_ALLOWED_ORIGINS "$APP"
# Caddy appends the visitor's address as the last X-Forwarded-For entry.
set_env GATEWAY_TRUSTED_PROXY_HOPS 1

compose() {
    sudo docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f docker-compose.yml -f deploy/compose.public.yml \
        -f deploy/oracle/compose.caddy.yml --profile app "$@"
}

log "Pulling ghcr.io/$OWNER/ifp/*:$IMAGE_TAG and starting the stack (the first start takes several minutes)"
compose pull --quiet
compose up -d --no-build --remove-orphans --wait --wait-timeout 900

log "Waiting for HTTPS certificates and the public URLs"
for url in "$APP/config.json" "$AUTH/.well-known/openid-configuration"; do
    for _ in $(seq 1 60); do
        if curl -fsS -o /dev/null "$url"; then break; fi
        sleep 5
    done
    curl -fsS -o /dev/null "$url" || { echo "$url does not answer; check: sudo docker compose -p $PROJECT logs caddy" >&2; exit 1; }
done

cat <<EOF

The platform is running.
  App:      $APP
  API:      $API
  Sign-in:  $AUTH
  Admin:    admin@ifp-demo.test; the password is ADMIN_BOOTSTRAP_PASSWORD in $ENV_FILE
  Emails:   ssh -L 8025:127.0.0.1:8025 <this server>, then open http://localhost:8025
Re-run this script to update to the newest images.
EOF
