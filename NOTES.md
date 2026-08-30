# 到点 (scenario-timer)

## 项目概述

「到点」——按场景预设的计时器，到点“或静或响”。

- 包名: `dev.junyan.scenariotimer`
- 技术栈: Kotlin + View Binding + Foreground Service + AlarmManager
- 无 Compose / ViewModel / Room

## 架构要点

- **单计时器模式**: 通过 `btnStartCancel` 按钮在启动/取消间切换
- **场景管理**: 用 SharedPreferences (JSON) 持久化 `TimerScene` 列表
- **前台服务**: `TimerService` 负责倒计时，通过广播 Intent 回传状态 (TICK/FINISHED/CANCELLED)
- **滑动操作**: 场景卡片支持左滑揭示编辑/删除按钮（自定义 MotionEvent 实现）

## 常用脚本

| 脚本 | 用途 |
|---|---|
| `start-emulator.sh` | 启动 Android 模拟器 |
| `install.sh` | 完整安装（SDK 检测 + 多设备选择 + 条件构建） |
| `package-apk.sh` | 打包 release APK 并自动小版本升级 |
| `test-ui.sh` | adb + python3 自动化 UI 测试（11 项） |

## UI 自动化测试

基于 `adb` + `python3` 实现，无需安装第三方框架：

- `uiautomator dump` 导出 UI XML
- `python3 xml.etree.ElementTree` 解析元素 text/bounds
- `adb shell input tap/swipe` 模拟交互
- 覆盖: 场景切换、计时器启停、滑动编辑、添加场景

运行: `./test-ui.sh`

## 关键 UI 坐标 (Pixel 7 模拟器 1080x2400)

| 元素 | resource-id |
|---|---|
| 时间显示 | `tvTimerDisplay` |
| 选中场景名 | `tvSelectedScene` |
| 场景列表容器 | `sceneContainer` |
| 单个场景根 | `sceneItemRoot` |
| 编辑按钮 | `btnSceneEdit` |
| 删除按钮 | `btnSceneDelete` |
| 添加场景 | `btnAddScene` |
| 启动/取消 | `btnStartCancel` |

## 已知问题与修复记录

### 选中场景卡片透明导致按钮透出 (2026-08-01)

**问题**: `tintBackground(color, 0.1f)` 使用 alpha=25/255 的半透明色，前景几乎透明，编辑/删除按钮直接透出。

**修复**: 改为与白色混合，保持完全不透明:

```kotlin
private fun tintBackground(color: Int, alpha: Float): Int {
    val r = (Color.red(color) * alpha + 255 * (1 - alpha)).toInt()
    val g = (Color.green(color) * alpha + 255 * (1 - alpha)).toInt()
    val b = (Color.blue(color) * alpha + 255 * (1 - alpha)).toInt()
    return Color.argb(255, r, g, b)
}
```
