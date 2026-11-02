# APK 更新夹具

`candidate.apk` 仅含 manifest，无 Activity、代码或资源，包名 `com.lightboat.app`、版本名 `99.0.0`、版本码 `2000000000`、最低 API 26，由项目固定 debug 密钥签名。用于真实 PackageManager 归档/签名校验及 FileProvider 读取测试，不是用户安装包。

**不可安装此夹具。** `AppUpdateUiTest` 访问真实未知来源设置页并返回，用可替换的权限查询模拟授权结果，再用 Instrumentation.ActivityMonitor 拦截安装 Intent，校验交接后的 URI 可读，不运行系统安装器或替换应用。仅在专用模拟器运行，不更改设备安装 app-op；Android 在 app-op 被拒绝/恢复时会杀死被测进程。

可使用 Android SDK Build Tools 35.0.0 重建，manifest 为：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.lightboat.app" android:versionCode="2000000000" android:versionName="99.0.0"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="Update test fixture - DO NOT INSTALL"/></manifest>
```

以 `aapt2 link -I <SDK>/platforms/android-35/android.jar --manifest AndroidManifest.xml -o unsigned.apk` 创建归档，再以 `apksigner sign --ks signing/lightboat-debug.p12 --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out candidate.apk unsigned.apk` 签名。只保留签名 APK，不保留中间产物及 `.idsig` 文件。
