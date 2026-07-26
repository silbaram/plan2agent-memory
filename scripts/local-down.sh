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
Usage: ./scripts/local-down.sh

Stops the PostgreSQL, Memory Server, and Dashboard containers while preserving
the named PostgreSQL volume. This script never uses `down --volumes`.
EOF
}

compose() {
  (
    cd "$PROJECT_DIR"
    docker-compose --env-file "$ENV_FILE" "$@"
  )
}

ensure_docker() {
  command -v docker >/dev/null 2>&1 || die 'docker is not installed'
  command -v docker-compose >/dev/null 2>&1 || die 'docker-compose is not installed; this project uses Docker Compose v2'

  if docker info --format '{{.ServerVersion}}' >/dev/null 2>&1; then
    return
  fi

  command -v limactl >/dev/null 2>&1 || die 'Docker daemon is unavailable and limactl is not installed'
  info 'Docker daemon is unavailable; starting Lima default instance to stop the Compose services...'
  limactl start default >/dev/null

  [ -S "$LIMA_SOCKET" ] || die "Lima Docker socket was not created at $LIMA_SOCKET"
  export DOCKER_HOST="unix://$LIMA_SOCKET"
  docker info --format '{{.ServerVersion}}' >/dev/null 2>&1 || die 'Docker daemon is still unavailable after Lima startup'
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

[ -f "$ENV_FILE" ] || die "environment file not found: $ENV_FILE"
ensure_docker

info 'Stopping PostgreSQL, Memory Server, and Dashboard without deleting volumes...'
compose down --remove-orphans
info 'Services stopped. PostgreSQL data remains in the named Docker volume.'
info 'Lima is left running so other local Docker workloads are unaffected.'
