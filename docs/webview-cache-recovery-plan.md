**WebView 重定向循环的缓存恢复方案 — 交给 DeepSeek 的实现说明**

项目：轻舟 Android 网页应用。

仓库：`C:/Users/WinPC/Desktop/new-project/app_view`。

这是一份待实现方案，下面的接口示意和测试要求尚未实现或执行。请先核对当前代码，再按本方案完成修改和验证。

**1. 问题、证据和目标**

用户访问 `https://huangguoai.com/` 时，APP 主框架收到 `code=-9 net::ERR_TOO_MANY_REDIRECTS`；同一手机、同一网络的浏览器可以打开。用户清除 APP 缓存后，APP 恢复访问。

调查中，实际 Android 模拟器上的 APP 在启用和停用同一份 `no-ad.js` 时都成功加载首页，网络观察得到首页 HTTP 200，没有重定向。模拟器系统 WebView 版本为 `124.0.6367.219`。

这些证据强烈指向 WebView 缓存状态。原故障未抓到完整跳转链，不能把“某条 301/308 已确定被缓存”当成已证实事实，也不能承诺清缓存能修复所有重定向循环。

实现目标：主页面遇到重定向循环时，在 APP 内提供“清除网页缓存并重试”，让用户不必去系统设置清缓存；保留普通重试入口。

采用显式按钮触发。每次点击执行一次清缓存和重新打开，不自动恢复、不自动循环重试。

**2. 用户可见行为**

仅当当前主框架收到 `WebViewClient.ERROR_REDIRECT_LOOP` 时，错误页显示以下内容：

- 保留现有“重试”“编辑地址”“调试日志”。
- 增加“清除网页缓存并重试”按钮。
- 提示：“网页跳转次数过多。可能存在过期的网页缓存，可尝试清除缓存后重试。”
- 明确说明范围：“会清除应用内所有网页的资源缓存，保留登录信息、配置和脚本数据。”
- 保留原始错误描述，便于用户复制日志和定位。

说明文字可以并入现有 `statusDetail`，不必额外新增提示控件。新增按钮放在普通“重试”之后、“编辑地址”之前，沿用现有按钮样式，默认 `gone`。

点击后立即进入现有准备/加载流程，重新获取和准备启用的脚本，再打开该配置的入口地址。该流程沿用当前平台、UA、脚本顺序和现有的脚本失败处理。

恢复后再次出现重定向循环，继续显示错误和两个重试入口，等待用户操作。断网、证书错误、脚本准备错误、渲染进程退出、配置恢复页、HTTP 403/429/5xx、iframe 或子资源错误不展示这个新按钮。

HTTP 403/429/5xx 继续展示网站原始页面，验证脚本、表单、Cookie 和后续跳转保持可用。

**3. 修改位置**

以下行号以方案编写时的代码为准，实施时按符号定位。

| 文件 | 修改内容 |
| --- | --- |
| [MainActivity.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/MainActivity.kt:1097) | `onReceivedError` 判断主框架重定向错误；`failPage` 传递恢复资格；`showError` 设置按钮；`openProfile`/`openPrepared` 执行缓存恢复。 |
| [activity_main.xml](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/res/layout/activity_main.xml:265) | 在 `statusPanel` 增加默认隐藏的恢复按钮。建议 ID：`clearWebCacheRetryButton`。 |
| [strings.xml](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/res/values/strings.xml) | 增加按钮、原因提示和全局缓存范围说明。 |
| [README.md](C:/Users/WinPC/Desktop/new-project/app_view/README.md:119) | 说明错误恢复入口、清理范围和保留的数据；补充验证命令。 |

新增设备测试文件建议为 `C:/Users/WinPC/Desktop/new-project/app_view/app/src/androidTest/java/com/lightboat/app/WebViewCacheRecoveryTest.kt`。参考 [VisibleNavigationTest.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/androidTest/java/com/lightboat/app/VisibleNavigationTest.kt:21) 中的真实 Activity、NanoHTTPD 和异步等待方式。

**4. 错误识别与页面状态**

在 `onReceivedError` 原有的当前导航判断内，使用原生错误常量：

```kotlin
if (request.isForMainFrame && isCurrentNavigation()) {
    val allowCacheRecovery =
        error.errorCode == WebViewClient.ERROR_REDIRECT_LOOP
    failPage(existingMessage, allowCacheRecovery)
}
```

`existingMessage` 代表现有错误信息构造逻辑；保留当前证书错误信息处理。不要通过匹配 `error.description` 文本判断错误类别。

建议给 `failPage` 增加默认参数 `allowCacheRecovery: Boolean = false`，其他调用者沿用默认行为。在错误发生时保存失败配置的 ID，并只在允许恢复时把这个 ID 传给错误页。

建议给 `showError` 增加默认参数 `cacheRecoveryProfileId: String? = null`。每次渲染错误页都先隐藏恢复按钮、移除旧点击监听，再按本次参数设置，防止按钮出现在脚本错误或配置恢复页。`showLoading` 也必须隐藏并重置该按钮。

当前 `failPage` 会 post 销毁失败的 WebView，而 `destroyWebView` 会重置 `pageFailed` 并递增 `navigationGeneration`。因此：

- 恢复入口必须在失败 WebView 销毁后仍然有效。
- 不用 `pageFailed` 的当前值作为恢复按钮的可用条件。
- 不要求点击时的 `navigationGeneration` 等于销毁前捕获的值。
- 不持有或使用已销毁的 `failedView` 清缓存。

按钮绑定失败时的配置 ID。点击时重新从当前 `config` 中解析配置，并检查仍在对应错误页、当前配置 ID 仍匹配。配置已被删除或用户已经切换页面时，旧操作不应生效。

点击后阻止重复点击并进入原有加载流程；若启动同步失败且仍停在原错误页，应恢复按钮可点击。普通“重试”继续走现有普通加载路径。

**5. 清缓存的准确时机与参数传递**

沿用当前“准备脚本 → 创建主 WebView → 注册脚本 → loadUrl”的流程，不新增专门用来清缓存的临时 WebView。

建议扩展现有的内部重载：

```kotlin
private fun openProfile(
    profile: Profile,
    skippedScriptIds: Set<String>,
    clearWebCache: Boolean = false
)
```

保留单参数 `openProfile(profile)`，让普通刷新、切换配置和现有 `::openProfile` 引用继续使用默认行为。恢复按钮显式传入 `clearWebCache = true`。

在这一次打开操作中，用局部变量保存尚未消费的恢复意图，例如 `var cacheClearPending = clearWebCache`。不要保存到配置 JSON、SharedPreferences 或 Activity 的全局“下一次打开清缓存”标志。

准确插入点是 `openPrepared()` 内、原有 `current()` 检查通过之后：

```kotlin
prepareMainWebView(profile)
if (cacheClearPending) {
    val recoveryView = checkNotNull(webView)
    // 记录恢复请求；这里运行在 WebView 所属的 UI 线程。
    recoveryView.clearCache(true)
    cacheClearPending = false
}
createWebView(profile, nativeGuardScript, prepared.toList(), navigationRequest)
```

新建的主 WebView 尚未打开目标网页，因此可以调用清缓存 API，再继续原有注入和加载流程。不要把清理放进所有调用都会执行的 `prepareMainWebView`、`destroyWebView`、普通刷新或通用错误处理代码中。

`openProfile` 内部已有脚本下载失败重试、安装失败重试、跳过失败脚本后继续等递归打开路径。需要逐一核对这些路径：

- 尚未执行清理时，内部重试应传递仍待执行的 `cacheClearPending`，避免恢复按钮操作变成普通重试。
- 已成功调用清理后，将标志置为 false；后续内部重试不重复清理。
- 用户主动切换配置或普通刷新时，启动新的默认打开操作，不继承旧恢复标志。
- 异步脚本回调和 post 回调继续使用现有 `generation`、`navigationGeneration` 和 `current()` 防护，旧操作不得清理新配置正在使用的缓存。

保留 `WebSettings.LOAD_DEFAULT`。本次实现只增加明确的清理操作，不增加永久禁用缓存的配置。

`clearCache(true)` 没有完成回调；不要为它编造异步完成事件、写死等待时间或后台线程包装。调用必须在创建该 WebView 的线程上。若调用抛错，记录失败并显示可重试错误，不应记录清理成功或静默吞掉异常。

**6. 数据边界和日志**

Android 官方 API 明确：WebView 的资源缓存按应用共享，`clearCache(true)` 会清除本应用所有 WebView 的资源缓存，包含磁盘缓存；无法把它描述为只清理当前站点或当前配置。

仅调用资源缓存清理 API。保留 Cookie、localStorage、IndexedDB、配置、脚本库、GM 数据、备份和 WebDAV 设置。不额外注销 Service Worker，也不新增网站 Cache Storage 的删除操作；底层资源缓存范围遵循系统 WebView 的实现。本功能不承诺修复站点存储或服务器规则造成的重定向循环。

不要调用 `CookieManager.removeAllCookies`、`removeSessionCookies`、`WebStorage.deleteAllData`，不要删除应用数据目录，不要通过 `clearHistory()` 代替 HTTP 缓存清理。无需改 UA、UA-CH、媒体代理或加入黄果站点专用逻辑。

使用现有 `DebugLog`，建议增加 `cache-recovery` 来源，记录：

- 用户发起恢复：失败配置 ID、当前导航标识、脱敏后的入口 URL。
- 已在新 WebView 上调用清理：例如 `clear requested includeDiskFiles=true scope=application`。
- 调用异常：记录经过限制长度的错误原因。

后续成功或失败继续由现有 `page`、`page-error`、`ui-error` 日志记录，不建立另一套结果状态机。日志里的 `requested` 只表示调用已发起，不声称能观察磁盘清理完成。沿用 `DebugLog.safeUrl`，不输出 Cookie、Authorization 或完整查询参数值。

**7. 验证要求**

使用本机 HTTP 服务和真实 WebView 做核心回归，不依赖公网网站当前行为，也不要仅通过手工调用 `onReceivedError` 验证按钮。

必须覆盖以下行为：

1. **缓存跳转恢复。** 用带 `Cache-Control: public, max-age=3600` 的 301 或 308 跳转构造并确认缓存循环。收到真实主框架 `ERROR_REDIRECT_LOOP`，失败 WebView 被销毁后恢复按钮仍可用；服务端改为正常 200，点击恢复后应出现真实成功页面。
2. **确认清理发生。** 使用请求计数和另一个明确可缓存的页面/资源，证明测试确实预热了 HTTP 缓存，恢复后再次请求服务器并得到新内容，避免把“服务端自己恢复”误当成缓存清理生效。尽量用两个本机 origin 验证清理是应用范围。
3. **数据保留。** 在受控页面预先设置 Cookie、localStorage、IndexedDB 和一个 GM 值；恢复后检查这些值及配置仍存在。用服务器实际收到的 Cookie 验证凭据保留。
4. **持续服务端循环。** 服务端继续返回跳转循环时，清理后仍显示错误，没有自动第三次、第四次加载；每次用户点击最多发起一次恢复操作。
5. **错误隔离。** 断网、证书错误、iframe/子资源跳转错误、脚本失败和配置恢复页没有恢复按钮。已有 HTTP 403 验证页面、Cookie 以及验证后导航测试继续通过。
6. **取消和内部重试。** 脚本准备较慢时切换配置，旧恢复操作不得清理新配置缓存；脚本准备失败后选择重试或跳过，待执行的恢复意图不丢失，清理已调用后也不重复。

注意不同 Chromium 版本可能对错误跳转缓存有不同处理。先用请求计数和页面行为确认缓存循环夹具有效；如果目标版本无法稳定构造，明确记录缺口，用真实服务端循环验证错误入口、用独立缓存资源验证清理语义，不能宣称已覆盖原始缓存循环。不要为了得到绿色结果而只伪造错误回调。

设备测试结束后恢复测试前配置、清理本次夹具数据并关闭 Activity/本机服务。人工检查小屏和大字体下的按钮与提示是否完整可见。

构建和验证命令：

```powershell
node --test tests/*.test.cjs
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain
adb -s <测试设备ID> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <测试设备ID> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <测试设备ID> shell am instrument -w -e class com.lightboat.app.WebViewCacheRecoveryTest com.lightboat.app.test/androidx.test.runner.AndroidJUnitRunner
```

同时执行与导航改动直接相关的既有 `VisibleNavigationTest`。没有设备环境时明确说明设备测试未执行，构建通过不能替代真实 WebView 回归。

**8. 交付标准**

提交实现说明时，列出修改文件、行为变化、实际执行的检查及结果、未完成的设备验证和已知限制。按仓库现有约定在本地 `dev` 开发；不推送、不合并、不发布、不增加 `versionCode` 或修改 `versionName`。

最终应满足：主框架重定向错误具有应用内缓存恢复入口；普通访问保持浏览器默认缓存；失败 WebView 销毁后仍能操作；操作保留网站凭据和用户数据；真实服务端循环不会引发自动重试风暴。

API 依据：[Android WebView.clearCache](https://developer.android.com/reference/android/webkit/WebView#clearCache(boolean))、[WebViewClient.ERROR_REDIRECT_LOOP](https://developer.android.com/reference/android/webkit/WebViewClient#ERROR_REDIRECT_LOOP)。
