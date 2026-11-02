# 脚本引用数量与分类删除开发方案（交给 DeepSeek）

请在轻舟 Android 项目中实现本文约定的两个功能：脚本库显示每个脚本被多少个配置引用；在现有分类编辑窗口提供分类删除入口。本文已根据现有源码核对接入点，可作为开发任务直接执行。

核对日期：2026-10-22。当前分支：`dev`；核对基线：`65ce189b543b24fcc26965d2f82345875f152a8a`。行号仅用于定位，开发时以符号为准。当前 `versionName = 1.0.3`、`versionCode = 35`，均保持不变。

项目使用 Kotlin、XML、原生 View、View Binding 和 AppCompat。开始前阅读 `AGENTS.md`、`CONTEXT.md` 及相关模块文档，检查分支与未提交修改；在现有 `dev` 分支完成开发，不重置他人工作。本任务不包含提交、推送、合并或发布。

## 一、确定的产品方案

| 需求 | 本次实现约定 |
| --- | --- |
| 脚本引用数量 | 每个脚本名称旁展示“`N 个配置引用`”胶囊；大于 0 为浅绿底、绿色图标和文字，0 为浅灰底、灰色图标和文字。 |
| 引用数量口径 | 统计所有**已保存配置**，停用引用也计入；一个配置对同一脚本只计一次；未保存草稿不计入。 |
| 分类删除入口 | 将配置管理页右上角“编辑分类”改为“管理分类”，在现有窗口内保留改名功能，增加清晰可见的“删除当前分类”按钮。 |
| 删除分类的结果 | 删除分类记录；原来属于它的配置全部移至“未分类”。配置本身及其内容保留。 |
| 删除当前筛选分类 | 分类筛选恢复“全部分类”，保留收藏筛选，并更新本机保存的筛选偏好。 |

两项功能均沿用现有配置结构，不增加 JSON 字段、不升级 `version: 2`、不增加数据库或 UI 框架。网页浏览、媒体播放和脚本实际执行路径不属于本次改动范围。

## 二、脚本库：按参考图实现引用数量

### 2.1 视觉基准

用户提供的原图已随本方案保存：

![脚本库引用数量的用户设计参考](C:/Users/WinPC/Desktop/new-project/app_view/docs/design-references/script-library-usage-reference-2026-10-22.png)

原图中的 `3 / 5 / 0` 是设计示例，运行时必须显示真实数据。沿用图中的浅色窗口、白色圆角卡片、名称旁的细描边胶囊和右侧三点菜单。截图中的徽标文字发生了断行；实现时应完整显示为单行，避免最后一个“用”字另起一行。参考图像素尺寸不直接作为 Android dp 尺寸。

正常宽度的条目结构：

```text
┌───────────────────────────────────────────┐
│ 91p.user.js  [链图标  3 个配置引用]      ⋮ │
│ https://file.example/91p.user.js            │
└───────────────────────────────────────────┘
```

窄屏或大字体放不下时，整个徽标换到下一行：

```text
┌───────────────────────────────┐
│ 很长的脚本名称……           ⋮ │
│ [链图标  3 个配置引用]         │
│ https://file.example/…         │
└───────────────────────────────┘
```

### 2.2 尺寸与样式

| 元素 | 实现要求 |
| --- | --- |
| 脚本卡片 | 复用 `bg_library_script`，保留现有白底、14dp 圆角、1dp 描边及选中态。 |
| 名称 | 沿用现有约 15sp 的中等字重；标题行单行，长名称末尾省略。完整名称仍用于无障碍描述及编辑窗口。 |
| 徽标 | `wrap_content` 宽高；最小高度 24dp，左右内边距约 8dp、上下约 3dp，圆角胶囊，1dp 描边。 |
| 徽标文字 | 12sp，单行完整显示，不使用固定窄宽度，不省略数字，不通过自动缩小字号挤进一行。 |
| 图标 | 约 14dp 的原生 VectorDrawable，图文间距 4dp；非零使用链条，零引用使用灰色未关联图标。不要用 emoji 代替图标。 |
| 非零颜色 | 文字与图标复用 `accent`；底色复用 `config_soft`；新增局部浅绿描边色，建议 `#BDD9CC`。 |
| 零引用颜色 | 文字与图标复用 `muted`；新增局部浅灰底 `#F2F4F6`、浅灰描边 `#DCE1E7`。 |
| 更多按钮 | 保留最少 48dp 触摸区域，固定在卡片右侧，不被徽标挤出或遮挡。 |
| 地址 | 独立次级信息区，沿用现有 12sp、`muted`；为贴近截图可显示最多 2 行，超出末尾省略。保留现有隐藏查询和片段的展示规则，不改实际保存的 URL。 |

不要为了增加徽标而修改全局主题色、其它卡片样式或窗口整体配色。零引用是信息状态，不能把脚本条目设为禁用；它仍然可以编辑、选择和删除。

### 2.3 布局与适配

继续在 `item_library_script.xml` 的原生 View 布局内实现：名称、徽标和三点菜单分配独立空间。正常宽度时徽标紧随名称，剩余空白留在徽标与三点菜单之间。

- 按条目实际可用宽度和当前字体测量布局，不按手机型号或截图宽度写死。
- 先为三点菜单预留 48dp。若余下宽度能同时容纳徽标、间距以及一段可辨识的名称，则同排展示；名称可末尾省略。
- 名称的最少可辨识宽度可按当前字号下约 4 个汉字的绘制宽度测量，选择模式还要计入复选框与内边距。
- 如果上述空间不足，把**整个徽标**放在标题下方、地址上方，并使用卡片内容的完整宽度。这一行不再为上一行的三点菜单预留空间。
- 徽标自身始终单行，不允许“配 / 置 / 引 / 用”逐字换行。高度随字体增长，不能固定为 24dp 后裁切文字。
- 配置编辑器的“选择脚本”模式要保留复选框；布局测量必须包含它，不能只验管理模式。
- 宽度改变后更新布局；注意本项目处理普通横竖屏变化时不一定重建 Activity。可复用 `ResponsiveDialog.updateLayout()` 与条目实际布局尺寸变化触发测量。
- 沿用现有依赖实现，不为这个徽标引入 Compose、Material Chip、Flexbox 或新的通用布局框架。

徽标仅展示信息，不增加点击跳转功能，不占用条目的选择/编辑事件。装饰图标不单独进入无障碍焦点。为徽标提供“`N 个已保存配置引用`”的完整描述，保持现有脚本名称、选择状态和更多操作可被识别。

### 2.4 引用数量的准确口径

统计来源固定为对话框收到的 `config.profiles`，按共享脚本 ID 关联：

```kotlin
fun scriptReferenceCount(scriptId: String): Int =
    profiles.count { profile ->
        profile.scripts.any { reference -> reference.scriptId == scriptId }
    }
```

建议将此方法加入 `AppConfig`，同时用于徽标和已有“删除共享脚本”的确认提示，避免两处统计逻辑日后不一致。这里统计配置数，不能改成启用引用数、脚本执行次数、网站匹配数或当前可见配置数。

| 场景 | 预期数量 |
| --- | --- |
| 3 个配置引用同一脚本，其中 1 个停用 | 显示 3，使用绿色样式。 |
| 只在某个未保存的新配置中勾选脚本 | 仍显示原来的已保存数量；原来无人引用则为 0。 |
| 编辑已有配置，草稿取消或新增脚本引用 | 本次保存前仍按该配置原来的已保存引用统计。 |
| 最终保存配置后重新打开脚本库 | 按新的已保存配置重新统计。 |
| 当前只显示某分类或收藏配置 | 数量仍覆盖全部已保存配置，不随筛选变小。 |
| 修改脚本名称或地址，脚本 ID 不变 | 引用数量不变。 |
| 脚本库新增尚无人引用的脚本 | 显示 0，使用灰色样式。 |
| 从脚本库草稿删除条目 | 条目隐藏；其它脚本的数量仍按已保存配置统计。取消窗口不落盘。 |

现有规模最多 50 个配置、每配置 16 个引用、脚本库 100 个定义，绑定条目时直接调用此纯函数即可。不增加持久化计数、全局缓存、后台任务或网络请求。

### 2.5 两种入口与文案

现有 `ScriptLibraryDialog` 同时承载管理模式和选择模式，两处都展示相同口径的徽标：

1. `MainActivity.showScriptLibrary()` 传入完整已保存 `config`，保存时写脚本库。
2. `MainActivity.chooseScripts()` 传入 `config.copy(scriptLibrary = draftScriptLibrary)` 和 `draftScripts`。这里只替换了库草稿，`profiles` 仍是已保存数据，恰好符合计数口径。

不要把 `draftScripts` 临时合并进 `profiles` 计算，也不要为计数提前保存当前配置。

建议字符串资源：

- 新增 `script_usage_short`：`%1$d 个配置引用`，用于徽标可见文字。
- 复用已有 `script_usage`：`%1$d 个已保存配置引用`，用于完整描述。
- 新增 `script_usage_help`：`引用数仅统计已保存配置，包含停用引用。`

在现有帮助区为两种模式补充上述一句说明，不增加独立大段说明面板。`onBindViewHolder()` 每次绑定时完整更新数字、文字色、图标及背景，防止 RecyclerView 复用后出现“0 的文字配绿色背景”等混用。

## 三、分类删除：入口与交互

### 3.1 入口

入口路径固定为：**配置管理 → 右上角“管理分类” → 选择分类 → “删除当前分类”**。

将原来的“编辑分类”文字改为“管理分类”，仍使用现有位置。保留现有 `renameCategories` 控件 ID 和窗口/布局文件名即可，避免为命名一致性改动大量引用。窗口中只列出真实分类，不列出“全部分类”“未分类”这两个虚拟筛选项。

- 当前筛选为真实分类时，默认选中它；其它情况默认选中注册表中的第一个分类，沿用原有顺序。
- 只要分类注册表非空，即便没有任何配置，也应能进入管理并删除空分类。
- 没有真实分类时隐藏该入口，沿用现有行为。
- 本次不新增分类管理页面、长按入口、分类拖动排序或批量删除功能。

### 3.2 管理窗口

复用现有 `CategoryRenameDialog` 和圆角窗口样式，结构如下：

```text
管理分类                              ×
选择分类后，可修改名称或删除分类。

选择分类
[ 工作                            ▾ ]

分类名称
[ 工作                              ]
该分类下有 3 个配置，修改名称会同步更新。

────────────────────────────────────
[          删除当前分类              ]  ← 红色文字、细描边
删除后，配置将保留并移至“未分类”。

[       取消       ] [    保存名称    ]
```

- 删除区放在名称输入与引用说明之后，留约 20dp 间距，可用 1dp 分隔线划开。
- “删除当前分类”使用通栏、最小 48dp 触摸高度的描边按钮，文字/描边复用项目 `error` 色；视觉强度低于绿色主按钮。
- 保留底部两个操作，不把“取消 / 删除 / 保存”硬塞进同一行。保存按钮可见文案改为“保存名称”，明确它只负责改名。
- 标题、关闭按钮、底部操作始终可达；正文可滚动。新增删除区后，`updateLayout()` 应复用 `DialogLayout.apply()` 按可用高度限高，而不只设置宽度。
- 软键盘出现时继续避让；点击删除入口时先收起键盘，确保确认内容和按钮可见。按钮高度随大字体增长，避免固定高度裁字。
- 切换分类仍按原有行为载入所选分类的已保存名称和配置数。

### 3.3 删除确认

点击“删除当前分类”后显示二次确认，不能点击入口就直接删除。

有配置时：

```text
删除分类

确定删除分类“工作”吗？
该分类下的 3 个配置将移至“未分类”，配置内容会保留。

                     [取消] [删除分类]
```

空分类时：

```text
删除分类

分类“工作”下暂无配置，确定删除此分类吗？

                     [取消] [删除分类]
```

确认按钮使用红色文字。确认内容使用当前选中分类的**已保存名称**和全部关联配置数；输入框里尚未保存的新名称不参与删除，也不先执行改名。

行为约定：

1. 取消确认、按返回键或点击确认窗口外部，只关闭确认窗口，回到管理分类窗口，保留其中未保存的输入。
2. 确认删除成功后，立即落盘，关闭确认窗口和管理窗口，刷新配置列表并显示成功提示；不需要再点“保存名称”。
3. 删除有配置的分类后，提示“分类已删除，N 个配置已移至未分类”；空分类提示“分类已删除”。
4. 保存失败时保持原配置、原筛选和偏好，窗口保留，显示项目已有操作失败提示，不提示删除成功。
5. 删除最后一个分类也按相同规则处理，所有相关配置保留；成功返回后隐藏管理分类入口。
6. 取消或关闭管理窗口不保存输入的名称；已经确认并成功落盘的删除不会再被“取消”撤销，因此成功后直接关闭两层窗口。

### 3.4 确认窗口的生命周期

`MainActivity.showNativeDialog()` 会先关闭当前 `nativeDialog`。因此，**不要通过它打开分类删除的子确认窗口**，否则管理窗口会被提前关闭。

参考 `ScriptLibraryDialog.showEditor()` 的局部子窗口管理方式：由 `CategoryRenameDialog` 持有确认窗口引用，关闭时清空；父窗口停止或销毁时关闭子窗口。确认窗口取消不能触发删除；Activity 重建也不能自动确认删除。

标准 `AlertDialog` 的正按钮默认点击即关闭。应在显示后接管正按钮监听，仅当删除回调返回 `true` 才关闭两层窗口；返回 `false` 时保留窗口供用户重试或取消。子确认窗口也需适应宽度与大字体，长分类名可以换行，按钮不能被挤出屏幕。

## 四、分类删除的数据规则与保存顺序

### 4.1 模型方法

在 `AppConfig` 增加一个职责明确的纯方法，与现有 `renameCategory()` 相邻放置：

```kotlin
fun removeCategory(id: String): AppConfig {
    require(categories.containsKey(id)) { "分类不存在" }
    return copy(
        categories = categories - id,
        profiles = profiles.map { profile ->
            if (profile.categoryId == id) profile.copy(categoryId = null)
            else profile
        }
    )
}
```

删除分类记录和解除配置归属必须形成同一份 `AppConfig`，一次保存。禁止只删 map 项而留下悬空的 `categoryId`；现有 `ConfigCodec` 会拒绝这样的配置。

必须保持：配置数量、配置 ID、原有顺序、`lastProfileId`、网址、收藏、备注、UA、平台、脚本引用顺序与启停状态，以及脚本库原样保留。不要调用“删除配置”的 `remove()`，不要重建不完整的 `Profile(...)`。

未知分类 ID 采用与改名一致的策略：抛出 `IllegalArgumentException`，不产生部分修改；UI 捕获后反馈错误，不崩溃。

### 4.2 Profile 与筛选中的空值不能混淆

| 所在位置 | 值 | 含义 |
| --- | --- | --- |
| `Profile.categoryId` | `null` | 该配置未分类。 |
| `ProfileFilter.categoryId` | `null` | 查看全部分类。 |
| `ProfileFilter.categoryId` | `""` | 只看未分类。 |

删除归属时必须写 `Profile.categoryId = null`，不能写空字符串。删除当前筛选分类后返回“全部分类”，不自动切换到“未分类”。原来已经筛选“未分类”时则保持它。

### 4.3 MainActivity 的职责

保留现有改名保存回调，在 `CategoryRenameDialog` 额外增加按分类 ID 删除的回调，例如 `deleteCategory: (String) -> Boolean`。由 Activity 负责保存、同步筛选和刷新，窗口只负责交互，不直接写配置或偏好。

删除回调应使用 Activity 当前的完整 `config` 生成结果；执行顺序：

```text
确认目标分类仍存在，取得它的关联配置数
→ next = config.removeCategory(id)
→ persist(next)
  ├─ false：返回失败，后续步骤不执行
  └─ true：rememberProfileFilter(profileFilter)
           → renderProfiles()
           → 成功提示
           → 返回成功，窗口关闭
```

`persist()` 已有 Boolean 结果，并且先 `store.save(next)`，成功后才更新内存 `config`，应直接沿用。`showCategoryRename()` 入口已有的 `commitProfileOrder()` 保留，避免打开窗口前尚未提交的拖动排序丢失。

现有 `renderProfiles()` 只在内存调用 `profileFilter.resolve(config)`，不会把结果写回偏好。因此删除成功后必须显式调用 `rememberProfileFilter(profileFilter)`：

- 删除当前筛选分类：清除该分类 ID，切换到全部分类，`favoritesOnly` 保持原值。
- 删除其它分类：现有分类筛选保持，`favoritesOnly` 保持。
- 当前为全部分类或未分类：保持原筛选。
- 管理页、切换配置窗口、重启后的状态，以及备份中的筛选偏好必须一致。

分类 map 和配置归属由 `ConfigStore` 的 `AtomicFile` 一次提交；本机筛选偏好在成功后更新。两者不是跨文件事务，若恰在两步之间进程终止，下一次启动仍由现有筛选 `resolve()` 兜底归一化，无需为此增加新的事务系统。

分类删除只改变组织信息，不调用 `openProfile()`、`loadUrl()` 或刷新浏览器。配置的脚本引用没有变化，因此脚本库引用数也不应因删除分类而改变。

### 4.4 编辑草稿与备份兼容

- 分类管理入口仍只出现在配置管理页，不扩展到配置编辑器；无需建立额外的跨窗口分类草稿同步系统。
- 编辑器的分类草稿使用名称文本；保留现有“保存时按名称复用或新建分类”的行为。以后再次输入已删除分类的同名名称并保存，应创建一个新的分类 ID，不恢复旧 ID。
- 删除后的标准 v2 JSON 里不再有该分类 map 项，原关联配置的 `categoryId` 为 `null`；现有 Codec 可直接往返，不需要迁移。
- 本地 ZIP 与 WebDAV 共用该 Codec，不增加备份条目或独立分类文件。
- 恢复删除前的有效备份可以恢复原分类，这是整份配置恢复的正常语义。只有“引用的分类 ID 在该备份自身 map 中不存在”的非法数据才继续被拒绝。

## 五、代码接入清单

以下为核对时的文件与符号；按最终实现修改必要位置，未列为修改项的核心路径不顺带调整。

| 文件 / 符号 | 需要完成的内容 |
| --- | --- |
| [AppConfig.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/AppConfig.kt:52)，`renameCategory` 附近 | 增加 `removeCategory()`；增加 `scriptReferenceCount()`，供展示及脚本删除确认共用。 |
| [ScriptLibraryDialog.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/ScriptLibraryDialog.kt:68)，`remove`、`ScriptAdapter.onBindViewHolder` | 删除提示改用统一计数；绑定徽标文字、图标、背景与完整描述；两种入口同口径，处理条目回收及尺寸变化。 |
| [item_library_script.xml](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/res/layout/item_library_script.xml:6) | 新增 `libraryScriptUsage` 等必要控件/容器，实现同排及整枚换行；保持复选框、名称、URL 和更多操作。 |
| [CategoryRenameDialog.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/CategoryRenameDialog.kt:12) | 升级为管理交互；新增删除回调及确认子窗口管理；失败不关闭；保留改名校验；补充高度限制。 |
| [dialog_rename_category.xml](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/res/layout/dialog_rename_category.xml:23) | 增加删除区、说明和 `deleteCategory` 按钮；保存文案改为“保存名称”；正文滚动、操作可达。 |
| [MainActivity.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/MainActivity.kt:627)，`showCategoryRename` | 绑定删除回调：纯模型变换、`persist` 成功判断、`rememberProfileFilter`、列表刷新及提示。保留现有入口排序提交。 |
| [activity_main.xml](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/res/layout/activity_main.xml:186) | 入口显示“管理分类”；保留现有 ID 和位置。 |
| [strings.xml](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/res/values/strings.xml:153) | 新增短计数、帮助说明、管理分类、删除确认/空分类确认/成功文案；复用现有完整计数及通用取消/关闭/错误文案。 |
| [colors.xml](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/res/values/colors.xml:3)、[styles.xml](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/res/values/styles.xml:40) | 只增加局部徽标颜色/样式及必要的删除按钮样式，复用已有 accent、muted、error。 |
| [drawable 目录](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/res/drawable) | 增加局部徽标背景、链条/未关联矢量图标。背景可以用独立状态选择器，但不能误用卡片自身的选中状态。 |
| [ResponsiveDialog.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/ResponsiveDialog.kt:14) | 复用 `DialogLayout` 的测量和限高；预期不需要修改全局工具实现。 |
| [ProfileFilter.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/ProfileFilter.kt:15)、[MainActivity.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/main/java/com/lightboat/app/MainActivity.kt:592) | 复用 `resolve` 和 `rememberProfileFilter`，不增加第二套筛选偏好。 |
| [README.md](C:/Users/WinPC/Desktop/new-project/app_view/README.md:31)、[multiple-scripts.md](C:/Users/WinPC/Desktop/new-project/app_view/docs/multiple-scripts.md:13) | 补充计数口径、管理分类入口、删除转未分类及筛选变化的简短说明。 |

`ConfigCodec`、`BackupArchive`、`BackupRepository`、`EditorDraftStore` 预期无须新增字段或更改格式，只需通过相关验证确认现有机制兼容。

## 六、推荐实施顺序

1. **建立语义与模型测试。** 为已保存脚本引用计数和分类删除数据保留补充测试，实现两个纯方法；确认删除后 JSON 能正常编解码。
2. **完成脚本库徽标。** 先完成管理模式的图文、颜色与排版，再检查选择模式、草稿口径、窄屏和 2 倍字体；与用户参考图对照截图。
3. **扩展分类管理交互。** 更新入口/标题，加入删除区、确认窗口及失败保留；沿用现有改名校验，不改变分类创建规则。
4. **接通落盘与筛选。** 在 Activity 内按规定顺序保存，验证分类归属和筛选偏好同时表现正确，不刷新当前网页。
5. **完成回归、文档与交付。** 运行下面的检查，在专用测试设备上验证界面；交付修改清单、验证结果和截图。

## 七、测试与验收

### 7.1 单元测试

优先扩展现有测试，验证用户可观察的数据语义和数据保留，不为颜色常量或 XML 属性本身编写镜像测试。

| 测试位置 | 必须覆盖 |
| --- | --- |
| [ScriptLibraryTest.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/test/java/com/lightboat/app/ScriptLibraryTest.kt:8) | 0、1、多个配置引用；停用引用计入；按 ID 关联；改名/改地址不影响数量；数量不依赖当前筛选。 |
| [ProfileOrganizationTest.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/test/java/com/lightboat/app/ProfileOrganizationTest.kt:150) | 删除空分类、多个配置的分类、最后一个分类；关联配置转 null；无关分类和配置顺序保留；收藏、备注、脚本及开关、UA、平台、启动项保留；未知 ID 拒绝。 |
| 同上及现有 Codec 测试 | 删除后 JSON 编解码往返一致，没有悬空分类 ID；原有 v1/v2 读取和分类改名测试继续通过。 |
| 筛选相关测试 | 删除当前分类只回退分类条件，保留收藏；删除其它分类不改变现有条件；未分类条件不被误置为全部。 |

### 7.2 原生 UI 与持久化验证

脚本库扩展 [ScriptLibraryUiTest.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/androidTest/java/com/lightboat/app/ScriptLibraryUiTest.kt:88)。分类删除建议新增 `CategoryManagementUiTest.kt`，并在 [ProfileFilterPersistenceTest.kt](C:/Users/WinPC/Desktop/new-project/app_view/app/src/androidTest/java/com/lightboat/app/ProfileFilterPersistenceTest.kt:130) 扩展筛选同步用例，避免把同一套流程重复写两遍。

| 场景 | 验收结果 |
| --- | --- |
| 以 3 / 5 / 0 引用配置构造脚本库 | 三个徽标准确；非零浅绿、零浅灰，文字完整，视觉接近参考图。 |
| 引用停用、分类/收藏筛选开启 | 数量仍按全局已保存引用统计。 |
| 新建配置勾选后只“确认选择”，尚未保存配置 | 数量不增加，配置文件也未被提前写入。 |
| 编辑已有配置的引用，保存或取消 | 保存后重开计数更新；取消后计数与原数据一致。 |
| 大脚本库滚动、交替出现 0 与非零条目 | 无回收复用导致的数字、颜色或图标串行。 |
| 长脚本名、长 URL、最多 50 个引用配置 | 更多菜单可点，名称正确省略，计数不截断，徽标不逐字换行。 |
| 删除确认中取消、返回或点外部 | 分类、配置归属及磁盘内容不变，父窗口及未保存名称输入保留。 |
| 先在名称框输入新名称，再删除 | 删除当前选中的原分类；确认显示已保存名称，不偷偷先改名。 |
| 删除有配置、空分类、最后一个分类 | 结果符合正文约定；不减少配置数；最后一个删完隐藏管理入口。 |
| 0 个配置但 categories 非空 | 管理入口仍可达，能清理空分类。 |
| 删除当前分类，收藏筛选为开 | 立即回到“全部分类 + 收藏”；偏好中的旧 ID 被清理；重启及切换弹窗一致。 |
| 删除其它分类，或当前筛选未分类 | 当前筛选保持，新增未分类配置按现有筛选自然展示。 |
| 可控保存失败 | 不出现成功提示，不清理筛选，不关闭窗口；原配置仍可读取。用专用设备夹具模拟失败，不破坏日常配置，也不为单个测试引入大型持久化改造。 |
| 删除后重新启动、JSON/ZIP 备份往返 | 分类记录确实移除、配置仍在、引用与备注不丢，筛选偏好正确；共用备份链路的现有测试继续通过。 |
| 当前网页已打开，进入管理后删除分类再返回 | 当前配置 ID 和同一个 WebView 保留，不重新准备脚本、不重新加载网页；脚本引用数量不变。 |
| Activity 真正 recreate | 编辑器既有脚本草稿恢复正常；删除确认不自动执行，不出现孤立子窗口；重新进入管理显示已落盘结果。 |

至少检查普通竖屏、约 320dp 宽窄屏、横屏以及 2.0 倍字体。建议组合包含 `320×640dp / 1.0`、`360×800dp / 1.0`、`480×320dp / 2.0`。检查标题、帮助、徽标及底部按钮的实际文字布局和点击区域，不能只看 View 是否可见。

### 7.3 执行命令

本功能属于 Kotlin 逻辑和原生 UI 修改，至少在现有 JDK 17、Android SDK 环境执行：

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

设备测试使用专用模拟器或明确指定的测试设备。先确认设备 ID，然后所有操作都指定 `-s`，先安装本次应用 APK 和测试 APK：

```powershell
adb devices -l
adb -s <测试设备ID> install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s <测试设备ID> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <测试设备ID> shell am instrument -w -e class com.lightboat.app.ScriptLibraryUiTest com.lightboat.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s <测试设备ID> shell am instrument -w -e class com.lightboat.app.CategoryManagementUiTest com.lightboat.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s <测试设备ID> shell am instrument -w -e class com.lightboat.app.ProfileFilterPersistenceTest com.lightboat.app.test/androidx.test.runner.AndroidJUnitRunner
```

`CategoryManagementUiTest` 是本方案要求新增的测试类，并非当前已经存在。读取 instrumentation 最终结果确认通过，不能只按 adb 退出码判断。测试完成恢复自己改过的显示/字体设置，清理测试资源。

本次不应触及 JS 注入与媒体路由；若实现过程中实际改到它们，则必须按项目 `AGENTS.md` 补齐相关 Node 与真实 WebView/设备回归，不能仅以编译成功交付。若交付可安装 debug APK，还需按项目要求执行固定签名校验，核对 `output-metadata.json` 和版本；保持原签名及 applicationId。

## 八、给 DeepSeek 的交付要求

完成实现后提供：

1. 实际修改文件及每处用途；特别说明计数来源和分类删除后的保存、筛选更新顺序。
2. 脚本库 0/非零引用的正常屏截图，以及窄屏或大字体截图；管理分类窗口和删除确认窗口截图。
3. 已执行检查、最终结果、测试设备/字体配置，明确列出未执行项及原因。
4. 确认分类删除后配置数量、备注、脚本引用和启动配置保留；已有浏览会话未因本操作重载。
5. 交付前检查 diff，只包含本需求相关源码、测试和文档，不包含构建产物、日志、本机配置或设备数据。

本方案编写阶段只核对代码并形成设计文档，尚未实现上述功能，也没有执行构建或设备验收。文中的测试与预期结果是开发完成后的验收要求。
