#!/usr/bin/env bash
# UI 自动化测试 - 基于 adb + python3
# 测试: 启动 → 切换场景 → 启动/取消计时器 → 滑动编辑 → 添加场景
set -euo pipefail

ADB="$HOME/Library/Android/sdk/platform-tools/adb"
PKG="dev.junyan.scenariotimer"
PASS=0
FAIL=0

green() { printf "\033[32m%s\033[0m\n" "$1"; }
red()   { printf "\033[31m%s\033[0m\n" "$1"; }
log()   { printf "\033[36m[TEST]\033[0m %s\n" "$1"; }

assert_eq() {
    local desc="$1" actual="$2" expected="$3"
    if [ "$actual" = "$expected" ]; then
        green "PASS: $desc"
        PASS=$((PASS + 1))
    else
        red "FAIL: $desc (expected '$expected', got '$actual')"
        FAIL=$((FAIL + 1))
    fi
}

assert_contains() {
    local desc="$1" actual="$2" expected="$3"
    if echo "$actual" | grep -q "$expected"; then
        green "PASS: $desc"
        PASS=$((PASS + 1))
    else
        red "FAIL: $desc (expected contains '$expected', got '$actual')"
        FAIL=$((FAIL + 1))
    fi
}

dump_ui() {
    "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    "$ADB" pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
}

get_text() {
    python3 -c "
import xml.etree.ElementTree as ET
tree = ET.parse('/tmp/ui.xml')
for node in tree.iter('node'):
    if node.get('resource-id') == '$1':
        print(node.get('text', ''))
        break
" 2>/dev/null
}

get_all_text() {
    python3 -c "
import xml.etree.ElementTree as ET
tree = ET.parse('/tmp/ui.xml')
for node in tree.iter('node'):
    if node.get('resource-id') == '$1':
        print(node.get('text', ''))
" 2>/dev/null
}

get_bounds() {
    python3 -c "
import xml.etree.ElementTree as ET
tree = ET.parse('/tmp/ui.xml')
for node in tree.iter('node'):
    if node.get('resource-id') == '$1':
        b = node.get('bounds', '')
        # [x1,y1][x2,y2]
        b = b.replace('[',' ').replace(']',' ')
        parts = b.split(',')
        x1, y1 = parts[0].strip(), parts[1].split()[0]
        x2, y2 = parts[1].split()[1], parts[2].strip()
        print(f'{x1} {y1} {x2} {y2}')
        break
" 2>/dev/null
}

tap_id() {
    local bounds=$(get_bounds "$1")
    if [ -z "$bounds" ]; then
        echo "  WARNING: element $1 not found"
        return 1
    fi
    local x1=$(echo $bounds | cut -d' ' -f1)
    local y1=$(echo $bounds | cut -d' ' -f2)
    local x2=$(echo $bounds | cut -d' ' -f3)
    local y2=$(echo $bounds | cut -d' ' -f4)
    local cx=$(( (x1 + x2) / 2 ))
    local cy=$(( (y1 + y2) / 2 ))
    "$ADB" shell input tap $cx $cy
    sleep "${2:-1}"
}

tap_scene_by_name() {
    # 点击指定名称的场景
    local name="$1"
    local coords=$(python3 -c "
import xml.etree.ElementTree as ET
tree = ET.parse('/tmp/ui.xml')
for node in tree.iter('node'):
    if node.get('resource-id') == 'dev.junyan.scenariotimer:id/tvItemSceneName' and node.get('text') == '$name':
        b = node.get('bounds','').replace('[',' ').replace(']',' ')
        parts = b.split(',')
        x1, y1 = parts[0].strip(), parts[1].split()[0]
        x2, y2 = parts[1].split()[1], parts[2].strip()
        print(f'{(int(x1)+int(x2))//2} {(int(y1)+int(y2))//2}')
        break
" 2>/dev/null)
    if [ -n "$coords" ]; then
        "$ADB" shell input tap $coords
        sleep 1
    else
        echo "  WARNING: scene '$name' not found"
    fi
}

screenshot() {
    "$ADB" shell screencap -p /sdcard/$1
    "$ADB" pull /sdcard/$1 /tmp/$1 >/dev/null 2>&1
}

# ── 开始测试 ──
log "停止应用和服务..."
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am stopservice "$PKG/.TimerService" 2>/dev/null || true
sleep 1

log "启动应用..."
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 2
dump_ui

# ── 测试 1: 默认场景 ──
log "测试 1: 验证默认显示"
TIMER_TEXT=$(get_text "dev.junyan.scenariotimer:id/tvTimerDisplay")
SCENE_NAME=$(get_text "dev.junyan.scenariotimer:id/tvSelectedScene")
assert_eq "选中场景为午休" "$SCENE_NAME" "午休"
assert_eq "计时器显示20分钟" "$TIMER_TEXT" "20:00"

# ── 测试 2: 切换到静音场景 ──
log "测试 2: 切换到静音场景"
tap_scene_by_name "静音"
sleep 1
dump_ui
SCENE_NAME=$(get_text "dev.junyan.scenariotimer:id/tvSelectedScene")
TIMER_TEXT=$(get_text "dev.junyan.scenariotimer:id/tvTimerDisplay")
assert_eq "切换到静音场景" "$SCENE_NAME" "静音"
assert_eq "计时器显示30分钟" "$TIMER_TEXT" "30:00"

# ── 测试 3: 启动计时器 ──
log "测试 3: 启动计时器"
tap_id "dev.junyan.scenariotimer:id/btnStartCancel"
sleep 3
dump_ui
TIMER_TEXT=$(get_text "dev.junyan.scenariotimer:id/tvTimerDisplay")
# 倒计时应该小于 30:00
if [ "$TIMER_TEXT" = "30:00" ]; then
    red "FAIL: 计时器未开始倒计时 (still 30:00)"
    FAIL=$((FAIL + 1))
else
    green "PASS: 计时器开始倒计时 ($TIMER_TEXT)"
    PASS=$((PASS + 1))
fi
screenshot test_running.png

# ── 测试 4: 取消计时器 ──
log "测试 4: 取消计时器"
tap_id "dev.junyan.scenariotimer:id/btnStartCancel"
sleep 1
dump_ui
TIMER_TEXT=$(get_text "dev.junyan.scenariotimer:id/tvTimerDisplay")
assert_eq "取消后恢复静音场景时间" "$TIMER_TEXT" "30:00"

# ── 测试 5: 切换到午休再启动 ──
log "测试 5: 切换到午休场景并启动"
tap_scene_by_name "午休"
sleep 1
dump_ui
SCENE_NAME=$(get_text "dev.junyan.scenariotimer:id/tvSelectedScene")
assert_eq "切换到午休场景" "$SCENE_NAME" "午休"

tap_id "dev.junyan.scenariotimer:id/btnStartCancel"
sleep 2
dump_ui
TIMER_TEXT=$(get_text "dev.junyan.scenariotimer:id/tvTimerDisplay")
if [ "$TIMER_TEXT" = "20:00" ]; then
    red "FAIL: 计时器未倒计时"
    FAIL=$((FAIL + 1))
else
    green "PASS: 午休计时器运行中 ($TIMER_TEXT)"
    PASS=$((PASS + 1))
fi

# 取消，为后续测试准备
tap_id "dev.junyan.scenariotimer:id/btnStartCancel"
sleep 1

# ── 测试 6: 左滑显示编辑/删除 ──
log "测试 6: 左滑冥想场景显示编辑/删除"
dump_ui
# 获取冥想场景坐标
COORDS=$(python3 -c "
import xml.etree.ElementTree as ET
tree = ET.parse('/tmp/ui.xml')
for node in tree.iter('node'):
    if node.get('resource-id') == 'dev.junyan.scenariotimer:id/tvItemSceneName' and node.get('text') == '冥想':
        b = node.get('bounds','').replace('[',' ').replace(']',' ')
        parts = b.split(',')
        y1 = parts[1].split()[0]
        y2 = parts[1].split()[1]
        print(f'{(int(y1)+int(y2))//2}')
        break
" 2>/dev/null)
if [ -n "$COORDS" ]; then
    "$ADB" shell input swipe 800 $COORDS 400 $COORDS 300
    sleep 1
    dump_ui
    EDIT_TEXT=$(get_text "dev.junyan.scenariotimer:id/btnSceneEdit")
    DELETE_TEXT=$(get_text "dev.junyan.scenariotimer:id/btnSceneDelete")
    assert_eq "编辑按钮可见" "$EDIT_TEXT" "编辑"
    assert_eq "删除按钮可见" "$DELETE_TEXT" "删除"
    screenshot test_swipe.png
    # 点击空白处关闭
    "$ADB" shell input tap 200 $COORDS
    sleep 1
else
    red "FAIL: 找不到冥想场景"
    FAIL=$((FAIL + 1))
fi

# ── 测试 7: 添加场景对话框 ──
log "测试 7: 打开添加场景对话框"
dump_ui
tap_id "dev.junyan.scenariotimer:id/btnAddScene"
sleep 1
dump_ui
DIALOG_TITLE=$(python3 -c "
import xml.etree.ElementTree as ET
tree = ET.parse('/tmp/ui.xml')
for node in tree.iter('node'):
    t = node.get('text', '')
    if '添加场景' in t or '编辑场景' in t:
        print(t)
        break
" 2>/dev/null)
assert_contains "添加场景对话框出现" "$DIALOG_TITLE" "添加场景"
screenshot test_add_dialog.png

# 关闭对话框
"$ADB" shell input keyevent 4
sleep 1

# ── 结果汇总 ──
echo ""
echo "══════════════════════════════════"
green "  通过: $PASS"
if [ "$FAIL" -gt 0 ]; then
    red "  失败: $FAIL"
else
    green "  失败: 0"
fi
echo "══════════════════════════════════"

[ "$FAIL" -eq 0 ]
