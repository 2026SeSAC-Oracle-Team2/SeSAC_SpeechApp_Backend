#!/bin/bash
# SeSAC 백엔드 기동 wrapper (P3-23 Docker 기동)
# 사용법: ./run_backend.sh start|stop|status|log
# .env 자동 주입, SPRING_PROFILES_ACTIVE=dev

set -a
source "$(dirname "$0")/.env"
set +a

COMPOSE_FILE="${HOME}/containers/SeSAC_SpeechApp_Deployment/docker-compose.yml"

case "${1:-start}" in
  start)
    echo "Starting backend via Docker Compose..."
    docker compose -f "$COMPOSE_FILE" up -d backend
    ;;
  stop)
    echo "Stopping backend..."
    docker compose -f "$COMPOSE_FILE" stop backend
    ;;
  status)
    docker compose -f "$COMPOSE_FILE" ps backend
    ;;
  log)
    docker compose -f "$COMPOSE_FILE" logs backend --tail 100
    ;;
  *)
    echo "Usage: $0 {start|stop|status|log}"
    exit 1
    ;;
esac
