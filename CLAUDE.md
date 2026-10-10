# 醒达 Remindar

Android 个人提醒 + 待办 App：到点像闹钟一样响铃、大模型智能识别建提醒、局域网离线找手机。
仓库：https://github.com/pedrotung/Remindar（MIT）。详细设计见 `ReminderApp/设计说明.md`。

## 沟通
- 与用户的所有对话一律使用中文（含中间进度说明）。代码注释、提交说明、界面文字也用中文。

## 结构
- `ReminderApp/`：Gradle 工程（单模块 `app`），Kotlin + XML 布局（viewBinding），minSdk 24 / targetSdk 34
- `ReminderApp/app/src/main/java/com/example/reminder/`
  - 提醒核心：`Reminder.kt`（实体 + `DateUtil`）、`ReminderDb.kt`（SQLite，版本 2）、`AlarmScheduler.kt`、`ReminderReceiver.kt`、`TodoHelper.kt`
  - 到点响铃：`ReminderRingService.kt`（前台服务，闹钟流铃声最多 1 分钟）、`ReminderTonePlayer.kt`、`ReminderAlertActivity.kt`（锁屏全屏卡片）、`NotificationHelper.kt`
  - 界面：`MainActivity.kt`、`EditReminderActivity.kt`、`ReminderAdapter.kt`、`SettingsActivity.kt`、`DateTimePickerSheet.kt`（滚轮时间选择）、`AppSettings.kt`
  - `ai/`：`AiParser.kt`（OpenAI 兼容 /chat/completions，提示词在此）、`AiConfig.kt`、`SecretStore.kt`（Keystore 加密 API Key）、`AiSettingsActivity.kt`
  - `findphone/`：`FindPhoneService.kt`（前台服务）、`FindPhoneServer.kt`（ServerSocket 极简 HTTP，端口 8080，PIN 校验）、`AlarmPlayer.kt`（最大音量警笛，可越过勿扰）、`FindPhoneActivity.kt`（二维码）
- `ReminderApp/app/src/main/assets/findphone.html`：找手机网页（手机提供）；`ReminderApp/laptop/查找手机.html` 是它的副本，**修改后必须同步复制**
- `ReminderApp/design/`：图标与主题设计稿（HTML，可用 Chrome 无头模式导出 PNG）
- `ReminderApp/scripts/`：`make_release_key.ps1`（生成签名密钥）、`publish_release.ps1`（gh 创建 Release）

## 构建
```bash
cd ReminderApp
./gradlew assembleDebug     # 调试版：包名 io.github.pedrotung.remindar.debug，名称“醒达 Dev”
./gradlew assembleRelease   # 正式版：需本地 keystore.properties，否则不签名
./gradlew lintDebug
```

## 关键决定与注意事项
- **applicationId 是 `io.github.pedrotung.remindar`，已发布，不可再改**；代码包名（namespace）仍为 `com.example.reminder`，无需改动
- **正式版签名密钥不可更换、不可提交**：`keystore.properties`、`*.jks` 已在 .gitignore
- 到点提醒用 `AlarmManager.setAlarmClock`（ColorOS 会给 `setExactAndAllowWhileIdle` 加 20 秒～1 分多钟窗口导致迟到）；逾期检查用普通精确闹钟
- ColorOS 会把应用通知重要性锁定为“默认”，全屏通知因此失效：服务主动亮屏，并在有悬浮窗权限时直接启动 `ReminderAlertActivity`
- `AlarmScheduler.schedule()` 内部会先 `cancel()`，所以**不要在 `cancel()` 里停止响铃**（重复提醒到点后会重新 schedule）；停止响铃用 `ReminderRingService.stop()`
- 待办：`isTodo` + `doneAt`；`isFinished()` = 已完成待办或已响过的一次性普通提醒，只出现在“已完成”
- 列表行变灰作用于子视图（`setDimmed`），不要设整行 alpha（RecyclerView 动画会重置）
- 主题色为活力橙（`values/colors.xml`、`values-night/colors.xml`），按钮文字用 `@color/on_accent`
- 数据库升级需在 `ReminderDb.onUpgrade` 中按版本迁移，不能丢用户数据
- 智能识别结果只填表单，由用户确认后保存；剪贴板只自动粘贴 15 分钟内复制的、非敏感内容

## 发布新版本
1. `app/build.gradle.kts` 中 `versionCode` +1、更新 `versionName`
2. `./gradlew assembleRelease`，用 `apksigner verify --print-certs` 核对证书 SHA-256 不变
3. 复制为 `Remindar-v<版本>.apk`，写更新说明，`git tag -a v<版本>` 并推送
4. `powershell -ExecutionPolicy Bypass -File ReminderApp/scripts/publish_release.ps1 -Version <版本>`

## 提交规范
- 中文提交说明：首行概述，空行后列要点；结尾加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- 作者身份为 GitHub 隐私邮箱，不要使用个人邮箱
