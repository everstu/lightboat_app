# 共享 GM 与规则缓存：Android 设备验收

2026-10-21，在本机 Pixel_4_XL 的独立只读模拟器实例上完成 **39/39** 项设备验收。环境为 Android 15 / API 35 / x86_64、Android System WebView 124.0.6367.219；App 为当前本地 dev 的 1.0.3（35），外部 no-ad.js 为 1.4.6。

## 覆盖范围

| 设备测试 | 通过数 |
| --- | ---: |
| ScriptMessageTransportTest | 2 |
| SharedScriptStorageTest | 6 |
| GmLazyValueTransferTest | 2 |
| NoAdDeviceAcceptanceTest | 3 |
| MultiScriptTest | 11 |
| GmLargeTransferTest | 6 |
| UserScriptNavigationTest | 2 |
| ScriptValueStoreFirstWriteTest | 2 |
| RecoveryAndValueValidationTest 的存储/备份非法值校验 | 1 |
| WebDavBackupTest 的完整恢复、排队写入/sidecar、启动回滚、不读取被替换数据 | 4 |
| 合计 | 39 |

原生测试覆盖共享脚本跨配置读写、不同脚本隔离、旧身份合并及冲突、10 MiB 额度与原子性、恢复后内存失效、5 MiB 按需读取、同步 API、分片 ACK 超时恢复、导航和菜单重连。

NoAdDeviceAcceptanceTest 从应用私有目录读取真实 no-ad.js，仅替换 SOURCES 为 Android 内 NanoHTTPD 提供的受控规则源：

1. 下载超过 4 MiB 的有效规则，切换配置及 origin（127.0.0.1 → localhost），再关闭并重开 Activity；规则继续生效，规则累计请求数保持为 1，只有一份规则正文分片。
2. 预置超过 6 MiB 的旧完整 GM 镜像，禁用网络端点；确认分片/元数据迁移、IndexedDB 恢复副本和冷启动复用，规则请求数为 0。
3. 预置当前来源和约 1 MiB 的废弃分片；通过实际 GM.deleteValue 原生桥回收废弃分片并更新索引，规则请求数为 0。

实际脚本原文件 SHA-256 为 `651d2de746805d68a9cd817138779e0e455f9653e2682944117157b49f2fb0c3`。传入设备时仅将 CRLF 规范化为 LF，已逐字核对其它内容一致。此结果针对本地受控规则与网页，不代表任意远端站点或其它 WebView 版本均已实测。

## 复跑实际脚本测试

先构建并在测试模拟器安装两个 APK；不要在个人手机上运行会替换配置和存储的 instrumentation 用例。设备测试按类串行运行。本轮模拟器使用 `-read-only -no-snapshot`，结束后关闭，不写回 AVD 快照。

NoAdDeviceAcceptanceTest 需要先注入外部脚本，源码默认位于仓库旁的 monkey-script 目录。在仓库根目录执行以下 PowerShell；按需修改脚本路径和设备序号：

```powershell
$noAdAcceptanceScript = (Resolve-Path ..\monkey-script\no-ad.js).Path
@'
const fs = require('node:fs');
const { spawnSync } = require('node:child_process');
const result = spawnSync('adb', ['-s', 'emulator-5580', 'shell',
  "run-as com.lightboat.app sh -c 'cat > files/acceptance-no-ad.js'"],
  { input: fs.readFileSync(process.argv[2]) });
if (result.stdout) process.stdout.write(result.stdout);
if (result.stderr) process.stderr.write(result.stderr);
if (result.error) throw result.error;
process.exit(result.status ?? 1);
'@ | node - $noAdAcceptanceScript
adb -s emulator-5580 shell am instrument -w -e class com.lightboat.app.NoAdDeviceAcceptanceTest com.lightboat.app.test/androidx.test.runner.AndroidJUnitRunner
```

三个实际脚本用例会备份/恢复配置，并清理自己创建的 GM 身份和测试 origin 存储。构建输出 `app/build/reports/gm-device-acceptance-20261021/` 留有完整环境、逐组 ADB 命令、结果 JSON 和原始日志；该目录遵循现有 Git 忽略规则，清理 build 前需要另存时可复制。
