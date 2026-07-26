#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${P2A_ENV_FILE:-$PROJECT_DIR/.env}"
LIMA_SOCKET="${HOME}/.lima/default/sock/docker.sock"

info() {
  printf '[p2a-local] %s\n' "$*"
}

die() {
  printf '[p2a-local] error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage: ./scripts/local-up.sh

Starts PostgreSQL, the Memory Server, and the Dashboard BFF with Compose.
The default environment file is .env. Override it with P2A_ENV_FILE=/path/to/file.
EOF
}

compose() {
  (
    cd "$PROJECT_DIR"
    docker-compose --env-file "$ENV_FILE" "$@"
  )
}

env_value() {
  local key="$1"
  local value

  value="$(awk -v key="$key" '
    $0 ~ ("^(export[[:space:]]+)?" key "=") {
      line = $0
      sub("^export[[:space:]]+", "", line)
      sub("^[^=]*=", "", line)
      value = line
    }
    END { print value }
  ' "$ENV_FILE")"

  case "$value" in
    \"*\") value="${value#\"}"; value="${value%\"}" ;;
    \'*\') value="${value#\'}"; value="${value%\'}" ;;
  esac

  printf '%s' "$value"
}

require_env_value() {
  local key="$1"
  local value

  value="$(env_value "$key")"
  [ -n "$value" ] || die "$key must be set in $ENV_FILE"
  case "$value" in
    *replace-with-*|/absolute/path/*)
      die "$key still has the example placeholder in $ENV_FILE"
      ;;
  esac
}

port_value() {
  local key="$1"
  local default_value="$2"
  local value

  value="$(env_value "$key")"
  value="${value:-$default_value}"
  case "$value" in
    ''|*[!0-9]*) die "$key must be a numeric port in $ENV_FILE" ;;
  esac
  [ "$value" -ge 1 ] && [ "$value" -le 65535 ] || die "$key must be between 1 and 65535"
  printf '%s' "$value"
}

ensure_docker() {
  command -v docker >/dev/null 2>&1 || die 'docker is not installed'
  command -v docker-compose >/dev/null 2>&1 || die 'docker-compose is not installed; this project uses Docker Compose v2'

  if docker info --format '{{.ServerVersion}}' >/dev/null 2>&1; then
    return
  fi

  command -v limactl >/dev/null 2>&1 || die 'Docker daemon is unavailable and limactl is not installed'
  info 'Docker daemon is unavailable; starting Lima default instance...'
  limactl start default >/dev/null

  [ -S "$LIMA_SOCKET" ] || die "Lima Docker socket was not created at $LIMA_SOCKET"
  export DOCKER_HOST="unix://$LIMA_SOCKET"
  docker info --format '{{.ServerVersion}}' >/dev/null 2>&1 || die 'Docker daemon is still unavailable after Lima startup'
}

wait_for_http() {
  local name="$1"
  local url="$2"
  local attempt=1

  command -v curl >/dev/null 2>&1 || die 'curl is required for service health checks'
  while [ "$attempt" -le 15 ]; do
    if curl --fail --silent --show-error "$url" >/dev/null 2>&1; then
      info "$name is healthy: $url"
      return
    fi
    sleep 2
    attempt=$((attempt + 1))
  done
  die "$name did not become healthy: $url"
}

case "${1:-}" in
  -h|--help)
    usage
    exit 0
    ;;
  '')
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

[ -f "$ENV_FILE" ] || die "environment file not found: $ENV_FILE (copy .env.example to .env first)"
require_env_value P2A_DB_PASSWORD
require_env_value P2A_LOCAL_TOKEN
require_env_value P2A_MODEL_DIR

MODEL_DIR="$(env_value P2A_MODEL_DIR)"
[ -d "$MODEL_DIR" ] || die "P2A_MODEL_DIR does not exist: $MODEL_DIR"
[ -f "$MODEL_DIR/model.onnx" ] || die "model.onnx is missing from P2A_MODEL_DIR"
[ -f "$MODEL_DIR/tokenizer.json" ] || die "tokenizer.json is missing from P2A_MODEL_DIR"

BACKEND_PORT="$(port_value P2A_BACKEND_HOST_PORT 8080)"
DASHBOARD_PORT="$(port_value P2A_DASHBOARD_HOST_PORT 4173)"

ensure_docker
info 'Checking Compose configuration...'
compose config --quiet

info 'Starting PostgreSQL, Memory Server, and Dashboard...'
compose up --build --detach --wait
wait_for_http 'Memory Server' "http://127.0.0.1:${BACKEND_PORT}/actuator/health"
wait_for_http 'Dashboard' "http://127.0.0.1:${DASHBOARD_PORT}/healthz"

compose ps
info "Dashboard: http://127.0.0.1:${DASHBOARD_PORT}/browse"
info 'The browser never needs P2A_LOCAL_TOKEN; the Dashboard BFF injects it server-side.'
