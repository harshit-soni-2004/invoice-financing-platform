#!/bin/sh
# Renders /config.json from IFP_API_BASE_URL, IFP_AUTH_ISSUER and IFP_CLIENT_ID so one image serves every environment.
# When none of them is set, the config.json baked into the image is served unchanged.
set -eu

target=/usr/share/nginx/html/config.json

if [ -z "${IFP_API_BASE_URL:-}" ] && [ -z "${IFP_AUTH_ISSUER:-}" ] && [ -z "${IFP_CLIENT_ID:-}" ]; then
    exit 0
fi

fail() {
    echo "40-ifp-runtime-config: $1" >&2
    exit 1
}

# Values are written into JSON verbatim, so only plain http(s) URLs and simple client ids are accepted.
url_pattern='^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$'

api="${IFP_API_BASE_URL:?IFP_API_BASE_URL must be set together with IFP_AUTH_ISSUER}"
issuer="${IFP_AUTH_ISSUER:?IFP_AUTH_ISSUER must be set together with IFP_API_BASE_URL}"
client="${IFP_CLIENT_ID:-ifp-spa}"

echo "$api" | grep -Eq "$url_pattern" || fail "IFP_API_BASE_URL is not a plain http(s) URL"
echo "$issuer" | grep -Eq "$url_pattern" || fail "IFP_AUTH_ISSUER is not a plain http(s) URL"
echo "$client" | grep -Eq '^[A-Za-z0-9._-]+$' || fail "IFP_CLIENT_ID contains unsupported characters"

printf '{\n  "apiBaseUrl": "%s",\n  "authIssuer": "%s",\n  "clientId": "%s"\n}\n' "$api" "$issuer" "$client" > "$target"
echo "40-ifp-runtime-config: wrote $target"
