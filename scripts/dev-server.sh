#!/usr/bin/env bash
# 共享开发服务器的总控。前后端都跑在容器里，源码从这台机器挂进去，改完自动生效。
#
#   scripts/dev-server.sh up        启动（首次会下 Maven / pnpm 依赖，慢一次）
#   scripts/dev-server.sh urls      打印给其他开发者的访问地址
#   scripts/dev-server.sh logs      跟日志（可跟服务名：logs backend）
#   scripts/dev-server.sh status    看各服务与端口
#   scripts/dev-server.sh restart   重启某个服务（默认 backend）
#   scripts/dev-server.sh seed      通过公开 API 造一份演示数据
#   scripts/dev-server.sh town      立刻重跑今晚的小镇社会模拟（用于反复调试传播链）
#   scripts/dev-server.sh reset-db  清库重来（会二次确认）
#   scripts/dev-server.sh down      停掉（数据保留）
set -euo pipefail

cd "$(dirname "$0")/.."
ENV_FILE=".env.local"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f deploy/compose.dev.yaml)

if [[ ! -f "$ENV_FILE" ]]; then
    echo "缺少 $ENV_FILE。先 cp .env.example .env.local，把示例值换掉再来。" >&2
    exit 1
fi

# shellcheck disable=SC1090
set -a; . "./$ENV_FILE"; set +a

WEB_PORT="${DEV_WEB_PORT:-5173}"
API_PORT="${DEV_API_PORT:-8080}"
DEBUG_PORT="${DEV_DEBUG_PORT:-5005}"
LOGS_PORT="${DEV_LOGS_PORT:-9999}"
DB_PORT="${MYSQL_PORT:-3306}"

lan_ip() {
    # 给其他开发者的地址：localhost 在他们机器上指向他们自己。
    ipconfig getifaddr en0 2>/dev/null \
        || ipconfig getifaddr en1 2>/dev/null \
        || hostname -I 2>/dev/null | awk '{print $1}' \
        || echo "<本机局域网IP>"
}

port_taken_by_host() {
    # 只关心非 Docker 占用的端口：容器自己占着不算冲突。
    lsof -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | awk 'NR>1 && $1 != "com.docke" && $1 != "OrbStack" {print $1; exit}'
}

preflight() {
    local blocked=0
    for spec in "$WEB_PORT:前端(Vite)" "$API_PORT:后端" "$DEBUG_PORT:远程调试" "$LOGS_PORT:日志面板"; do
        local port="${spec%%:*}" what="${spec##*:}"
        local owner; owner="$(port_taken_by_host "$port")"
        if [[ -n "$owner" ]]; then
            echo "端口 $port（$what）被本机进程 $owner 占用。" >&2
            blocked=1
        fi
    done
    if (( blocked )); then
        echo >&2
        echo "多半是你本机还开着 pnpm dev / mvnw spring-boot:run。先停掉它们，或者在 $ENV_FILE 里" >&2
        echo "改 DEV_WEB_PORT / DEV_API_PORT / DEV_DEBUG_PORT / DEV_LOGS_PORT 换个端口。" >&2
        exit 1
    fi
}

urls() {
    local ip; ip="$(lan_ip)"
    cat <<EOF

  应用          http://${ip}:${WEB_PORT}          （本机也可用 http://localhost:${WEB_PORT}）
  接口          http://${ip}:${WEB_PORT}/api/v1   （Vite 同源反代到后端，cookie 直接可用）
  后端直连      http://${ip}:${API_PORT}/api/v1   （绕过前端，给 curl / Postman 用）
  健康检查      http://${ip}:${API_PORT}/actuator/health
  日志面板      http://${ip}:${LOGS_PORT}
  远程调试      ${ip}:${DEBUG_PORT}               （IDEA/VS Code 挂 JDWP，同一时刻只挂一个人）
  数据库        ${ip}:${DB_PORT}  库 ${MYSQL_DATABASE:-growth}  用户 ${MYSQL_USER:-growth_app}

  提醒：这些端口只在同一局域网内可达；口令都在 ${ENV_FILE} 里，不要把该文件发出去。

EOF
}

case "${1:-up}" in
    up)
        preflight
        "${COMPOSE[@]}" up -d
        echo
        echo "起来了。首次启动后端要下 Maven 依赖、前端要装 node_modules，几分钟内接口才会通。"
        echo "看进度： scripts/dev-server.sh logs"
        urls
        ;;
    down)
        "${COMPOSE[@]}" down
        echo "已停止。数据卷保留，下次 up 还在。"
        ;;
    restart)
        # up -d，不是 restart。`docker compose restart` 只重启容器里的进程，不会拿
        # compose 文件重新比对配置——所以给某个服务加了环境变量或挂载之后 restart
        # 一次，看起来重启成功了，实际跑的还是旧容器、旧配置。
        # 这不是假想：后端曾经因此起不来，容器里既没有 COMPANION_MEMORY_ROOT 也没有
        # 记忆卷，日志里报的是 sqlite 打不开——查了半天才发现文件里配置一直是对的，
        # 只是运行中的容器从来没吃到过。up -d 在配置没变时是空操作，变了才重建。
        "${COMPOSE[@]}" up -d "${2:-backend}"
        ;;
    logs)
        if [[ -n "${2:-}" ]]; then "${COMPOSE[@]}" logs -f --tail=200 "$2"; else "${COMPOSE[@]}" logs -f --tail=100; fi
        ;;
    status)
        "${COMPOSE[@]}" ps
        urls
        ;;
    urls)
        urls
        ;;
    seed)
        # 复用已有的冒烟脚本：它本来就是"通过公开 API 造一份隔离的种子数据"。
        API_BASE="http://127.0.0.1:$API_PORT/api/v1" scripts/seed-local.sh
        ;;
    town)
        # 小镇的夜间流水线按 (用户, 当地日期) 幂等，想在同一天重跑必须先把那把锁删掉。
        # 删掉之后等下一次 cron（开发环境默认每 5 分钟）即可。
        "${COMPOSE[@]}" exec -T mysql sh -c \
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "delete from town_society_run;" '"${MYSQL_DATABASE:-growth}"
        echo "今日的小镇模拟锁已清除，下一次 cron（默认每 5 分钟）会重算一遍传播链。"
        echo "想立刻看结果，可以直接查表：scripts/dev-server.sh db"
        ;;
    db)
        "${COMPOSE[@]}" exec mysql sh -c \
            'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" '"${MYSQL_DATABASE:-growth}"
        ;;
    reset-db)
        read -r -p "这会删掉 ${MYSQL_DATABASE:-growth} 库里的全部数据，确定？输入 yes 继续： " confirm
        [[ "$confirm" == "yes" ]] || { echo "已取消。"; exit 0; }
        "${COMPOSE[@]}" exec -T mysql sh -c \
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "drop database if exists '"${MYSQL_DATABASE:-growth}"'; create database '"${MYSQL_DATABASE:-growth}"' character set utf8mb4;"'
        "${COMPOSE[@]}" up -d backend
        echo "库已重建，后端重启后 Flyway 会把 V1~V22 重新跑一遍。"
        ;;
    *)
        sed -n '2,14p' "$0"
        exit 1
        ;;
esac
