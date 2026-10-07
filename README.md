# 到点

> 按场景预设的计时器，到点「或静或响」。

一个轻量 Android 计时器应用。为每个场景预设时长和到点行为——睡眠时定时静音，午休时到点响铃，不用每次重复设置。

## 功能

- **场景化预设**：内置「睡前 30 分钟静音」「冥想 10 分钟静音」「午休 20 分钟静音」，可自由增删改
- **到点两种行为**：
  - `静音`——时间到自动将媒体音量静音，取消计时后恢复原音量
  - `响铃`——时间到循环响铃，最长 1 分钟（期间可随时手动关闭）
- **多计时器**：每个场景卡片独立控制启停，互不干扰
- **可靠后台运行**：Foreground Service + AlarmManager，倒计时广播驱动，不被轮询拖累
- **简洁交互**：场景卡片左滑即可编辑/删除

## 环境要求

- 运行：Android 9+（API 28）
- 开发：JDK 11+、Android SDK（compileSdk 34）

## 使用方式

1. **开始计时**：点选一个场景卡片，点「开始计时」
2. **取消计时**：再点一次按钮即可取消，取消后恢复原言量
3. **编辑/删除场景**：场景卡片**左滑**，露出编辑/删除按钮（至少保留一个场景）
4. **添加场景**：列表底部「添加场景」，可设置名称、时长（分钟/小时）和到点行为（静音/响铃）
5. **到点后**：
   - 静音模式：媒体言量自动归零，取消计时后恢复
   - 响铃模式：铃声循环响，弹窗中点「停止」或等 1 分钟自动停
6. **多计时器**：不同场景可同时计时，各自独立启停

> 首次使用建议关闭电池优化，确保后台计时可靠。

## 技术栈

- Kotlin + View Binding（无 Compose / ViewModel / Room）
- Foreground Service + AlarmManager + Broadcast
- SharedPreferences (JSON) 持久化场景配置
- minSdk 28 / targetSdk 34

## 构建

```bash
# Debug 构建
./gradlew assembleDebug

# 构建并安装到已连接的设备
./install.sh

# 打包 Release APK（自动小版本升级，签名配置见 local.properties.example）
cp local.properties.example local.properties  # 填入签名信息
./package-apk.sh
```

## 辅助脚本

| 脚本 | 用途 |
|---|---|
| `install.sh` | 构建并安装到设备（SDK 检测 + 多设备选择） |
| `package-apk.sh` | 打包 Release APK，自动递增版本号 |
| `start-emulator.sh` | 启动 Android 模拟器 |
| `test-ui.sh` | 基于 adb + python3 的 UI 自动化测试 |

## 项目结构

```
app/src/main/kotlin/dev/junyan/scenariotimer/
├── App.kt           # Application 入口
├── MainActivity.kt  # 场景列表、卡片交互、多计时器管理
├── TimerEntry.kt    # 场景数据模型（名称/时长/到点行为/单位）
└── TimerService.kt  # 前台服务：倒计时、静音/响铃执行
```

## License

MIT
