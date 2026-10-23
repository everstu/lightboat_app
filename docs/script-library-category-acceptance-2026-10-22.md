# 脚本库与分类管理增量验收（2026-10-22）

在本地 `dev` 分支接续已有实现。保持 `com.lightboat.app`、`versionName=1.0.3`、`versionCode=35` 和固定 debug 签名；没有提交、推送或发布。

## 本轮修复

- **脚本编辑器横屏键盘裁切**：原 `scriptEditorRetainsItsFieldsAndSaveActionAcrossRotationWithTheKeyboard` 在整类运行和单跑中均复现了 168px 输入框只有 140px 可见。日志显示，纠偏将滚动位置设为 605px 后，未完成的焦点滚动动画继续滚到 651px，覆盖了纠偏。`ScriptEditorDialog` 将自动焦点滚动设为立即完成，保留触摸滚动。原用例修复后连续单跑 5 次通过，最终完整脚本库回归也通过。
- **短屏大字体脚本库不可操作**：480×320dp、2 倍字体下，固定帮助区占满窗口，列表和保存按钮被挤出。`ScriptLibraryDialog` 使用现有 RecyclerView 的 `ConcatAdapter`，将帮助、新建/批量添加和空库提示作为列表头，与脚本条目一起滚动；标题及底部操作固定保留。没有增加依赖。
- **选择按钮裁字**：320×640dp、2 倍字体下，“确认选择”换行后受到固定 48dp 高度和横向容器基线对齐影响。按钮改为内容高度、保留 48dp 最小触摸高度，按钮行取消基线对齐。

## 补齐的验证

- `ScriptLibraryUiTest`：管理/选择两种模式的计数、样式、标题与完整底部操作，新建入口滚动后可达；新配置草稿取消、保存及已有配置移除引用后的计数；40 条交替 0/非零引用记录往返滚动后的复用状态。
- 横屏编辑器用例继续检查输入框完整高度，并将窗口内可见矩形转换为屏幕坐标后检查键盘和系统栏遮挡。
- `CategoryManagementUiTest`：失败删除除保留父子窗口和内存配置外，还断言磁盘文件逐字节不变、分类及收藏筛选偏好不变。保留短名称及 `unsavedName` 修正。
- `ProfileFilterPersistenceTest`：删除分类后返回浏览，断言同一个 WebView、同一当前配置 ID 和页面内 JS 标记仍在；切换弹窗与重启后的筛选保持一致。使用本机 HTTP 页面。
- `UiTestDisplay.kt`：矩阵测试通过临时 Activity 方向请求固定竖/横屏，并断言实际 `screenWidthDp`、`screenHeightDp`、`fontScale`。`fullSensor` Activity 会忽略系统旋转锁，所以仅设置 adb 的 `user_rotation` 不作为方向验证。

引用数仍来自全部已保存 `config.profiles`，包含停用引用，每个配置按脚本 ID 只计一次。分类删除仍先生成分类记录与归属同时更新的配置并调用 `persist`，保存成功后更新筛选偏好、刷新列表、提示成功；配置内容与脚本引用保留。

## 最终验证结果

设备为 Pixel 4 XL / Android 15（API 35）模拟器的临时只读实例 `emulator-5556`，先安装本次应用 APK 和测试 APK。下表的小屏尺寸是已由测试断言确认的 dp 值。

| 配置 | 验证范围 | 结果 |
| --- | --- | --- |
| 1440×3040 px、560 dpi、字体 1.0 | `ScriptLibraryUiTest` 完整类，包含类内旋转专项 | 15/15 |
| 同上，竖屏 | `CategoryManagementUiTest` | 6/6 |
| 同上 | `ProfileFilterPersistenceTest`，含真实 WebView 保留验证 | 7/7 |
| 320×640dp、字体 1.0、竖屏 | 分类管理完整类；脚本库两种模式的徽标/滚动入口/底部按钮 | 6/6；1/1 |
| 320×640dp、字体 2.0、竖屏 | 脚本库两种模式的徽标/滚动入口/底部按钮 | 1/1 |
| 360×800dp、字体 1.0、竖屏 | 同上 | 1/1 |
| 480×320dp、字体 1.0、横屏 | 分类管理完整类；脚本库两种模式的徽标/滚动入口/底部按钮 | 6/6；1/1 |
| 480×320dp、字体 2.0、横屏 | 同上 | 6/6；1/1 |
| 本地 | `testDebugUnitTest` | 138 项，0 失败/错误/跳过 |
| 本地 | `lintDebug` | 0 Error，103 Warning |
| 本地 | `assembleDebug assembleDebugAndroidTest` | 成功 |
| 本地 | `scripts/verify_debug_signature.py` | PASS |
| 本地 | `git diff --check` | 通过 |

典型矩阵命令（需先将测试设备设置为 640×960 px、320 dpi、2 倍字体）：

```powershell
adb -s emulator-5556 shell am instrument -w `
  -e class com.lightboat.app.ScriptLibraryUiTest#usageBadgesShowSavedProfileCountsWithGlobalScopeAndMatchingStyles `
  -e rotation 90 -e expectFontScale 2.0 -e expectScreenWidthDp 480 -e expectScreenHeightDp 320 `
  com.lightboat.app.test/androidx.test.runner.AndroidJUnitRunner
```

旋转专项单独运行，不传固定方向参数。所有结果都检查 instrumentation 最终 `OK`/失败明细，未仅依据 adb 退出码。

## 截图与交付

- [普通竖屏脚本徽标](screenshots/script-usage-badges.png)
- [320dp 窄屏](screenshots/script-usage-badges-narrow.png)
- [320dp、2 倍字体](screenshots/script-usage-badges-font-2x.png)
- [窄屏大字体的选择模式与完整确认按钮](screenshots/script-usage-selection-portrait-2x.png)
- [480×320dp、2 倍字体的脚本库](screenshots/script-usage-badges-landscape-2x.png)
- [同配置下的选择模式](screenshots/script-usage-selection-landscape-2x.png)
- [横屏键盘下的脚本编辑器](screenshots/script-editor-landscape-keyboard.png)
- [短屏大字体的分类删除入口](screenshots/category-manager-landscape-2x-delete.png)

可安装包：`app/build/outputs/apk/debug/app-debug.apk`。测试 APK 仅用于 instrumentation。

本轮没有修改网页加载、脚本执行或媒体播放路径，也没有重新执行目标网站或媒体播放回归。短屏验收覆盖本需求的脚本库和分类管理弹窗；配置管理主页在 480×320dp、2 倍字体下仍有列表空间不足的既有布局限制，未在本轮扩展修改。测试结束恢复显示、字体和旋转设置，关闭临时只读模拟器；测试数据不会写回原 AVD。

后续进展：上述配置管理主页短屏限制已在统一紧凑布局任务中复现并修复，见 [紧凑布局验收](compact-ui-2026-10-22.md)。
