package com.aamaker.core.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "unknown"
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    try {
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirmIntent)
                    } catch (e: Throwable) {
                        _installEvents.tryEmit(
                            SessionInstallEvent(
                                sessionId = sessionId,
                                success = false,
                                statusCode = PackageInstaller.STATUS_FAILURE_ABORTED,
                                packageName = packageName,
                                statusMessage = e.message ?: "Không thể mở hộp thoại xác nhận cài đặt"
                            )
                        )
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                _installEvents.tryEmit(
                    SessionInstallEvent(
                        sessionId = sessionId,
                        success = true,
                        statusCode = status,
                        packageName = packageName,
                        statusMessage = message
                    )
                )
            }
            else -> {
                _installEvents.tryEmit(
                    SessionInstallEvent(
                        sessionId = sessionId,
                        success = false,
                        statusCode = status,
                        packageName = packageName,
                        statusMessage = message
                    )
                )
            }
        }
    }

    data class SessionInstallEvent(
        val sessionId: Int,
        val success: Boolean,
        val statusCode: Int,
        val packageName: String,
        val statusMessage: String
    )

    companion object {
        private val _installEvents = MutableSharedFlow<SessionInstallEvent>(extraBufferCapacity = 8)
        val installEvents: SharedFlow<SessionInstallEvent> = _installEvents.asSharedFlow()
    }
}
