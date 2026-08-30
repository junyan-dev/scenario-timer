#!/usr/bin/env bash
# 启动 Android 模拟器
set -euo pipefail

AVD_NAME="${1:-Medium_Phone_API_36.0}"

# 定位 emulator
EMULATOR_CMD=""
for candidate in \
    "${ANDROID_HOME:-}/emulator/emulator" \
    "${ANDROID_SDK_ROOT:-}/emulator/emulator" \
    "$HOME/Library/Android/sdk/emulator/emulator"; do
    if [[ -x "$candidate" ]]; then
        EMULATOR_CMD="$candidate"
        break
    fi
done

if [[ -z "$EMULATOR_CMD" ]]; then
    echo "未找到 emulator 可执行文件，请确认 ANDROID_HOME 已设置" >&2
    exit 1
fi

# 定位 adb
ADB_CMD=""
for candidate in \
    "${ANDROID_HOME:-}/platform-tools/adb" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
    "$HOME/Library/Android/sdk/platform-tools/adb" \
    "$(command -v adb || true)"; do
    if [[ -x "$candidate" ]]; then
        ADB_CMD="$candidate"
        break
    fi
done

if [[ -z "$ADB_CMD" ]]; then
    echo "未找到 adb 可执行文件，请确认 ANDROID_HOME 已设置" >&2
    exit 1
fi

# 若已有同名设备在运行，直接返回
RUNNING=$("$ADB_CMD" devices | grep -E "emulator-[0-9]+\s+device" || true)
if [[ -n "$RUNNING" ]]; then
    echo "已有模拟器在运行："
    echo "$RUNNING"
    exit 0
fi

echo "启动 AVD: $AVD_NAME"
# 若指定的 AVD 不存在，回退到第一个可用的
if ! "$EMULATOR_CMD" -list-avds | grep -qx "$AVD_NAME"; then
    FALLBACK=$("$EMULATOR_CMD" -list-avds | head -1 || true)
    if [[ -z "$FALLBACK" ]]; then
        echo "没有可用的 AVD" >&2
        exit 1
    fi
    echo "AVD $AVD_NAME 不存在，改用 $FALLBACK"
    AVD_NAME="$FALLBACK"
fi
nohup "$EMULATOR_CMD" -avd "$AVD_NAME" -netdelay none -netspeed full >/tmp/emulator.log 2>&1 &

# 等待设备上线
echo "等待设备启动..."
"$ADB_CMD" wait-for-device
# 等待系统就绪
for _ in $(seq 1 60); do
    BOOTED=$("$ADB_CMD" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    if [[ "$BOOTED" == "1" ]]; then
        echo "模拟器已就绪"
        exit 0
    fi
    sleep 2
done

echo "等待启动超时，可查看 /tmp/emulator.log" >&2
exit 1
