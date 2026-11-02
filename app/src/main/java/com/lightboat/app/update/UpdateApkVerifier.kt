package com.lightboat.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

internal object UpdateApkVerifier {
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, release: AppRelease) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateException(UpdateFailure.INVALID_APK)
        val signers = signatures(installed)
        if (candidate.packageName != context.packageName || code(candidate) <= code(installed) ||
            ReleaseVersion.parse(candidate.versionName.orEmpty())?.compareTo(release.version) != 0 ||
            candidate.applicationInfo?.minSdkVersion?.let { it > Build.VERSION.SDK_INT } != false ||
            signers.isEmpty() || signers != signatures(candidate)) throw UpdateException(UpdateFailure.INVALID_APK)
    }

    @Suppress("DEPRECATION")
    fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
