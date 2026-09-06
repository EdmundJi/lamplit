#!/bin/sh
# 开发服务器的后端入口：一边跑 spring-boot:run（带 devtools + 远程调试），一边轮询源码目录，
# 有改动就重新 compile。devtools 监视的是 target/classes，所以「编译」这一步必须有人做——
# 容器里没有 IDE，只能自己轮询。
#
# 为什么是轮询而不是 inotify：源码是从 macOS 通过 bind mount 挂进来的，
# inotify 事件不会跨过虚拟机边界传进容器，watch 类工具在这里一律失灵。
# 同理，Spring devtools 自己的 FileSystemWatcher 也是轮询实现，所以那一侧是好的。
set -eu

cd /workspace/backend

MARKER=/tmp/.last-compile
STAMP=/tmp/.scan-stamp

# 必须把 runtime 作用域的依赖也拉全：mvn compile 只解析 compile 作用域，
# 而 spring-boot:run 跑的是 runtime classpath（devtools、hibernate 那些都在里面）。
# 少了这一步，下面的 spring-boot:run 一启动就报一堆 "has not been downloaded from it before"。
echo "[dev] 首次拉取依赖（第一次可能要几分钟）..."
# 不要加 -q：首次要下几百兆依赖，静默输出会让人以为卡死了。
mvn -B dependency:resolve
mvn -B compile
touch "$MARKER"

watch_loop() {
    while true; do
        sleep "${WATCH_INTERVAL:-2}"
        touch "$STAMP"
        # 把扫描起点记在 STAMP 上而不是编译完再 touch：编译期间的改动会留到下一轮，
        # 宁可多编译一次，也不要漏掉一次。
        if find src/main/java src/main/resources -type f -newer "$MARKER" 2>/dev/null | grep -q .; then
            echo "[dev] 源码有改动，重新编译..."
            if mvn -B -o -q compile 2>&1 | tail -40; then
                echo "[dev] 编译完成，devtools 会自动重启应用"
            else
                echo "[dev] 编译失败——应用继续跑在上一份能用的字节码上"
            fi
            mv "$STAMP" "$MARKER"
        fi
    done
}

watch_loop &

exec mvn -B spring-boot:run \
    -Dspring-boot.run.profiles=local \
    -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
