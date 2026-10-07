package io.heckel.ntfy.msg

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.net.toUri
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.ui.DetailActivity
import io.heckel.ntfy.ui.MainActivity
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.launch

/**
 * kudcrafts: what a tap on a notification does. Opens the message's `click` URL when it has one, and the
 * topic in the app when it does not (or when nothing on the phone can open the URL). Also marks the message
 * as read and tidies the app's notification group.
 *
 * Why an activity instead of a direct ACTION_VIEW PendingIntent (upstream): the tap now always runs our code,
 * so it can fall back, mark read and keep the group summary honest. Activities are exempt from Android 12's
 * notification trampoline restriction (only services and receivers are blocked).
 */
class NotificationClickActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val click = intent.getStringExtra(EXTRA_CLICK).orEmpty()
        val subscriptionId = intent.getLongExtra(EXTRA_SUBSCRIPTION_ID, 0L)
        val sequenceId = intent.getStringExtra(EXTRA_SEQUENCE_ID).orEmpty()
        val opened = click.isNotEmpty() && openUrl(click)
        if (!opened) {
            openTopic()
        }
        if (subscriptionId != 0L && sequenceId.isNotEmpty()) {
            val app = applicationContext as io.heckel.ntfy.app.Application
            app.ioScope.launch {
                Repository.getInstance(app).markAsReadBySequenceId(subscriptionId, sequenceId)
                intent.getStringExtra(EXTRA_GROUP)?.let { NotificationService(app).refreshGroupSummary(it) }
            }
        }
        finish()
    }

    private fun openUrl(url: String): Boolean {
        return try {
            val view = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(view)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app can open $url", e)
            Toast.makeText(this, getString(R.string.detail_item_cannot_open_url, url), Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open $url", e)
            false
        }
    }

    private fun openTopic() {
        val detail = Intent(this, DetailActivity::class.java).apply {
            putExtras(intent) // EXTRA_SUBSCRIPTION_* for DetailActivity
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val main = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivities(arrayOf(main, detail)) // Back from the topic lands on the list, like upstream's back stack
    }

    companion object {
        private const val TAG = "NtfyNotifClick"
        const val EXTRA_CLICK = "kc_click"
        const val EXTRA_SUBSCRIPTION_ID = MainActivity.EXTRA_SUBSCRIPTION_ID
        const val EXTRA_SEQUENCE_ID = "kc_sequence_id"
        const val EXTRA_GROUP = "kc_group"

        /** The intent a notification tap sends. Pure enough to unit-test (see NotificationClickTest). */
        fun intent(
            context: Context,
            click: String,
            subscriptionId: Long,
            baseUrl: String,
            topic: String,
            displayName: String,
            instant: Boolean,
            mutedUntil: Long,
            sequenceId: String,
            group: String?
        ): Intent {
            return Intent(context, NotificationClickActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra(EXTRA_CLICK, click)
                putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscriptionId)
                putExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL, baseUrl)
                putExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC, topic)
                putExtra(MainActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME, displayName)
                putExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, instant)
                putExtra(MainActivity.EXTRA_SUBSCRIPTION_MUTED_UNTIL, mutedUntil)
                putExtra(EXTRA_SEQUENCE_ID, sequenceId)
                if (group != null) putExtra(EXTRA_GROUP, group)
            }
        }
    }
}
