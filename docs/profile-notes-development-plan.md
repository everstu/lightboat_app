# 配置备注功能开发方案（交给 DeepSeek）

请在当前轻舟 Android 项目中实现以下功能。项目使用 Kotlin、XML 布局和原生 View，配置由 `ConfigCodec` 编解码后保存在应用私有目录的 `config.json`。请沿用现有实现方式，在本地 `dev` 分支开发，保持当前 `versionCode`、`versionName`，不推送、不合并、不发布。

## 一、功能与交互约定

1. 每个配置新增一份可选的纯文本备注，和配置一起保存。新建默认留空，编辑回填已有内容，可以修改或清空。
2. 在新建、编辑共用表单的最后增加“配置备注（可选）”多行输入框，位置在现有“访问方式 / UA”区块之后、底部固定保存操作区域之前，输入框随表单滚动。
3. 配置管理中，每条配置的三点菜单增加“查看备注”，排在“删除”前。入口层级为：配置管理 → 该配置的 ⋮ → 查看备注 → 弹窗。
4. 查看弹窗标题为“配置备注”，正文完整展示备注，支持长按选择并复制选中文本，同时提供“复制全部”和“关闭”按钮。“复制全部”复制完整备注，保留原始换行、缩进和空格，复制后弹窗保留，便于继续阅读。
5. 三点菜单始终保留“查看备注”。没有备注时显示“暂无备注”，仅提供“关闭”；占位提示不可作为备注复制。
6. 长备注可以纵向滚动；横向长字符串自动换行；横屏、小屏和大字体下正文可阅读，关闭及复制按钮始终可达。
7. 备注不显示在配置卡片、切换配置列表或网页浏览界面。查看和复制备注不修改配置、不改变当前启动项、不重新加载网页。备注通过编辑配置修改。

## 二、文本与兼容约定

- 字段名使用 `note`，在 `Profile` 构造参数末尾新增 `val note: String = ""`，减少对现有位置参数调用的影响。
- 本方案采用单条备注最多 2000 个 UTF-16 code unit 的上限，与 Kotlin `String.length` 和 Android `LengthFilter` 的计数一致。可在输入框下显示当前长度及上限，达到上限时给予明确反馈。
- 允许中文、英文、emoji、换行、回车和制表符；拒绝这些例外之外的 ISO 控制字符。纯空白内容归一化为 `""`，有实际内容时保留原文，不对整段内容执行 `trim()`。
- 在 `AppConfig` 中集中定义 `MAX_PROFILE_NOTE_LENGTH` 及统一的备注校验/归一化函数，编辑保存和 JSON 导入复用同一规则。超长导入必须报错，不得静默截断。
- 只按普通文本输入、保存、展示和复制，不解析 HTML 或 Markdown，不自动识别链接。
- 新版读取旧配置时，缺少 `note` 或值为 JSON `null` 均按空字符串处理；存在且不是字符串时拒绝导入，避免把数字、布尔或对象自动转成备注。
- 编码时写出 `"note": "..."`，空备注写 `"note": ""`。继续使用现有 `version: 2`，这是可选字段扩展；旧 v1/v2 数据仍可读取。
- 保持当前 JSON 导入与 ZIP 内 `config.json` 的 1 MiB 限制，不因本功能扩大备份体积限制。

## 三、代码接入点

以下行号以编写方案时的代码为准，开发时按符号定位。

| 文件与位置 | 修改内容 |
| --- | --- |
| [AppConfig.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/AppConfig.kt:9) | 为 `Profile` 增加 `note`；集中定义长度上限及备注校验规则。确认各类 `copy`、排序和脚本库更新操作保留此字段。 |
| [ConfigCodec.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/ConfigCodec.kt:39) | `decode()` 读取、校验并传入备注；`encode()` 的逐配置字段输出增加备注。注意不要用会隐式转换类型的读取方法替代严格类型检查。 |
| [activity_main.xml](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/res/layout/activity_main.xml:124) | 在表单内部最后增加带标签的 `noteInput`，多行、顶部对齐，初始约 3 行高，沿用现有间距、颜色及输入框外观。若复用 `UrlField` 样式，必须覆盖其中的 URI 输入类型和单行属性。 |
| [MainActivity.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/MainActivity.kt:706) | `showEditor()` 初始化与回填；`saveEditor()` 校验输入并在新建 `Profile` 时用命名参数传入备注。新增查看弹窗方法，并在 `setupProfileList()` 接入菜单回调。补齐下面的草稿保存和恢复。 |
| [ProfileAdapter.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/ProfileAdapter.kt:81) | 增加查看备注回调，在现有 `PopupMenu` 中添加“查看备注”。弹窗交给 Activity 管理，Adapter 仅发出操作回调。 |
| [strings.xml](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/res/values/strings.xml:213) | 增加备注标签、输入提示、查看备注、暂无备注、复制全部及校验反馈文案。复用现有“关闭”“已复制”等通用文案。 |
| [README.md](C:/Users/WinPC/Desktop/new-project/app_view/README.md:28) 与 [config.example.json](C:/Users/WinPC/Desktop/new-project/app_view/config.example.json) | 简要补充填写和查看入口、字段默认值及长度限制，并在示例配置中展示 `note`。 |

表单、管理列表与查看弹窗按现有视觉风格实现，不引入新的 UI 框架或通用备注管理模块。

## 四、两个必须补齐的保存环节

**编辑保存时的完整对象重建。** 当前 `MainActivity.saveEditor()` 在约 775 行完整构造 `Profile(...)`。只给模型新增默认字段还不够，必须明确从 `noteInput` 读出并传入，否则编辑后保存会把原备注覆盖为空。`ConfigCodec.decode()` 也有完整构造点，需要同步传入读取的备注。

**未保存表单的恢复。** 当前表单草稿使用 Bundle 保存，脚本库大对象另由 `EditorDraftStore` 存储。请新增 `draftNote`：

- 在 [onSaveInstanceState()](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/MainActivity.kt:1515) 写入当前输入内容。
- 在 [编辑器恢复逻辑](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/MainActivity.kt:314) 中，在 `showEditor()` 回填保存数据之后恢复 `draftNote`，没有这个键时保留正常回填值。
- 备注只有最多 2000 个计数单位，沿用普通表单草稿路径即可，无需改造 `EditorDraftStore`。
- 扫码返回、切换平台、打开脚本选择窗口后，备注输入必须保留。取消编辑应丢弃未保存修改，随后新建配置不能残留上一份备注。
- 项目已自行处理普通横竖屏切换，不要把横竖屏切换等同于必然重建 Activity；草稿恢复应通过真正的 Activity recreate 场景核验。

## 五、弹窗实现建议

使用原生 `AlertDialog`，自定义正文为 `ScrollView` 内的 `TextView`，设置 `textIsSelectable=true`，通过普通文本属性绑定内容。正文区域应有合理高度上限，避免长内容挤走底部按钮。横屏、窗口尺寸改变后的限高更新可参考 [ResponsiveDialog.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/ResponsiveDialog.kt:8)，优先复用项目已有机制。

弹窗统一交由 [showNativeDialog()](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/MainActivity.kt:414) 展示和清理，保持系统栏及现有弹窗生命周期行为。复制复用 [applyScanResult()](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/MainActivity.kt:526) 中 `ClipboardManager` / `ClipData.newPlainText` 的原生实现与版本适配；不需要新增权限或 WebView 接口。

注意标准 `AlertDialog` 按钮点击后会自动关闭；若用“复制全部”按钮，应在弹窗显示后设置对应点击监听，使复制成功后保留弹窗。备注为空时仅展示空状态和关闭按钮。

## 六、备份处理

本地 `ConfigStore`、ZIP 备份及 WebDAV 使用同一份配置编解码：`BackupRepository.snapshot()` 调用 `ConfigCodec.encode()`，`BackupArchive` 读取配置时调用 `ConfigCodec.decode()`。本功能预期只扩展模型与 Codec，无需增加独立备注文件、数据库、ZIP 条目或 WebDAV 接口。

开发完成后必须用包含备注的配置验证完整往返，确认不是只在内存里保存成功。测试与验收还要覆盖修改收藏、分类、排序或脚本库后备注仍在。

## 七、实施顺序与验证

1. 先实现模型和 Codec，补充备注 JSON 往返、旧配置缺省与非法输入测试。
2. 再实现表单输入、编辑回填、保存及 Bundle 草稿恢复。
3. 最后接入三点菜单、可选择正文弹窗和复制全部，补充说明文档并完成回归。

必要的自动化测试可接入现有 `AppConfigTest.kt`、`ProfileOrganizationTest.kt` 和 `BackupArchiveTest.kt`，重点覆盖数据正确性。UI 测试可借鉴 `ScriptLibraryUiTest.kt` 的管理页启动、表单输入和按钮点击方式；没有设备条件的操作如实记为待真机验收。

在具备 JDK 17 和 Android SDK 的环境中，按现有工作流执行：

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug
```

验收清单：

- [ ] 不填备注可以新建、保存并打开；表单仍使用原有保存与打开语义。
- [ ] 填写中文、emoji、多行、制表符、缩进以及 `<b>文本</b>` 后保存，编辑回填、弹窗展示和复制均保留原文；标签作为文本显示。
- [ ] 修改备注、清空备注后保存，再次进入及重启应用后结果正确。
- [ ] 空备注仍可从三点菜单查看，显示“暂无备注”，没有复制占位提示的按钮。
- [ ] 弹窗可复制选中文本；“复制全部”得到完整原文，复制后保持弹窗，关闭后返回原管理页。
- [ ] 表单输入上限与保存/导入校验一致；导入超长或非字符串备注失败且保留当前有效配置。包含正常换行、回车、制表符的备注不会被误判为非法控制字符。
- [ ] 取消编辑不写盘；扫码返回、平台切换、脚本选择和 Activity recreate 保留未保存备注，新建另一配置不串值。
- [ ] 旧 v1/v2 配置和没有备注的旧备份可读取；包含备注的 JSON 编解码、本地 ZIP 备份恢复及 WebDAV 上传恢复都能保留备注。
- [ ] 收藏、分类、拖动排序、置顶、脚本库更新不丢备注，也不改变原有启动项语义。
- [ ] 小屏、横屏、大字体和长段文本下输入/阅读/复制/关闭正常，按钮可达；TalkBack 能辨识标签和操作。
- [ ] 配置卡片和切换配置列表没有新增备注正文；查看/复制不重新创建或刷新当前网页。

交付时说明修改文件、文本规则、已执行的测试及结果，并明确列出尚未执行的设备验收项。
