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
INSTALL_MARKER="$RUNTIME/update/installing.task"
CONTAINER="xianyusmart-app-1"
RELEASE_API="${UPDATE_RELEASE_API:-https://api.github.com/repos/Evvvvvvvan/XianYuSmart/releases/latest}"
WORK_DIR="$(mktemp -d "$RUNTIME/update/work.XXXXXX")"
ROLLBACK_REQUIRED=false
DOWNLOAD_ATTEMPTS=5
TASK_ID=""
TARGET_VERSION=""
REQUESTED_AT=""
REQUEST_UID=""
REQUEST_GID=""
CURRENT_PROGRESS=0
DOWNLOADED_BYTES=0
TOTAL_BYTES=0
FAILURE_MESSAGE="自动更新失败，当前可用版本已保留"

request_task_id() {
    [[ -f "$REQUEST" ]] || return 1
    python3 - "$REQUEST" <<'PY'
import json, sys
print(json.load(open(sys.argv[1], encoding="utf-8")).get("taskId", ""))
PY
}

task_is_current() {
    [[ -n "$TASK_ID" ]] || return 0
    [[ "$(request_task_id 2>/dev/null || true)" == "$TASK_ID" ]]
}

remove_current_request() {
    if [[ -z "$TASK_ID" ]] || task_is_current; then
        rm -f "$REQUEST"
    fi
}

status() {
    local state="$1"
    local progress="${2:-$CURRENT_PROGRESS}"
    local message="${3:-自动更新处理中}"
    local downloaded="${4:-$DOWNLOADED_BYTES}"
    local total="${5:-$TOTAL_BYTES}"
    task_is_current || return 0
    CURRENT_PROGRESS="$progress"
    DOWNLOADED_BYTES="$downloaded"
    TOTAL_BYTES="$total"
    printf '{"taskId":"%s","version":"%s","status":"%s","progress":%s,"message":"%s","downloadedBytes":%s,"totalBytes":%s,"requestedAt":"%s","updatedAt":"%s"}\n' \
        "$TASK_ID" "$TARGET_VERSION" "$state" "$progress" "$message" "$downloaded" "$total" \
        "$REQUESTED_AT" "$(date -u +%FT%TZ)" > "$STATUS.tmp"
    # 状态文件保持为应用用户所有，保证粘滞目录中的后续更新请求可以原子替换。
    chown "$REQUEST_UID:$REQUEST_GID" "$STATUS.tmp"
    mv -f "$STATUS.tmp" "$STATUS"
}

compose() {
    docker compose --project-name xianyusmart --env-file "$ENV_FILE" \
        -f "$BASE_COMPOSE" -f "$OVERRIDE_COMPOSE" "$@"
}

download_jar() {
    local url="$1"
    local target="$2"
    local total="$3"
    local attempt pid downloaded progress

    for attempt in $(seq 1 "$DOWNLOAD_ATTEMPTS"); do
        downloaded="$(stat -c %s "$target" 2>/dev/null || echo 0)"
        if [[ "$downloaded" -gt "$total" ]]; then
            rm -f "$target"
            downloaded=0
        elif [[ "$downloaded" -eq "$total" ]]; then
            status "DOWNLOADING" 70 "更新文件下载完成" "$downloaded" "$total"
            return 0
        fi
        status "DOWNLOADING" "$CURRENT_PROGRESS" "正在下载更新文件（第 $attempt/$DOWNLOAD_ATTEMPTS 次）" \
            "$downloaded" "$total"
        curl -fsSL --connect-timeout 30 --max-time 1800 --continue-at - "$url" -o "$target" &
        pid=$!
        while kill -0 "$pid" 2>/dev/null; do
            downloaded="$(stat -c %s "$target" 2>/dev/null || echo 0)"
            progress=$((10 + downloaded * 60 / total))
            (( progress > 70 )) && progress=70
            status "DOWNLOADING" "$progress" "正在下载更新文件（第 $attempt/$DOWNLOAD_ATTEMPTS 次）" \
                "$downloaded" "$total"
            sleep 2
        done
        wait "$pid" || true
        downloaded="$(stat -c %s "$target" 2>/dev/null || echo 0)"
        if [[ "$downloaded" -eq "$total" ]]; then
            status "DOWNLOADING" 70 "更新文件下载完成" "$downloaded" "$total"
            return 0
        fi
        sleep $((attempt * 3))
    done

    FAILURE_MESSAGE="更新文件下载失败，已重试 $DOWNLOAD_ATTEMPTS 次，可稍后重新尝试"
    return 1
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
    if [[ $exit_code -ne 0 && "$ROLLBACK_REQUIRED" == true ]]; then
        if [[ -f "$BACKUP_JAR" ]] \
            && install -m 0644 "$BACKUP_JAR" "$ACTIVE_JAR" \
            && compose up -d --no-build --no-deps --force-recreate app \
            && wait_for_app; then
            FAILURE_MESSAGE="$FAILURE_MESSAGE，已恢复更新前版本"
            rm -f "$INSTALL_MARKER"
        else
            FAILURE_MESSAGE="$FAILURE_MESSAGE；自动回滚失败，请立即检查服务"
        fi
    fi
    if [[ $exit_code -ne 0 ]]; then
        status "FAILED" "$CURRENT_PROGRESS" "$FAILURE_MESSAGE" "$DOWNLOADED_BYTES" "$TOTAL_BYTES" || true
    fi
    rm -rf "$WORK_DIR"
    remove_current_request
    exit "$exit_code"
}
trap cleanup EXIT

[[ -f "$REQUEST" && -f "$BASE_COMPOSE" && -f "$OVERRIDE_COMPOSE" && -f "$ENV_FILE" ]] || exit 0
command -v curl >/dev/null
command -v python3 >/dev/null
command -v docker >/dev/null
command -v flock >/dev/null

mapfile -t REQUEST_META < <(python3 - "$REQUEST" <<'PY'
import json, sys
request = json.load(open(sys.argv[1], encoding="utf-8"))
print(request.get("taskId", ""))
print(request.get("version", ""))
print(request.get("requestedAt", ""))
PY
)
TASK_ID="${REQUEST_META[0]}"
TARGET_VERSION="${REQUEST_META[1]}"
REQUESTED_AT="${REQUEST_META[2]}"
[[ -n "$TASK_ID" && -n "$TARGET_VERSION" && -n "$REQUESTED_AT" ]]
REQUEST_UID="$(stat -c %u "$REQUEST")"
REQUEST_GID="$(stat -c %g "$REQUEST")"
[[ "$REQUEST_UID" =~ ^[0-9]+$ && "$REQUEST_GID" =~ ^[0-9]+$ ]]

exec 9>"$RUNTIME/deploy.lock"
flock -w 180 9

# 上次进程若在替换 JAR 后被强制中断，先恢复稳定版本再重新执行更新。
if [[ -f "$INSTALL_MARKER" ]]; then
    FAILURE_MESSAGE="检测到上次更新被中断，恢复更新前版本失败"
    [[ -f "$BACKUP_JAR" ]]
    install -m 0644 "$BACKUP_JAR" "$ACTIVE_JAR"
    compose up -d --no-build --no-deps --force-recreate app
    wait_for_app
    rm -f "$INSTALL_MARKER"
fi

status "CHECKING" 3 "正在读取 GitHub 正式版本信息" 0 0

FAILURE_MESSAGE="无法读取 GitHub 正式版本信息"
curl -fsSL --retry 3 --retry-delay 2 --retry-all-errors --connect-timeout 15 --max-time 60 \
    -H 'Accept: application/vnd.github+json' -H 'User-Agent: XianYuSmart-Updater' \
    "$RELEASE_API" -o "$WORK_DIR/release.json"
python3 - "$WORK_DIR/release.json" "$WORK_DIR/asset-urls" "$TARGET_VERSION" <<'PY'
import json, sys
release = json.load(open(sys.argv[1], encoding="utf-8"))
release_version = release.get("tag_name", "").lstrip("vV")
if release_version != sys.argv[3].lstrip("vV"):
    raise SystemExit("GitHub 最新版本与请求版本不一致")
assets = release.get("assets") or []
jars = [asset for asset in assets if asset.get("name", "").endswith(".jar")]
checksums = [asset for asset in assets if asset.get("name") == "SHA256SUMS.txt"]
if len(jars) != 1 or len(checksums) != 1:
    raise SystemExit("正式版本缺少唯一 JAR 或 SHA256SUMS.txt")
jar_size = jars[0].get("size")
if not isinstance(jar_size, int) or jar_size <= 0:
    raise SystemExit("正式版本 JAR 大小无效")
with open(sys.argv[2], "w", encoding="utf-8") as output:
    output.write(jars[0]["browser_download_url"] + "\n")
    output.write(jars[0]["name"] + "\n")
    output.write(str(jar_size) + "\n")
    output.write(checksums[0]["browser_download_url"] + "\n")
PY

mapfile -t ASSET < "$WORK_DIR/asset-urls"
TOTAL_BYTES="${ASSET[2]}"
FAILURE_MESSAGE="更新文件下载失败，当前可用版本已保留"
download_jar "${ASSET[0]}" "$WORK_DIR/app.jar" "$TOTAL_BYTES"
FAILURE_MESSAGE="校验文件下载失败，可稍后重新尝试"
curl -fsSL --retry 3 --retry-delay 2 --retry-all-errors --connect-timeout 15 --max-time 60 \
    "${ASSET[3]}" -o "$WORK_DIR/SHA256SUMS.txt"
status "VERIFYING" 75 "正在校验更新文件完整性" "$TOTAL_BYTES" "$TOTAL_BYTES"
EXPECTED_SHA="$(awk -v name="${ASSET[1]}" '$2 == name || $2 == "*" name {print $1}' "$WORK_DIR/SHA256SUMS.txt")"
ACTUAL_SHA="$(sha256sum "$WORK_DIR/app.jar" | awk '{print $1}')"
FAILURE_MESSAGE="更新文件校验失败，当前可用版本已保留"
[[ "$EXPECTED_SHA" =~ ^[0-9a-fA-F]{64}$ && "${EXPECTED_SHA,,}" == "$ACTUAL_SHA" ]]

FAILURE_MESSAGE="安装或重启新版本失败"
status "INSTALLING" 82 "更新文件校验通过，正在备份当前版本" "$TOTAL_BYTES" "$TOTAL_BYTES"
install -m 0644 "$ACTIVE_JAR" "$BACKUP_JAR"
printf '%s\n' "$TASK_ID" > "$INSTALL_MARKER.tmp"
mv -f "$INSTALL_MARKER.tmp" "$INSTALL_MARKER"
ROLLBACK_REQUIRED=true
install -m 0644 "$WORK_DIR/app.jar" "$ACTIVE_JAR"
status "RESTARTING" 88 "正在重启应用服务" "$TOTAL_BYTES" "$TOTAL_BYTES"
compose up -d --no-build --no-deps --force-recreate app
FAILURE_MESSAGE="新版本健康检查失败"
status "HEALTH_CHECKING" 92 "服务已重启，正在执行健康检查" "$TOTAL_BYTES" "$TOTAL_BYTES"
wait_for_app
rm -f "$INSTALL_MARKER"
ROLLBACK_REQUIRED=false
status "SUCCESS" 100 "自动更新完成" "$TOTAL_BYTES" "$TOTAL_BYTES"
