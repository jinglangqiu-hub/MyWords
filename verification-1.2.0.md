# v1.2.0 验证记录

日期：2026-11-01。版本号 1.2.0 / 5，数据库仍为版本 3，无需迁移。

## 已执行

- `testDebugUnitTest`：13 项通过，覆盖新词/到期词调度、拼写到期状态、词库轮换、同词族与重叠释义过滤、选项来源英文、候选不足和拼写轮次上限。
- `lintDebug`：0 错误，13 个现有依赖可更新提示；此次未调整依赖。
- `assembleDebug assembleAndroidTest assembleRelease`：成功；设备测试包含新增的共享词库进度和同会话纠错间隔测试。
- 使用 Room schema 3 和代码中的实际进度查询执行 SQLite 检查，6 个场景通过：空词库、新词、仅识别通过、两阶段通过及跨词库共享、不拼写、重置记忆。
- `apksigner verify --verbose --print-certs`：正式签名 Release APK 验证通过，使用 APK v2 签名。
- 已下载 GitHub Release v1.1.2 的 APK 对比，v1.2.0 与旧版本的签名证书 SHA-256 一致，可覆盖升级。
- `git diff --check`：通过。

构建使用 Android Studio JBR 21。此电脑的 JDK Unix domain socket 连接异常，构建进程临时设置了 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\nonexistent-wordreview-sockets`，使 JDK 使用 TCP loopback 回退；未修改项目或系统的持久配置。

Release 安装包：`app-release.apk`，SHA-256 为 `9db18c5c79a3fa5b0f7daaa98dbc87e95f50675426d3b58969fbaac5b98bd61a`。

## 设备验收边界

`adb devices` 无设备，`emulator -list-avds` 无可用 AVD，现有系统镜像目录只有未完成安装的占位目录。因此 Android 设备测试仅编译，未实际执行；真实语音、触控与布局未作设备验收。

手机验收重点：

1. 让最后一个词答错，进入只含该词的新一轮，确认选择题和拼写题均自动发音；再测试快速换题和手动发音。
2. 拼写初始轮答错、下一轮答对，确认该词退出队列；连续错三轮则结束本次拼写，结果显示留待次日巩固数。
3. 中途退出再继续，确认错词队列、错误次数和拼写最多三轮的规则一致。
4. 选择题分别选对、选错，确认作答后每个干扰项显示来源英文，正确释义不追加英文；答题前不显示来源英文。
5. 导入含 create/creation/creative、quick/quickly 和重叠中文释义的词库，确认这些候选被过滤；仅一个安全干扰项时显示两选项，无安全干扰项时提示补充词库。
6. 在词库列表、详情及复习设置检查进度；识别/拼写作答、不拼写设置、跨词库共享词和重置记忆后均应更新。
7. 小屏和大字号下检查新增选项英文、复习设置和结果页面可滚动访问。
