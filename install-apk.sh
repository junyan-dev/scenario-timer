#!/usr/bin/env bash
# 构建并安装 debug APK 到模拟器
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="dev.junyan.scenariotimer"

# 定位 adb
ADB_CMD=""
for candidate in \
    "${ANDROID_HOME:-}/platform-tools/adb" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
    "$HOME/Library/Android/sdk/platform-tools/adb" \
    "adb"; do
    if command -v "$candidate" >/dev/null 2>&1 || [[ -x "$candidate" ]]; then
        ADB_CMD="$candidate"
        break
    fi
done

if [[ -z "$ADB_CMD" ]]; then
    echo "未找到 adb" >&2
    exit 1
fi

# 选择目标设备
DEVICES=$("$ADB_CMD" devices | grep -E "emulator-[0-9]+\s+device" || true)
if [[ -z "$DEVICES" ]]; then
    echo "没有运行中的模拟器，先执行 ./start-emulator.sh" >&2
    exit 1
fi

DEVICE=$(echo "$DEVICES" | head -n1 | awk '{print $1}')
echo "目标设备: $DEVICE"

# 若 APK 不存在则构建
if [[ ! -f "$APK_PATH" ]]; then
    echo "APK 不存在，开始构建..."
    GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/gradle-tmp}" ./gradlew assembleDebug
fi

# 安装
echo "安装 APK..."
"$ADB_CMD" -s "$DEVICE" install -r "$APK_PATH"

# 启动应用
echo "启动应用..."
"$ADB_CMD" -s "$DEVICE" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo "完成"
