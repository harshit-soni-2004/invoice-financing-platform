#!/usr/bin/env sh
# DEVELOPMENT ONLY. Creates infra/keys/jwt-signing-key.pem (git-ignored, mode 600) for auth-service.
# Never use this key outside local development and never commit it. Existing keys are never overwritten.
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
out_file=${1:-"$script_dir/../infra/keys/jwt-signing-key.pem"}

java "$script_dir/GenerateDevJwtKey.java" "$out_file"
echo "Use it with: AUTH_JWT_PRIVATE_KEY_LOCATION=file:$out_file"
