package io.heckel.ntfy.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.ui.Colors
import io.heckel.ntfy.ui.SettingsActivity
import io.heckel.ntfy.util.Log

/**
 * kudcrafts: catalog. When the server refuses the account token (revoked, expired), say so loudly: a
 * notification that opens Settings -> Kudcrafts account, plus the "sign in again" state in Settings and the
 * main screen. Shown once per refusal, cleared by the next successful sync or sign-in.
 */
object CatalogAuthPrompt {
    private const val TAG = "NtfyCatalogAuth"
    private const val CHANNEL_ID = "kudcrafts_account" // Must not start with "kc-" (those are app channels, reconciled)
    private const val NOTIFICATION_ID = 0x6b630001

    fun show(context: Context, repository: Repository, username: String) {
        if (repository.getCatalogAuthError()) return // Already prompted
        repository.setCatalogAuthError(true)
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.kc_auth_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            )
            val intent = Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val text = context.getString(R.string.kc_auth_notification_text, username)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(Colors.notificationIcon(context))
                .setContentTitle(context.getString(R.string.kc_auth_notification_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to show sign-in prompt", e)
        }
    }

    fun clear(context: Context, repository: Repository) {
        if (!repository.getCatalogAuthError()) return
        repository.setCatalogAuthError(false)
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to cancel sign-in prompt", e)
        }
    }
}
