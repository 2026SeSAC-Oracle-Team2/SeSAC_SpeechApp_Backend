#!/bin/bash
# SeSAC 백엔드 기동 스크립트 (부트캠프 VM / 개인VM 공용)
# 사용법:
#   ./run_backend.sh          → 백그라운드 기동 (기존 프로세스 있으면 종료 후)
#   ./run_backend.sh stop     → 중지
#   ./run_backend.sh status   → 상태 확인
#   ./run_backend.sh log      → 로그 tail
#
# .env 의 SPRING_PROFILES_ACTIVE=dev 가 자동 주입됨 (H2 방지)

set -a
source "$(dirname "$0")/.env"
set +a

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="/tmp/boot_dev.log"

get_pid() {
    pgrep -f "SpeechappApplicationKt" | head -1
}

case "$1" in
    stop)
        PID=$(get_pid)
        if [ -n "$PID" ]; then
            kill "$PID" && echo "중지됨 (PID $PID)"
        else
            echo "실행 중인 프로세스 없음"
        fi
        ;;
    status)
        PID=$(get_pid)
        if [ -n "$PID" ]; then
            PROFILE=$(sudo cat /proc/$PID/environ 2>/dev/null | tr '\0' '\n' | grep SPRING_PROFILES_ACTIVE || echo "(env에서 못 읽음, 로그 확인 요망)")
            echo "실행 중: PID $PID / $PROFILE"
            ss -tlnp 2>/dev/null | grep -q 8080 && echo "8080 포트 응답 중" || echo "8080 미응답"
        else
            echo "실행 중 아님"
        fi
        ;;
    *)
        # 기존 프로세스 있으면 종료
        OLD=$(get_pid)
        [ -n "$OLD" ] && kill "$OLD" && sleep 3
        cd "$APP_DIR"
        nohup ./gradlew bootRun > "$LOG_FILE" 2>&1 &
        echo "기동 중... 로그: $LOG_FILE"
        echo "확인: ./run_backend.sh status"
        ;;
esac