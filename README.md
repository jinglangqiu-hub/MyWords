# Personal Word Review

一个面向谷歌翻译收藏 CSV 的安卓本地生词复习应用。V1.0 目标是小体积、离线、无账号、无联网权限。

## 功能

- 导入谷歌翻译导出的无表头 4 列 CSV。
- 自动按标准化单词去重。
- 重复导入不会重复创建单词。
- 同一单词新增释义会用中文分号追加。
- 查看、搜索单词库。
- 查看单词详情，编辑释义，设置是否跳过拼写。
- 指定本次复习数量。
- 选择题阶段：3 个中文释义选项，错题进入下一轮。
- 拼写阶段：按标准化英文严格匹配，错题进入下一轮。
- 使用 Android 系统 TextToSpeech 播放英文发音。
- Room 本地数据库持久化，核心功能离线可用。

## CSV 格式

文件必须是 UTF-8 文本，无表头，每行 4 列：

```csv
英语,中文（简体）,auction,拍卖
英语,中文（简体）,stunned,目瞪口呆
英语,中文（简体）,floating,漂浮
```

字段映射：

- 第 1 列：源语言
- 第 2 列：目标语言
- 第 3 列：英文单词或短语
- 第 4 列：中文释义

导入会跳过空行、缺列行、空单词和空释义，并在导入结果中计入失败行数。

## 安装和构建

当前仓库是 Android Studio / Gradle 项目源码。构建 release APK 需要本机安装：

- JDK 17
- Android Studio 或 Android SDK
- Android Gradle Plugin 可访问的 Maven 仓库

在项目根目录执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:assembleRelease
```

生成文件位置：

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

建议最终交付命名：

```text
WordReview-v1.0-release.apk
```

## 签名

当前 `release` 已开启：

- `isMinifyEnabled = true`
- `isShrinkResources = true`
- ProGuard/R8 优化

正式分发前需要在 Android Studio 中配置 release signing config，或使用本地 keystore 配置签名。不要把 keystore 或密码提交进仓库。

## 已知限制

- 目前不包含账号、云同步、AI 释义、内置词库、广告 SDK 或联网功能。
- 选择题至少需要 3 个不同释义才能生成题目。
- 拼写判断是严格匹配：trim、小写、合并连续空格后与 normalizedTerm 完全一致。
- TTS 使用系统能力，设备没有可用英文 TTS 时不会阻塞答题，但可能没有声音。
- 当前源码可通过 Gradle Wrapper 生成 unsigned release APK；正式分发前仍需配置 release 签名。

## 版本

- versionCode: 1
- versionName: 1.0.0
- APK 体积目标：小于 30MB
