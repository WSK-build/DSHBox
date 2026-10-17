package com.dshbox.browser.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Informs the user that an upload approval is pending without starting the Browser activity.
 * Posting the notice never moves the task to the foreground; the content intent only runs when
 * the user taps it, which then routes into the existing in-Activity approval dialog.
 */
internal class BrowserUploadApprovalNotice(private val context: Context) {
    private val manager: NotificationManager? = context.getSystemService(NotificationManager::class.java)

    fun show(grant: BrowserUploadGrant): Boolean {
        val manager = manager ?: return false
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing?.importance == NotificationManager.IMPORTANCE_NONE) return false
        if (existing == null) {
            runCatching {
                manager.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.browser_upload_approval_title),
                    NotificationManager.IMPORTANCE_HIGH,
                ))
            }
        }
        val text = listOf(grant.summary, grant.origin).firstOrNull { it.isNotBlank() }.orEmpty()
            .ifBlank { grant.origin }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.browser_upload_approval_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openIntent(grant))
            .setTimeoutAfter(BrowserUploadPolicy.GRANT_TTL_MILLIS)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        return runCatching { manager.notify(NOTIFICATION_ID, notification); true }.getOrDefault(false)
    }

    fun cancel() {
        runCatching { manager?.cancel(NOTIFICATION_ID) }
    }

    private fun openIntent(grant: BrowserUploadGrant): PendingIntent {
        // Open the normal session window (adopt path). The pending approval is shown by the
        // activity's resume/focus handler against the shared grant store; an approval-only launch
        // would classify as APPROVAL_ONLY and finish immediately, and a warm approval-only intent
        // would be rejected because a gateway grant has no activity pending reply.
        val intent = Intent(context, BrowserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val CHANNEL_ID = "browser_upload_approval"
        const val NOTIFICATION_ID = 0x425242
    }
}
