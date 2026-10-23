package com.rombs.newyorkeraudio.playback.library

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.media3.session.MediaSession
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import java.security.MessageDigest

@OptIn(UnstableApi::class)
class ControllerAccessValidator(context: Context) {
    private val manager = context.applicationContext.packageManager

    @Suppress("DEPRECATION")
    fun accepts(controller: MediaSession.ControllerInfo): Boolean {
        if (controller.uid == Process.myUid() || controller.isTrusted) return true
        if (controller.packageName != ANDROID_AUTO_PACKAGE) return false
        return try {
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val info = manager.getPackageInfo(controller.packageName, flags)
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.let { if (it.hasMultipleSigners()) emptyArray() else it.signingCertificateHistory }
            } else info.signatures
            val certificates = signatures.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
            controllerAllowed(controller.uid, Process.myUid(), false, controller.packageName, info.applicationInfo?.uid, certificates)
        } catch (_: PackageManager.NameNotFoundException) { false }
        catch (_: SecurityException) { false }
    }
}
