#!/bin/bash
# SeSAC 백엔드 + nginx 기동 wrapper (P3-23)
# 사용법: ./run_backend.sh start|stop|status|log
# 위치: VM ~/app/run_backend.sh

set -e

APP_DIR="${HOME}/app"
DEPLOY_DIR="${HOME}/containers/SeSAC_SpeechApp_Deployment"
LOG_FILE="/tmp/backend.log"
NGINX_NAME="sesac-nginx"

case "${1:-start}" in
  start)
    echo "=== SeSAC Backend + Nginx 기동 ==="
    
    # 1. Oracle DB 확인 (외부 컨테이너)
    if docker ps | grep -q sesac-oracle-db; then
      echo "✅ Oracle DB (sesac-oracle-db) 확인"
    else
      echo "⚠️  Oracle DB가 실행 중이 아닙니다. ~/containers/SeSAC_SpeechApp_Container_DB 에서 docker compose up -d"
      exit 1
    fi
    
    # 2. Backend 기동 (.env 주입)
    echo "🚀 Backend 기동 중..."
    cd "$APP_DIR"
    set -a
    source .env
    set +a
    nohup ./gradlew bootRun --args='--spring.profiles.active=dev' --no-daemon > "$LOG_FILE" 2>&1 &
    echo "   Backend PID: $!"
    
    # 3. Nginx 기동 (이미 떠있으면 재시작)
    echo "🌐 Nginx 기동 중..."
    docker rm -f "$NGINX_NAME" 2>/dev/null || true
    docker run -d \
      --name "$NGINX_NAME" \
      -p 80:80 \
      --network host \
      -v "$DEPLOY_DIR/nginx.conf:/etc/nginx/nginx.conf:ro" \
      nginx:alpine
    echo "   Nginx 컨테이너: $(docker ps -q -f name=$NGINX_NAME)"
    
    echo ""
    echo "✅ 기동 완료!"
    echo "   API: http://localhost:80/api/v1/..."
    echo "   로그: tail -f $LOG_FILE"
    ;;
    
  stop)
    echo "🛑 중지 중..."
    # gradlew 프로세스 종료 (Java 프로세스)
    pgrep -f 'gradlew bootRun' | xargs -r kill -TERM 2>/dev/null || true
    sleep 2
    # nginx 컨테이너 종료
    docker rm -f "$NGINX_NAME" 2>/dev/null || true
    echo "✅ 중지 완료"
    ;;
    
  status)
    echo "=== 상태 확인 ==="
    echo "--- Backend ---"
    pgrep -a -f 'gradlew bootRun' || echo "   Backend: 미기동"
    echo "--- Nginx ---"
    docker ps -f name=$NGINX_NAME --format "   {{.Names}} ({{.Status}})" || echo "   Nginx: 미기동"
    echo "--- Oracle DB ---"
    docker ps -f name=sesac-oracle-db --format "   {{.Names}} ({{.Status}})" || echo "   Oracle: 미기동"
    ;;
    
  log)
    tail -f "$LOG_FILE"
    ;;
    
  *)
    echo "Usage: $0 {start|stop|status|log}"
    exit 1
    ;;
esac
