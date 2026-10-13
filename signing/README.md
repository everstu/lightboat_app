# 固定 debug 签名

`lightboat-debug.p12` 是本项目共享的开发签名，别名 `androiddebugkey`，库密码和私钥密码均为 `android`。手动构建与标签 Release 的 `assembleDebug` 使用同一个文件，保证以后构建的 APK 签名一致。

这是公开的 debug 密钥，仅用于本项目的 debug 分发，不作为生产发布密钥。请保留该文件，不要每次构建重新生成。升级 APK 保持 `applicationId` 不变且 `versionCode` 不低于已安装版本。

以前由 GitHub runner 临时 debug 密钥签名的版本与此密钥不同：首次迁移需要导出配置、卸载旧版、安装新版、再导入；此后的版本可覆盖安装。

证书 SHA-256 指纹：`05:75:D7:99:8D:F3:38:FE:23:1F:05:F3:FE:85:B8:76:A0:64:30:5F:C4:DB:2B:39:AB:93:7F:47:4E:D7:FE:29`。工作流在发布/上传前校验 APK 签名，防止无意替换密钥导致覆盖安装失败。
