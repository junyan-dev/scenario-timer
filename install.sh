#!/usr/bin/env bash
# 构建 APK 并安装到 Android 设备（模拟器或真机）
# 适配 macOS / Linux 主机
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="dev.junyan.scenariotimer"

# ---- 平台检测 ----
OS_TYPE="$(uname -s)"
case "$OS_TYPE" in
    Darwin) HOST_PLATFORM="macos" ;;
    Linux)  HOST_PLATFORM="linux" ;;
    *)      HOST_PLATFORM="unknown" ;;
esac
echo "主机平台: $HOST_PLATFORM"

# ---- 定位 SDK ----
detect_sdk() {
    for sdk_dir in \
        "${ANDROID_HOME:-}" \
        "${ANDROID_SDK_ROOT:-}" \
        "$HOME/Library/Android/sdk" \
        "$HOME/Android/Sdk" \
        "/usr/local/share/android-sdk"; do
        if [[ -n "$sdk_dir" && -d "$sdk_dir" ]]; then
            echo "$sdk_dir"
            return 0
        fi
    done
    return 1
}

SDK_ROOT="$(detect_sdk || true)"
if [[ -z "$SDK_ROOT" ]]; then
    echo "未找到 Android SDK，请设置 ANDROID_HOME" >&2
    exit 1
fi
export ANDROID_HOME="$SDK_ROOT"
export PATH="$SDK_ROOT/platform-tools:$SDK_ROOT/emulator:$PATH"
echo "SDK: $SDK_ROOT"

# ---- 定位 adb ----
ADB_CMD=""
for candidate in "$SDK_ROOT/platform-tools/adb" "adb"; do
    if [[ -x "$candidate" ]] || command -v "$candidate" >/dev/null 2>&1; then
        ADB_CMD="$candidate"
        break
    fi
done
[[ -z "$ADB_CMD" ]] && { echo "未找到 adb"; exit 1; }

# ---- 检测设备 ----
DEVICES=$("$ADB_CMD" devices | awk '/device$/ && !/List/ {print $1}')
if [[ -z "$DEVICES" ]]; then
    echo "未检测到 Android 设备" >&2
    echo "请先连接真机（开启 USB 调试）或启动模拟器：./start-emulator.sh" >&2
    exit 1
fi

DEVICE_COUNT=$(echo "$DEVICES" | wc -l | tr -d ' ')
if [[ "$DEVICE_COUNT" -eq 1 ]]; then
    DEVICE=$(echo "$DEVICES" | head -n1)
else
    echo "检测到多个设备："
    echo "$DEVICES" | nl -w2 -s'. '
    read -rp "选择设备序号 [1]: " IDX
    IDX="${IDX:-1}"
    DEVICE=$(echo "$DEVICES" | sed -n "${IDX}p")
fi
echo "目标设备: $DEVICE"

# ---- 构建（如需要）----
if [[ ! -f "$APK_PATH" ]]; then
    echo "构建 APK..."
    # 绕过全局 init.gradle 与项目 FAIL_ON_PROJECT_REPOS 的冲突
    if [[ -f "$HOME/.gradle/init.d/init.gradle" ]]; then
        GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/gradle-tmp}" ./gradlew assembleDebug
    else
        ./gradlew assembleDebug
    fi
fi

# ---- 安装 ----
echo "安装 APK..."
"$ADB_CMD" -s "$DEVICE" install -r "$APK_PATH"

# ---- 启动 ----
echo "启动应用..."
"$ADB_CMD" -s "$DEVICE" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo "完成 ✓"
