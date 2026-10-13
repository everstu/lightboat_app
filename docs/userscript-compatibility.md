# 用户脚本兼容范围

轻舟内置用户脚本运行器，支持表中的油猴功能，但不是 Tampermonkey 浏览器扩展本体。每组配置仍运行一个主脚本；依赖和资源通过 WebView 自带 HTTP 缓存加载，不单独保存 JS 文件。

## 元数据与执行

| 指令 | 行为 |
| --- | --- |
| `@name`、`@namespace`、`@version`、`@description` | 识别信息及 `GM_info`，名称和 namespace 参与存储隔离 |
| `@match` | HTTP/HTTPS 通配协议、域名/子域、路径和查询串匹配，不匹配 hash；不支持 `file:`/扩展 URL |
| `@include`、`@exclude` | 通配符或 `/正则/`，排除规则优先；hash 不参与匹配 |
| `@exclude-match` | 以 match 格式排除 |
| `@noframes` | 仅主文档执行，否则独立匹配每个 iframe |
| `@run-at document-start` | 在文档开始时调度；需要 GM 存储的脚本会先等原生快照握手，可能晚于网页最早的同步脚本 |
| `@run-at document-body` | 等 body 出现 |
| `@run-at document-end` | DOMContentLoaded 时或之后 |
| `@run-at document-idle` | DOMContentLoaded 后排队执行；带用户脚本头但未指定 run-at 的默认值 |
| 无 `==UserScript==` 头 | 保留普通 JS 的 document-start 原文执行行为 |
| `@require` | 相对/绝对 HTTP(S) 地址，按声明顺序预下载并在主体之前执行，最多 16 项 |
| `@resource 名称 URL` | 预下载文本/二进制，最多 32 项；供资源 API 使用 |
| 资源完整性 | require/resource URL 可附 `#sha256=...`、`#sha384=...`、`#sha512=...`，支持十六进制和 base64 校验值；其它格式报错 |
| `@grant` | 只向脚本提供明确列出的 API；`none` 只提供 `GM_info`，省略 grant 为无授权 |
| `@connect` | 跨域 GM 请求授权；支持域名及其子域、IP、`self`、`*`，同源请求无需额外 connect |

预下载每项资源最多 1 MiB，依赖/资源合计最多 8 MiB；每次依赖准备最多 30 秒。依赖失败或完整性不符时显示脚本准备失败，不提前打开目标网页。普通浏览器 fetch 跨域重定向受 CORS 约束。

所有脚本运行于页面 JS 环境，授予接口通过函数参数限定到该脚本。`unsafeWindow` 授权后指向页面 window。**不具备 Tampermonkey 扩展隔离世界的安全 sandbox**，不支持特殊 `@sandbox`、`@run-in`、`@webRequest`；这些指令会记录警告。只加载可信脚本。脚本顶层变量处于运行器的函数范围，不会自动成为 window 属性。

不会自动检查/安装 `@updateURL`/`@downloadURL`，不会下载 `@icon`。更新由刷新时重新请求配置中的脚本 URL 及浏览器缓存规则控制。不缓存和重放已发生的 DOM/load 事件；脚本应检查 `document.readyState`。SPA 同文档路由不自动重跑脚本。

## API

| 功能 | 旧式同步/回调 API | 现代 Promise API |
| --- | --- | --- |
| 元数据 | `GM_info` | `GM.info`（属性） |
| 持久值 | `GM_getValue` / `GM_setValue` / `GM_deleteValue` / `GM_listValues` | `GM.getValue` / `GM.setValue` / `GM.deleteValue` / `GM.listValues` |
| 批量值 | `GM_getValues` / `GM_setValues` / `GM_deleteValues` | `GM.getValues` / `GM.setValues` / `GM.deleteValues` |
| 值变更监听 | `GM_addValueChangeListener` / `GM_removeValueChangeListener` | `GM.addValueChangeListener` / `GM.removeValueChangeListener` |
| DOM | `GM_addStyle` / `GM_addElement` | `GM.addStyle` / `GM.addElement` |
| 资源 | `GM_getResourceText` / `GM_getResourceURL`（及 Url 别名） | `GM.getResourceText` / `GM.getResourceUrl` |
| 命令 | `GM_registerMenuCommand` / `GM_unregisterMenuCommand` | `GM.registerMenuCommand` / `GM.unregisterMenuCommand` |
| HTTP | `GM_xmlhttpRequest` | `GM.xmlHttpRequest`（带 abort 的 Promise） |
| 文本复制 | `GM_setClipboard` | `GM.setClipboard` |
| 日志 | `GM_log` | `GM.log` |
| 应用内文字提示 | `GM_notification` | `GM.notification` |

GM 值按配置 ID + namespace + name 存入应用私有目录，与网页 localStorage 分离。支持 JSON 可序列化值和 undefined；每个脚本最多 1 MiB，键最多 512 字符。旧式 set/delete 立即修改 JS 内存快照，再异步原子落盘；现代 API 可 await 落盘完成。监听回调传入 `(key, oldValue, newValue, remote)`，remote 表示当前同脚本的其它 frame；不支持跨独立应用进程同步。导出配置不包含 GM 值；修改脚本名称/namespace 或换配置 ID 会使用新的存储空间。

样式/元素在 head 尚未创建时也可排队插入。资源 URL 返回 data URL，适用于 CSS/图片；不同浏览器 CSP 可能限制 data URL。菜单注册返回 ID，支持 options.id 更新；命令显示在悬浮菜单的 **脚本命令** 中；不实现桌面 accessKey 快捷键等选项。文本复制需要近期用户点击/键盘操作或点选原生脚本命令，最多 64 Ki 字符；不支持 HTML 或图片写入。

notification 目前是原生应用内文字 Toast，拒绝点击回调、图片或系统通知选项，不伪装成完整系统通知接口。

### GM HTTP

原生 HTTP 请求绕过网页 CORS，但必须同时满足 `@grant` 与 `@connect`。初始和**每次重定向**目标都检查；拒绝 file/账号密码 URL。发送配置 UA；非 anonymous 请求读写 WebView Cookie，跨 origin（协议/主机/端口）重定向移除用户填写的 Authorization/Origin/Referer。

支持 method/url/headers/data/timeout/anonymous/context，正文支持字符串、URLSearchParams、ArrayBuffer 和 TypedArray。响应支持 text/json/arraybuffer/blob/document，包含 status/statusText/finalUrl/responseHeaders/responseText/response/responseXML/readyState。

支持 onloadstart/onprogress/onreadystatechange/onload/onerror/onabort/ontimeout；现代 Promise 带 `.abort()`。HTTP 4xx/5xx 作为正常响应返回，网络/TLS/超时进入对应错误路径。进度和 readyState 为应用合成状态，不保证与桌面油猴事件频率一致。

不支持 stream、同步请求、FormData/Blob 正文、cookiePartition、显式 cookie/user/password、fetch 特殊行为、部分受保护请求头。请求正文最多 1 MiB，响应最多 4 MiB，同时最多 16 个请求，总超时上限 120 秒。Cookie 行为不是独立的浏览器请求隔离环境。

## 未实现功能与诊断

`GM_download`、`GM_cookie`、`GM_webRequest`、`GM_getTab`/`GM_saveTab`/`GM_getTabs`、独立标签页 `GM_openInTab`、扩展安装/自动更新、真正安全 sandbox 及系统通知并不支持。未知 grant 会写入警告；openInTab 明确抛错。不能宣称“100% 油猴兼容”。

悬浮菜单 → **脚本日志** 可查看准备后的运行时机、授权、运行结果、错误栈/行号、浏览器错误及 GM 请求失败。最多保留当前 WebView 的 100 条日志，不上传；刷新/切换后清空。`@match` 不符合的网址和 iframe 不执行脚本。

用户贴出的 grant-none 脚本会改为默认 document-idle，避免 head/body 为空时立即报错。它本身的普通 fetch 不会转换成 GM 请求，仍可能因为服务器 CORS、站点改版、未知全局变量或脚本自身逻辑而失败。

官方参考：[Tampermonkey 文档](https://www.tampermonkey.net/documentation.php)。
