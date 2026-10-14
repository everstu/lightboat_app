# 轻舟 · Android 网页应用

原生 Kotlin 设置界面 + Android 系统 WebView。没有内置网址；没有配置时提示添加。启动自动打开**最后一次切换的配置**，无需手动设默认项。

## GitHub Actions 在线构建

1. 把本目录全部文件上传或推送到 GitHub 仓库，包含 `.github/`、`gradle/`、`gradlew` 和 `gradlew.bat`。
2. 仓库 **Actions → Build Android APK → Run workflow** 手动触发；普通推送和 PR 不自动构建。
3. 成功后在该次运行的 **Artifacts** 下载 `lightboat-debug-apk`，解压得到 `app-debug.apk`，传到 Android 手机安装。

工作流执行网页交互和脚本获取测试、Kotlin 单元测试、Android Lint 和 `assembleDebug`，无需本地安装 JDK 或 Android SDK。最低 Android 8.0（API 26），系统 WebView 需支持提前注入和 WebMessage 接口。

输出为可直接安装的 debug APK。手动构建与标签发布固定使用仓库中的 `signing/lightboat-debug.p12`，签名一致，可覆盖安装后续版本；无需配置签名 Secrets。升级时保持相同 `applicationId`，`versionCode` 不低于已安装版本。

以前使用 runner 临时密钥构建的 APK 与新签名不同，首次迁移须先导出配置、卸载旧版、安装新版并导入。请保留仓库里的 debug 签名文件，不要重新生成。它是公开的开发签名，不用作生产发布密钥，详见 `signing/README.md`。

### 标签自动发布

推送 `v*` 标签会触发独立的 **Publish GitHub Release** 工作流，测试、Lint 和固定签名校验通过后，将 debug APK 上传到该标签的 GitHub Release。无需额外上传密钥或配置 Secrets，使用仓库中的固定 debug 签名。例如：

```sh
git tag v1.1.0
git push origin v1.1.0
```

附件名为 `lightboat-v1.1.0-debug.apk`。标签发布与普通手动构建相互独立。仓库 Actions 的默认 `GITHUB_TOKEN` 需允许工作流声明的 `contents: write` 权限。

## 使用与配置

- 无配置时，点击 **添加配置**，填写名称、网页 URL 和 JS 在线地址；可只保存，也可保存并立即打开。
- 配置列表右下角的 **＋** 悬浮按钮用于添加配置，滚动列表时始终可见。
- 长按配置卡片或右上角排序图标，上下拖动调整顺序；松手自动写入配置文件，重启和 JSON 导入导出保留顺序。排序不改变最后使用的启动配置。
- 浏览页贴边悬浮按钮 → **配置管理** 查看所有配置；点击 **打开** 切换，同时记录为下次启动项。
- **编辑** 修改名称、地址和平台；**保存**只写入配置并返回列表，**保存并打开**才切换网页并记录为下次启动项。
- 删除最后使用项时，下次启动使用剩余第一组；删除全部配置后提示添加。
- **导出 JSON / 导入 JSON** 使用系统文件选择器读写配置文件；导入在确认后替换全部配置。
- 悬浮菜单中的 **刷新** 重新获取脚本并打开该配置的入口网页，继续遵循浏览器缓存规则。
- 每组配置支持 **Apple / Android / Windows 平台预设**及可编辑 UA。平台会随配置保存，并同步网页、子资源、Service Worker、用户脚本请求和原生音视频请求的 UA 与 UA Client Hints；Apple/Windows 还覆盖页面可见的 `navigator.platform`、`vendor` 和触点数量，避免继续暴露安卓默认值。Windows UA 使用当前 Chromium 版本，Apple 使用 iPhone Safari 18.5，Android 使用设备默认移动 UA。支持的系统 WebView 会清空旧式 `X-Requested-With` 应用包名允许列表；旧 JSON 会从已有 UA 推断平台。

运行时配置保存在应用私有目录 `files/config.json`，原子写入；设置操作都修改此 JSON 文件。`app/src/main/assets/config.json` 是首次安装的空配置模板。`config.example.json` 仅展示格式，不会内置到应用。

```json
{
  "version": 1,
  "lastProfileId": "work",
  "profiles": [
    {
      "id": "work",
      "name": "工作应用",
      "url": "https://your-site.example/",
      "scriptUrl": "https://your-site.example/inject.js",
      "userAgent": "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
  ]
}
```

空配置：`{"version":1,"lastProfileId":null,"profiles":[]}`。非空配置的 `lastProfileId` 必须对应已有项；ID 不重复，名称为 1–60 字符，最多 50 组。地址支持 HTTP/HTTPS，不接受 `file:`、`javascript:` 或带账号密码的 URL。JS 必须是可直接读取的脚本文件，不能填写 GitHub 文件预览页等 HTML 地址。

UA 最长 2048 字符，使用可打印 ASCII，不接受换行和控制字符；更改平台或 UA 后，网页内容及服务器按 UA/Client Hints 区分的缓存可能不同。平台模拟不改变真实 WebView 内核、TLS 指纹或设备硬件能力。

Android WebView 的原生媒体加载器可能绕过 `WebSettings.userAgentString`。应用在文档开始时把 HTTP(S) 音视频地址路由到仅监听本机回环地址的会话代理，再由共享 HTTP 客户端使用配置 UA 请求上游；HLS 播放列表、分片、密钥、初始化片段及普通音视频 Range 请求保留 Cookie、Referer、重定向和缓存语义。代理地址带每次浏览随机令牌，切换配置或销毁网页后立即关闭；上游失败时不会用系统 UA 重试。

## 全屏与扫码

网页成功显示后隐藏应用标题栏和系统状态/导航栏，网页及播放器延伸到屏幕边缘，不再为刘海区域留下整条顶部空白。仅悬浮菜单按钮和播放器退出按钮避开刘海，保留键盘避让。边缘滑动可暂时唤出系统栏，这是 Android 的沉浸式行为；配置页、错误页和扫码页恢复正常系统栏。

浏览时点击贴边悬浮按钮，底部菜单提供 **切换配置、扫码复制、刷新、配置管理**。按钮可拖动到左右边缘，闲置 3 秒后变淡并向边缘收起 8 dp，保留 48 dp 可点击区域。无需系统悬浮窗权限。选择当前正在浏览的配置、关闭菜单、扫码返回或从配置列表返回浏览均不重新加载网页；切换到其他配置才重新打开。系统回收应用进程后需重新加载，这是正常生命周期行为。

配置编辑页的网页地址和 JS 地址各有扫码按钮：识别单个 HTTP/HTTPS URL 后回填对应字段，不自动保存或打开；普通文字或无效 URL 提示错误并保留原输入内容。菜单 **扫码复制** 识别任意二维码文字，显示结果，点击 **复制** 写入系统剪贴板，也可重新扫描。

使用 APK 内置的 ZXing 离线二维码识别，仅支持二维码。首次点击扫码时请求相机权限，不保存拍摄图片；拒绝权限可以返回并继续浏览，也可以前往系统应用设置授权。应用本身不向网页开放相机权限。扫码复制属于原生功能，网页复制粘贴限制继续保留。

## 浏览器缓存与提前注入

应用**不单独保存 JS 文件，不建立自定义脚本缓存**。脚本由一个短暂的 WebView 内部加载页调用 `fetch(..., {cache: 'default'})` 获取，使用 Chromium 自带的 HTTP 缓存。拿到脚本文本后销毁加载页，再在浏览用 WebView 中注册脚本，最后打开目标网址。

执行顺序：

1. 读取最后使用的配置，检查 WebView 功能支持。
2. 通过浏览器获取 JS 文本；有效 HTTP 缓存可以直接命中，过期时按服务器规则验证/更新。
3. 解析用户脚本头，预加载 require/resource，再注册交互限制和用户脚本运行器。
4. 注册完成后调用 `loadUrl()`。普通 JS 在 document-start 执行；油猴格式脚本按 run-at 调度，默认 document-idle，并遵循 match/exclude/noframes。

**缓存命中取决于服务器响应头**，无法保证每次免网络请求。`Cache-Control: no-store` 不缓存，`no-cache` 要求验证，带有效 `max-age` 的资源可直接复用。建议服务器使用 `Cache-Control`、`ETag`/`Last-Modified` 控制更新；长期缓存的脚本可使用版本化 URL。网页使用 `WebSettings.LOAD_DEFAULT`，不强行展示过期内容。

加载页使用脚本 URL 的 HTTP(S) origin，因此直接地址的请求是同源。跨域重定向必须满足浏览器的 CORS 规则；最好填写最终 JS 文件地址。脚本最大 1 MiB，准备超时 30 秒；拒绝空脚本、HTML、非成功响应。失败时不打开目标网页，而是显示重试/修改配置入口。

用户脚本不会在加载页执行，只传回文本。传递脚本文本的 WebMessage 接口仅存在于临时加载页，限制到该脚本 origin。正式浏览页另有只写文本的剪贴板接口，用于支持网页复制按钮；不开放剪贴板读取。

运行器覆盖后续导航、跨域跳转和 iframe，按脚本声明筛选；每个匹配的新文档执行一次，单页应用的同文档路由不重复注入。切换配置销毁旧浏览页、脚本命令和未完成的 GM 请求。设备不支持提前注册运行器时提示更新 Android System WebView / Chrome。

document-start 时 DOM 可能尚未创建。需要操作元素时，用户 JS 可以采用以下模式；注册监听或拦截网络可直接在顶部执行：

```js
function applyChanges() {
  // 操作已创建的 DOM
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', applyChanges, { once: true });
} else {
  applyChanges();
}
```

普通 JS 的提前注入保证执行时序，用户脚本的语法和逻辑仍需正确。需要原生存储快照的 document-start 脚本会先等待 GM 握手。脚本顶层同步计算会阻塞页面加载。

## 油猴格式用户脚本

内置运行器支持 `@match`/`@include`/`@exclude`/`@noframes`、四种 `@run-at`、`@require`/`@resource`、`@grant`、`@connect` 和常用 `GM_*`/`GM.*`：持久存储、值变更监听、样式/元素、资源、脚本菜单、跨域 HTTP、文本剪贴板、日志和应用内提示。带脚本头但未指定时机默认 document-idle，解决需要 head/body 的脚本过早启动问题。

悬浮菜单新增 **脚本命令** 和 **脚本日志**，不用退出网页即可执行脚本注册的命令或查看报错。GM 存储按配置与脚本身份隔离，独立于网页 localStorage；依赖资源继续使用 WebView 缓存，不额外保存脚本文件。

WebView 运行器不是浏览器扩展，完整接口、边界和未实现能力见 [用户脚本兼容表](docs/userscript-compatibility.md)。尤其不能把原生跨域接口视为任意网页权限；只向声明 grant/connect 的可信脚本开放，不具备油猴扩展的安全隔离世界。

## 浏览体验与范围

硬件加速、DOM Storage、站点数据库、HTTP 缓存及 Cookie；加载进度与 Android 返回键网页历史。允许站点在首次播放后自动切换下一段视频/音频，允许 HTTPS 站点兼容加载旧 HTTP 媒体资源，并允许跨域 iframe 播放器保存登录态、进度和连播会话 Cookie。HTTPS 播放器可请求受保护媒体标识；相机和麦克风请求仍拒绝。屏蔽 WebView 长按、文本操作工具条、右键菜单、正文选择、拖拽和剪切/粘贴事件及快捷键；保留点击、滚动、普通键盘输入、光标和网页自身的复制功能。配置编辑器允许正常复制粘贴。

打开配置管理、脚本命令或应用内弹窗时，当前 WebView 不重建，并保留原有布局尺寸，减少返回网页时的重排、白屏和媒体状态抖动。Activity 只有完全不可见时才暂停 WebView，短暂失去焦点不会反复暂停/恢复播放器。切换到其他配置、刷新入口或系统回收进程仍会重新创建页面。

网页 `copy` 事件、`document.execCommand('copy')` 和复制快捷键不再被应用拦截；支持 HTTPS（及安全的本机 HTTP 地址）下用户点击触发的 `navigator.clipboard.writeText()`，最多 64 Ki 字符。复制接口同时检查网页用户激活状态和近期原生触摸/键盘事件，不允许无交互的后台复制，也不新增读取剪贴板、图片或富文本复制能力。普通 HTTP 网页可继续使用浏览器允许的旧式复制方式。

播放器通过 WebView 的 `onShowCustomView` / `onHideCustomView` 进入原生全屏层，自动切为横屏、保持屏幕常亮并隐藏悬浮菜单。播放器退出、右上角关闭按钮或 Android 返回键均先退出播放全屏，恢复之前的屏幕方向和悬浮菜单，保留同一个网页。页面跳转、错误及 WebView 销毁时也清理播放器全屏。支持触发标准视频/HTML 全屏接口的播放器；站点自定义 CSS 全屏或没有调用全屏接口的横屏按钮仍由站点自身控制。

实际性能取决于网页、脚本、网络和设备，不能保证任意网页等同纯原生界面的性能。没有强改页面 viewport 或滚动布局。

没有开启离屏预栅格、大内存模式或主动预下载下一段媒体：这些做法会显著增加内存、流量和耗电。WebView 默认已将可见渲染进程设为重要优先级；应用保留该策略。进入后台或锁屏后仍会暂停 WebView，真正的后台音频需要前台媒体服务和系统通知，不属于当前网页播放模式。

未扩展网页的相机/麦克风/定位、文件上传、下载、外部 App 跳转或独立新窗口功能。配置之间共享 WebView 的 Cookie、存储和缓存，不提供多账号隔离。无需存储权限。

交互限制用于移除网页操作菜单，不是内容保护；网页主动改写事件/CSS、键盘将剪贴板作为普通文字输入、截图等不能完全拦截。只使用可信的脚本，因为它拥有所在页面的 JavaScript 权限。

## 验证

本地无需 Android 工具即可执行：

```sh
node tests/native-guard.test.cjs
node --test tests/fetch-script.test.cjs
node --test tests/clipboard-support.test.cjs
node --test tests/userscript-runtime.test.cjs
```

Android 编译、Lint、Kotlin 测试由 GitHub Actions 执行。真机验收重点：

1. 空配置提示添加；添加第一组后重开直接打开。
2. 切换到第二组，重开仍打开第二组；编辑、JSON 导入导出、删除项均正常。
3. 为 JS 设置可缓存响应头，查看服务器日志：缓存有效期内重开无需重新下载脚本；过期后按服务器规则验证。
4. JS 设置 `window.__injectedEarly = true`；目标网页最前面的脚本检查此值，刷新、跳转和 iframe 也检查。
5. 长按、鼠标右键、剪切/粘贴被拦截；网页复制按钮、滑动、点击与普通输入正常。
6. 脚本获取失败、页面断网、HTTP/证书错误能重试或修改配置。
7. 成功打开后全屏；悬浮按钮拖动、收起、菜单、刘海及键盘避让正常；扫码取消/返回不刷新网页。
8. 两个字段扫码准确回填，普通文本仅在扫码复制入口支持，拒绝相机权限可返回。
9. Apple/Android/Windows 平台与 UA 可切换，JSON 往返保留平台，旧 JSON 可导入并推断平台；服务器观察网页和 JS 请求头、UA Client Hints，页面观察 navigator 平台值。
10. HTTPS 网页的 `navigator.clipboard.writeText()` 和旧式复制按钮有效；无用户点击的脚本复制失败。
11. 视频全屏切横屏；播放器退出、应用关闭按钮和 Android 返回键均恢复原方向且不重载网页；检查跨域 iframe 播放器、暂停/恢复及全屏中页面跳转。
12. 油猴脚本无 run-at 时等 DOM 就绪；match/exclude/noframes 正常，require/resource 准备正确，GM 存储重启后保留，命令和日志可查看。
13. 长按拖动配置排序并自动滚动，重启/导出后顺序保留，启动项不变；右下角新增按钮不随列表滚走，也不会挡住最后一项操作。
14. 播放列表自动切换下一段视频/音频不要求重复点击；跨域 iframe 播放器保持会话。打开配置页再返回，播放位置和网页布局不重建；切到后台后媒体暂停，返回后可继续。

参考：[提前注入接口](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript(android.webkit.WebView,java.lang.String,java.util.Set%3Cjava.lang.String%3E)) · [WebView HTTP(S) base URL](https://developer.android.com/reference/android/webkit/WebView#loadDataWithBaseURL(java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String))。
