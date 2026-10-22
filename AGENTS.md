# 项目规则

## 本地开发与版本管理

- 开始前读取 `CONTEXT.md`，检查当前分支和工作区；按任务读取相关模块文档，保留用户或其他工具尚未提交的修改。
- 日常需求在本地 `dev` 分支开发。没有该分支时从当前确认的开发基线创建；已有分支直接沿用，不重置已有工作。
- 提交、合并、推送和发布分别遵循用户明确指令。“本地提交”只创建本地 commit，不包含推送、合并、标签或 Release。未经明确要求，不自行合并主干或操作远端。
- `dev` 开发和本地打包期间保持现有 `versionCode`、`versionName`。只有用户明确要求合并到主干时才增加内部 `versionCode`；明确发布新版本时才按指定版本修改 `versionName`。版本值以 `app/build.gradle.kts` 为准。
- 修改围绕当前需求，采用最小必要范围。提交前检查 diff，只纳入相关源码、测试和文档；构建产物、临时日志、设备数据及本机环境配置不提交。

## 核心功能稳定性

网页浏览和媒体播放是本项目的核心功能。功能稳定的情况下，如无必要，不得随意修改相关代码。

确需修改时，以用户明确提出的需求或已复现的缺陷为依据，只修改完成该目标所必需的部分；验证受影响的浏览、播放行为及相关回归用例。不得在其他任务中顺带重构或调整这些核心路径。

## 测试与验证

- 根据改动范围选择测试。缺陷修复优先建立能够复现原问题的回归用例，确认修复前失败、修复后通过；纯文档修改检查内容和 diff 即可。
- JavaScript 注入与路由修改运行相关 Node 测试；Kotlin 逻辑修改运行相关单元测试和 Android Lint。完整检查与 `.github/workflows/android.yml` 保持一致：

```powershell
node tests/native-guard.test.cjs
node --test tests/fetch-script.test.cjs tests/clipboard-support.test.cjs tests/media-diagnostics.test.cjs tests/media-request-routing.test.cjs tests/userscript-runtime.test.cjs
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug
```

- 浏览、媒体播放、脚本桥接、Activity 生命周期或数据恢复的修改，还需运行受影响的真实 WebView/设备测试。媒体修改需验证实际播放时间推进，涉及加密 HLS 时验证密钥、分片及解密播放；仅 HTTP 200、代理返回成功或编译通过不能作为播放成功的结论。
- 使用本地可控服务和合成媒体复现问题，覆盖相关正常、失败及边界场景。真实网站的验证结论仅覆盖实际测试过的设备、WebView、配置和网络条件。
- 设备测试使用专用模拟器或明确指定的测试设备；先确认 `adb` 设备 ID，所有操作指定 `-s`。测试需恢复配置及显示设置，清理自己创建的资源，避免影响日常设备数据。
- 设备测试先构建并安装本次对应的应用 APK 和测试 APK，再运行指定类；完整用例说明见 `README.md` 的“验证”部分：

```powershell
.\gradlew.bat --no-daemon assembleDebug assembleDebugAndroidTest
adb -s <测试设备ID> install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s <测试设备ID> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <测试设备ID> shell am instrument -w -e class com.lightboat.app.<测试类名> com.lightboat.app.test/androidx.test.runner.AndroidJUnitRunner
```

- 检查测试输出中的最终通过/失败结果，设备测试不能只依据 `adb` 退出码判断成功。交付时区分已执行验证、未验证部分和阻塞原因，不把本地夹具通过描述为目标网站已经恢复。

## 本地打包与交付

- 使用项目 Gradle Wrapper、JDK 17 和项目配置要求的 Android SDK；Windows 使用 `gradlew.bat`。先检查现有工具链，必要时仅为当前命令指定 `JAVA_HOME` 或 `-Dorg.gradle.java.home`，不顺带升级依赖、Gradle 或 SDK 配置。
- 用户要求“编译”“打包”且未指定类型时，生成可安装的 debug APK：

```powershell
.\gradlew.bat --no-daemon assembleDebug
```

- 应用包位于 `app/build/outputs/apk/debug/app-debug.apk`；`app-debug-androidTest.apk` 仅供设备测试，不作为用户安装包交付。
- 保留固定签名 `signing/lightboat-debug.p12` 和现有 `applicationId`，确保后续 APK 可覆盖安装；签名约定见 `signing/README.md`。不得为解决安装失败而重新生成密钥或擅自卸载用户应用。
- 构建成功后确认 APK 是本次源码的产物，核对 `output-metadata.json` 的版本信息。打包交付执行 `scripts/verify_debug_signature.py` 校验固定签名；该脚本通过 `ANDROID_HOME` 定位 SDK，可为当前进程设置此变量。
- 交付提供 APK 的绝对路径链接、版本号及相关验证结果；编译失败时明确说明失败，不以旧 APK 作为本次成功产物。打包本身不包含提交、推送或发布。
