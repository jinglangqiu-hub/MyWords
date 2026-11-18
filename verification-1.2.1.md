# v1.2.1 验证记录

日期：2026-11-18。版本 1.2.1 / 6；数据库版本仍为 3。

## 本次修正

- 撤销 v1.2.0 的三轮拼写上限和“留待次日巩固”结束逻辑。本次错词一直进入下一轮，直到答对或用户主动禁用拼写；断点保存也不再按轮数提前结束会话。
- 拼写阶段只包含会话开始时拼写新词或拼写记忆到期的词，依据已有记忆快照保存范围。识别到期不会强制重复尚未到期的拼写；恢复和重新开始保持原范围。
- 历史错误次数不产生永久推荐加权。同一会话中多次纠错仍只记一次遗忘，不把现场纠正当作跨日记忆。错词在后续独立复习中首次答对后，间隔从 3 天、6 天继续增长；再次首次答错恢复 1 天。

## 已执行

- `testDebugUnitTest`：15 项通过。新增/调整的回归场景包括连续 20 轮仍保留错词、答对后退出队列、错词后续独立答对的 3/6 天间隔、未到期不推荐、新词及识别间隔保持原规则。
- `connectedDebugAndroidTest`：10 项通过，0 失败、0 错误，在 Android 16 / API 36 设备上执行。覆盖迁移、共享词库进度、记忆重置、作答幂等、会话清理、同会话纠错间隔、TTS voice 排序，以及本次新增的拼写范围在识别作答、恢复和重新开始后保持一致。
- 设备测试使用临时 Gradle init 脚本设置 `applicationIdSuffix = '.qa'`，安装独立的 `com.example.wordreview.qa` / `.qa.test`。测试后测试包已移除，原 `com.example.wordreview` 安装路径保持一致。
- `lintDebug`：0 错误，13 个依赖更新提示。
- `assembleDebug assembleAndroidTest assembleRelease`：成功。
- Release APK 签名验证通过，签名证书 SHA-256 与 v1.2.0 一致：`569c226d9a270d169c7ded6bbb9a08e7f1639a5ae14299aa012effbaaf9b05bd`。

Release APK：`app-release.apk`，SHA-256：`f85819c5d76a3ad5d059abf86e2154de2daddd658ddac187559a97886d6c8d9e`。

构建沿用此前临时的 JDK socket 回退参数，未改动持久配置。Android 设备测试不等于真实语音、触控和布局的人工验收。
