#!/usr/bin/env bash
set -euo pipefail

# Resolve installation root regardless of caller's working directory
CONSOLE_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_JAR="${CONSOLE_HOME}/seatunnel-console.jar"
ENV_FILE="${CONSOLE_HOME}/config/console.env"
LOG_DIR="${CONSOLE_HOME}/logs"
DATA_DIR="${CONSOLE_HOME}/data"
PID_FILE="${LOG_DIR}/console.pid"
OUT_FILE="${LOG_DIR}/console.out"

mkdir -p "${LOG_DIR}" "${DATA_DIR}"

# Load environment variables if config/console.env exists
if [[ -f "${ENV_FILE}" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "${ENV_FILE}"
    set +a
fi

# Default runtime paths & JVM options (can be overridden in config/console.env)
export CONSOLE_DATA_DIR="${CONSOLE_DATA_DIR:-${DATA_DIR}}"
export CONSOLE_XXL_JOB_LOG_PATH="${CONSOLE_XXL_JOB_LOG_PATH:-${LOG_DIR}/xxl-job}"
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx1g -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai}"

find_java() {
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
        echo "${JAVA_HOME}/bin/java"
    elif command -v java >/dev/null 2>&1; then
        command -v java
    else
        echo "ERROR: Java 17+ not found. Set JAVA_HOME or install java on PATH." >&2
        exit 1
    fi
}

is_running() {
    if [[ -f "${PID_FILE}" ]]; then
        local pid
        pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
        if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
            return 0
        fi
    fi
    return 1
}

do_start() {
    if is_running; then
        echo "seatunnel-console is already running (PID $(cat "${PID_FILE}"))."
        return 0
    fi
    if [[ ! -f "${APP_JAR}" ]]; then
        echo "ERROR: ${APP_JAR} not found." >&2
        exit 1
    fi
    local java_bin
    java_bin="$(find_java)"

    cd "${CONSOLE_HOME}"
    # shellcheck disable=SC2086
    nohup "${java_bin}" ${JAVA_OPTS} -jar "${APP_JAR}" \
        --spring.config.additional-location="optional:file:${CONSOLE_HOME}/config/" \
        >> "${OUT_FILE}" 2>&1 &
    local pid=$!
    echo "${pid}" > "${PID_FILE}"

    # Wait briefly to confirm it didn't crash immediately on startup
    sleep 2
    if kill -0 "${pid}" 2>/dev/null; then
        echo "seatunnel-console started (PID ${pid}). Log: ${OUT_FILE}"
    else
        rm -f "${PID_FILE}"
        echo "ERROR: seatunnel-console failed to start. Check ${OUT_FILE}:" >&2
        tail -n 30 "${OUT_FILE}" >&2 || true
        exit 1
    fi
}

do_run() {
    if [[ ! -f "${APP_JAR}" ]]; then
        echo "ERROR: ${APP_JAR} not found." >&2
        exit 1
    fi
    local java_bin
    java_bin="$(find_java)"
    cd "${CONSOLE_HOME}"
    # shellcheck disable=SC2086
    exec "${java_bin}" ${JAVA_OPTS} -jar "${APP_JAR}" \
        --spring.config.additional-location="optional:file:${CONSOLE_HOME}/config/"
}

do_stop() {
    if ! is_running; then
        echo "seatunnel-console is not running."
        rm -f "${PID_FILE}"
        return 0
    fi
    local pid
    pid="$(cat "${PID_FILE}")"
    echo -n "Stopping seatunnel-console (PID ${pid})"
    kill "${pid}" 2>/dev/null || true

    local waited=0
    while kill -0 "${pid}" 2>/dev/null && [[ ${waited} -lt 30 ]]; do
        echo -n "."
        sleep 1
        waited=$((waited + 1))
    done
    echo ""

    if kill -0 "${pid}" 2>/dev/null; then
        echo "Process did not exit in 30s; sending SIGKILL..."
        kill -9 "${pid}" 2>/dev/null || true
    fi
    rm -f "${PID_FILE}"
    echo "seatunnel-console stopped."
}

do_status() {
    if is_running; then
        echo "seatunnel-console is running (PID $(cat "${PID_FILE}"))."
        exit 0
    else
        echo "seatunnel-console is not running."
        exit 3
    fi
}

case "${1:-}" in
    start)
        do_start
        ;;
    stop)
        do_stop
        ;;
    restart)
        do_stop
        do_start
        ;;
    status)
        do_status
        ;;
    run)
        do_run
        ;;
    *)
        echo "Usage: $0 {start|stop|restart|status|run}" >&2
        exit 2
        ;;
esac
