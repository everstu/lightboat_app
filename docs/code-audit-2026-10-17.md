# 代码审查与修复记录 · 2026-10-17

目标：配置管理和切换配置共用并持久保存收藏筛选状态，随后审查当前完整项目，修复有明确触发条件的问题。基于本地 `dev` 分支的 `73d5538`，本轮不修改版本号。

## 收藏筛选

- 将分类和收藏筛选统一作为 `ProfileFilter` 传递，两个入口修改后立即更新同一份本机偏好。
- 页面重建优先使用已保存的实例状态，包括显式关闭；全新启动从 SharedPreferences 恢复。
- 导入配置保留收藏筛选。分类 ID 不再存在时仅恢复全部分类，仍保留收藏开关。
- 重置为全部配置、关闭收藏筛选同样保存；配置本身的收藏属性、排序、启动项及导出格式不变。

`ProfileFilterPersistenceTest` 通过真实原生按钮和列表验证开启、关闭、两个入口的共享、重新打开、Activity 重建、分类失效、切换弹窗的空结果重置，以及在线导入前后的状态。

## 全盘范围

| 范围 | 核验内容 |
| --- | --- |
| 配置与界面 | 模型/JSON、分类、收藏、导入导出、拖动排序、编辑及保存、两个配置入口 |
| WebView 与脚本 | 创建/导航/错误/销毁、脚本准备与权限、GM 存储/网络/分片/超时、历史页面恢复 |
| 媒体 | 直接媒体代理、HLS、Range、UA/Cookie/重定向、MSE/blob 保持浏览器原生路径、全屏及资源释放 |
| 原生功能 | 日志缓存与界面、后台恢复、筛选/清空、悬浮入口、扫码、布局和权限 |
| 工程 | Manifest、Gradle、资源、现有构建/发布工作流、固定签名与发布版本校验 |

四个分区分别进行了只读审查，主线程复核关键原文、决定修改并完成测试。

## 额外修复

1. **日志的凭据字段没有完整脱敏。** 原模式遇到空格、逗号或分号便结束匹配，`Authorization: Bearer ...` 及多段 Cookie 会留下后续值。现在隐藏完整头字段所在行，支持 Authorization、Proxy-Authorization、Cookie、Set-Cookie 和带引号的字段。普通状态、URL 路径及查询参数名仍保留；崩溃摘要在写入本机偏好前也走同一脱敏逻辑。两项新增单元测试先复现失败，再验证修复。
2. **恢复历史页面时，GM 监听收到不真实的值变更。** 原先先清空脚本侧快照，随后每个恢复分片都直接通知监听器，旧值丢失，未改变的值也触发回调。现在保留当前值，将恢复分片暂存，完整恢复后统一更新，再仅通知实际新增、删除或改变的键。比较 JSON 值时不重新序列化大字符串；脚本仍只执行一次，菜单仍重新注册。新增 Node 回归验证不暴露半份快照、旧值/新值、删除通知和未改变的大值不回调。

## 已复核、未作额外修改的疑点

- 首次 GM 存储父目录由 Android `AtomicFile.startWrite()` 建立；新增独立空目录的 Android 回归验证首次写入和新 store 冷读成功。
- 资源完整性校验失败阻止准备，是现有明确设计；普通可选资源下载失败才允许空资源继续运行，保留既有测试。
- 日志 Dialog 的停止不同于 Activity 进入后台。真实后台/前台测试验证继续刷新、清空、再收新日志均正常，未修改其 executor 生命周期。
- 媒体使用当前两参数 CookieManager 接口时，真实 CookieManager 连续十次 Set-Cookie + 302 重定向均使用新值，未加入推测性的 Cookie 缓存或等待。

## 前一阶段验证与边界（历史记录）

- `node --test tests/*.test.cjs`：53 项通过（另含原生交互脚本内部的 4 项断言）。
- `gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`：通过；71 项 Kotlin 单元测试通过。
- Lint：0 Error，72 Warning。剩余为现有的依赖、WebView 特性检查、布局/无障碍、资源等提示；不将它们描述为已经全部消除。
- 固定 debug 证书校验通过；`v1.0.3` 与生成 APK 的公开版本一致。
- Android 15 / WebView 124 独立只读模拟器实例执行全部设备回归：`adb -s emulator-5580 shell am instrument -w com.lightboat.app.test/androidx.test.runner.AndroidJUnitRunner`，20 项全部通过。覆盖筛选持久化/导入、日志后台恢复、GM 大值与导航、Cookie 重定向及可交互 HTTP 错误页。
- 额外使用真实触摸操作切换收藏筛选，分别强制结束 App 进程后重新启动，确认开启/关闭状态及对应的可见配置列表都从磁盘恢复；测试结束后还原测试前的配置与偏好。

设备测试仍需显式执行，现有 GitHub Actions 不自动启动模拟器。运行结果只覆盖已执行的系统/WebView 环境，不代表任意网站、媒体格式或设备均已实测。扫码相机、实际视频内容与其它设备保留原有验收要求。

## 本次优化与重新验证

本节记录随后回归审查发现的可优化点及当前工作区结果；上面的数字为前一阶段历史结果。本次仍在本地 `dev` 开发，版本保持 `1.0.3 (35)`，不提交、不合并、不推送。

### 已完成

- **GM 队列拥堵保护。** 共用单线程队列仍最多等待 64 项，提交失败返回明确的繁忙错误，绝不把磁盘或等待 JS 确认的工作移到调用线程。值广播在同一次写入任务中、落盘后和确认前执行，避免二次排队被拒绝或使旧通知晚于后续写入。取消、销毁及超时的临时文件清理由共用 Handler 保留任务，队列满时以单一回调重试，不受某个 host 关闭影响。
- **日志磁盘写入和读取分开。** 普通日志从 250ms 改为约 2 秒合并保存，磁盘锁只负责串行写入；记录/读取锁内只取字符串引用快照。无新增修订时不写盘，启动清空也使用原子写入。清空及崩溃仍立即保存，清空不能被较早快照覆盖。增量读取只取新增尾部，无新日志时直接返回，避免每 500ms 扫描完整 2000 条。内存、文件和界面保留上限仍为最新 2000 条；界面刷新频率仍为 500ms。
- **悬浮圆形布局。** 圆面固定为 78×78dp，折叠缩放为 39dp，并明确关闭内部裁切；保留 48dp 触摸目标、85% 不透明度及原有展开/外部点击收起行为。
- **脚本编辑窗口。** 提取独立 `ScriptEditorDialog`，沿用圆角背景和主次按钮。父脚本库窗口旋转时同步更新子窗口宽度；表单滚动、操作栏固定，横屏键盘出现时缩小上下留白并使用实际可用窗口高度。浮窗的 IME insets 可能在到达内容之前被消费，因此使用可见显示区域判断高度；显式移除 AppCompat 标题区域，防止隐藏区域挤压输入框。输入值、校验和草稿保存边界不变。
- **文档。** README 中排序入口改为配置编辑页，更新圆形入口和日志保存间隔；历史测试数字与当前验证分开。没有为消除提示而批量升级依赖或重构媒体链路。

### 当前验证

- `node --test tests/*.test.cjs`：54 项通过（原生交互脚本另有内部 4 项断言）。
- Kotlin 单元测试：78 项通过，含新增的队列饱和拒绝、执行顺序和恢复接收任务测试。
- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`：通过；Lint 为 0 Error、82 Warning，保留现有依赖/特性检查/资源等提示。
- Android 15 / WebView `124.0.6367.219`，使用独立 `-read-only -no-snapshot` 模拟器 `emulator-5580`；按测试类分别启动进程，当前 APK 的 19 项全部通过：`ScriptLibraryUiTest` 7、`DebugLogFilteringTest` 5、`GmLargeTransferTest` 4、`UserScriptNavigationTest` 2、`MediaCookieRedirectTest` 1。
- 新增设备断言：折叠圆面的实际可见宽高；横屏且键盘确实可见时输入框/保存按钮均完整可见，旋转后输入保持并能保存；并发 flush/clear 不恢复旧文件记录；两个 frame 同时写同一键后内存快照与磁盘一致。对界面另做截图复核，没有仅以可点击或可获得焦点作为通过标准。

本机构建过程中 Temurin 17 C2 编译线程发生两次 `jvm.dll` 原生访问异常；最后一次构建临时使用 `--max-workers=2` 与 `-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=384m` 通过。参数仅用于本机命令，没有写入项目配置，也未将 JVM 崩溃归因于应用代码。

本轮没有执行全部其它设备测试或外部网站视频实播，不把上述 19 项描述为任意网站/设备的完整验收。

## Apple UA 与验证页排查

用户报告同一地址、无启用脚本时，Android UA 能完成验证，Apple UA 会再次验证。本次核验未修改运行时代码、配置预设或版本号。

- `MobileUserAgent.kt`、`BrowserScriptLoader.kt` 相对 `v1.0.3` 没有变化；最近的多脚本/批量添加改动没有改 UA、UA-CH、Cookie 设置或正常页面导航回调。无脚本时不会建立用户脚本组或注册初始化探针。
- 真实 WebView 请求核验发现：Apple 预设发送 iPhone Safari UA，同时生成 `Sec-CH-UA: "Safari";v="18"` 与 `Sec-CH-UA-Platform: "iOS"`，而页面 `navigator.userAgentData` 被覆盖为 undefined。这是旧预设中的身份不一致，不能据此断言服务端的具体拒绝原因。
- 对照的原生 WebView 仅设置同一 Safari UA、不调用 metadata API，仍发送 Chromium/Android WebView 的低熵 UA-CH；页面也是 Android navigator。因此简单移除 metadata 覆盖并不能得到真实 Safari 环境，没有将这种改动作为修复提交。
- 本机模拟器访问报告地址时，Apple 与 Android 两种配置均停留在 Managed Challenge 页，没有完成用户手机上“通过后再次验证”的完整对照。因此没有声称已复现或修复真实 Cloudflare 验证循环，也没有改写请求、绕过验证或增加特定站点逻辑。
- 增补 `VisibleNavigationTest.appleAndAndroidProfilesWithoutScriptsKeepVerificationCookiesAndTheirWebView`：Apple/Android 无脚本配置在本地 403 验证页完成 Cookie 写入，再连续导航三次，验证 Cookie 与请求 UA 保留且 WebView 实例不变。该测试及已有页面可见性/403/连接失败回归共 4 项，在 Android 15 / WebView 124 上通过。

## Windows UA 后续核验与修复

用户随后报告同一网站使用 Windows UA 也无法完成验证。这个对照不能用 Safari 特有的内核差异单独解释，也不能视为服务器拒绝原因已经确认。

- 新增真实 WebView 设备测试 `WindowsIdentityTest`，不启用用户脚本。修复前两个用例均复现失败：`navigator.userAgentData` 的 getter 每次构造新对象；手写的 `getHighEntropyValues()` 忽略请求字段，总是返回全部高熵字段。生产配置在已经设置原生 metadata 后仍用普通 JS 对象替代了原生 `NavigatorUAData`。
- 修复 `MobileUserAgent.navigatorIdentityScript`：当 WebView 已提供 Windows/desktop 的原生 UA-CH 时保留该原生对象及 getter，由同一原生 metadata 生成 HTTP 请求头和 JS 数据；旧 WebView 的兼容对象只构造一次，`toJSON()` 仅给出低熵字段，`getHighEntropyValues()` 仅追加请求且已知的字段。Android 分支与 Apple 预设值保持原有行为，媒体通道和用户脚本未改。
- 设备验证 Windows 原生对象类型与稳定性、请求头/JS 的 brands/platform/mobile 一致性、高熵字段选择，以及旧接口兼容分支。无脚本的验证 Cookie 与 WebView 保留回归扩展到全部三个平台。
- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 通过。Android 15 / WebView 124 分别启动 instrumentation 运行 `WindowsIdentityTest` 2 项、`VisibleNavigationTest` 4 项、`MultiScriptTest` 10 项、`MediaCookieRedirectTest` 1 项，均通过。版本仍为 1.0.3（35），仅本地 dev，未合并或推送。
- 这是对已复现浏览器接口兼容缺陷的修复，尚未证明该网站真实 Cloudflare 验证恢复，也不代表 Apple UA 的问题已经解决。没有增加站点白名单、自动换 UA、清除验证 Cookie 或修改媒体请求逻辑。
