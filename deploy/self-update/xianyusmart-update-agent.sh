#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="${XIANYUSMART_ROOT:-/opt/xianyusmart}"
RUNTIME="$PROJECT_ROOT/runtime"
REQUEST="$RUNTIME/update/request.json"
STATUS="$RUNTIME/update/status.json"
BASE_COMPOSE="$PROJECT_ROOT/source/deploy/server/compose-existing-mysql.yaml"
OVERRIDE_COMPOSE="$RUNTIME/compose-jar-update.yaml"
ENV_FILE="$RUNTIME/.env"
ACTIVE_JAR="$RUNTIME/app.jar"
BACKUP_JAR="$RUNTIME/app.jar.previous"
CONTAINER="xianyusmart-app-1"
RELEASE_API="${UPDATE_RELEASE_API:-https://api.github.com/repos/Evvvvvvvan/XianYuSmart/releases/latest}"
WORK_DIR="$(mktemp -d "$RUNTIME/update/work.XXXXXX")"
ROLLBACK_REQUIRED=false

status() {
    printf '{"status":"%s","message":"%s","updatedAt":"%s"}\n' \
        "$1" "$2" "$(date -u +%FT%TZ)" > "$STATUS"
}

compose() {
    docker compose --project-name xianyusmart --env-file "$ENV_FILE" \
        -f "$BASE_COMPOSE" -f "$OVERRIDE_COMPOSE" "$@"
}

wait_for_app() {
    for _ in $(seq 1 40); do
        if [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$CONTAINER" 2>/dev/null || true)" == "healthy" ]] \
            && curl -fsS "http://127.0.0.1:12400/actuator/health" | grep -q '"status":"UP"'; then
            return 0
        fi
        sleep 3
    done
    return 1
}

cleanup() {
    local exit_code=$?
    if [[ $exit_code -ne 0 && "$ROLLBACK_REQUIRED" == true && -f "$BACKUP_JAR" ]]; then
        install -m 0644 "$BACKUP_JAR" "$ACTIVE_JAR"
        compose up -d --no-build --no-deps --force-recreate app || true
        wait_for_app || true
    fi
    if [[ $exit_code -ne 0 ]]; then
        status "FAILED" "自动更新失败，当前可用版本已保留" || true
    fi
    rm -rf "$WORK_DIR"
    rm -f "$REQUEST"
    exit "$exit_code"
}
trap cleanup EXIT

[[ -f "$REQUEST" && -f "$BASE_COMPOSE" && -f "$OVERRIDE_COMPOSE" && -f "$ENV_FILE" ]] || exit 0
command -v curl >/dev/null
command -v python3 >/dev/null
command -v docker >/dev/null

exec 9>"$RUNTIME/deploy.lock"
flock -w 180 9
status "DOWNLOADING" "正在下载并校验最新版本"

curl -fsSL -H 'Accept: application/vnd.github+json' -H 'User-Agent: XianYuSmart-Updater' \
    "$RELEASE_API" -o "$WORK_DIR/release.json"
python3 - "$WORK_DIR/release.json" "$WORK_DIR/asset-urls" <<'PY'
import json, sys
release = json.load(open(sys.argv[1], encoding="utf-8"))
assets = release.get("assets") or []
jars = [asset for asset in assets if asset.get("name", "").endswith(".jar")]
checksums = [asset for asset in assets if asset.get("name") == "SHA256SUMS.txt"]
if len(jars) != 1 or len(checksums) != 1:
    raise SystemExit("正式版本缺少唯一 JAR 或 SHA256SUMS.txt")
with open(sys.argv[2], "w", encoding="utf-8") as output:
    output.write(jars[0]["browser_download_url"] + "\n")
    output.write(jars[0]["name"] + "\n")
    output.write(checksums[0]["browser_download_url"] + "\n")
PY

mapfile -t ASSET < "$WORK_DIR/asset-urls"
curl -fsSL "${ASSET[0]}" -o "$WORK_DIR/app.jar"
curl -fsSL "${ASSET[2]}" -o "$WORK_DIR/SHA256SUMS.txt"
EXPECTED_SHA="$(awk -v name="${ASSET[1]}" '$2 == name || $2 == "*" name {print $1}' "$WORK_DIR/SHA256SUMS.txt")"
ACTUAL_SHA="$(sha256sum "$WORK_DIR/app.jar" | awk '{print $1}')"
[[ "$EXPECTED_SHA" =~ ^[0-9a-fA-F]{64}$ && "${EXPECTED_SHA,,}" == "$ACTUAL_SHA" ]]

install -m 0644 "$ACTIVE_JAR" "$BACKUP_JAR"
install -m 0644 "$WORK_DIR/app.jar" "$ACTIVE_JAR"
ROLLBACK_REQUIRED=true
status "RESTARTING" "版本已校验，正在重启应用"
compose up -d --no-build --no-deps --force-recreate app
wait_for_app
ROLLBACK_REQUIRED=false
status "SUCCESS" "自动更新完成"
