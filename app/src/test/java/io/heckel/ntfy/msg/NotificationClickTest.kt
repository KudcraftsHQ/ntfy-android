package io.heckel.ntfy.msg

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.ui.DetailActivity
import io.heckel.ntfy.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * kudcrafts: tapping a notification must open the message's click URL (and the topic only when there is none).
 * In 1.25.2-kc.1 a tap opened the app: Android bundled our many notifications under its own auto-group summary,
 * whose tap launches the app. Now every notification goes through NotificationClickActivity, in our own group.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationClickTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val url = "https://board.kudcrafts.com/facemap/orders/facemap-d1c473bc"

    private fun subscription(app: String? = "facemap") = Subscription(
        id = 4242L, baseUrl = "https://ntfy.kudcrafts.com", topic = "facemap-orders", instant = true, mutedUntil = 0,
        minPriority = 0, autoDelete = -1, insistent = -1, lastNotificationId = null, icon = null,
        upAppId = null, upConnectorToken = null, displayName = null, dedicatedChannels = false,
        managed = app != null, catalogApp = app, catalogAppName = app?.let { "FaceMap" }, catalogIcon = null,
        catalogSound = "alert", catalogName = "Orders"
    )

    private fun notification(id: String, click: String, notificationId: Int = 777) = Notification(
        id = id, subscriptionId = 4242L, timestamp = 1_759_740_000L, sequenceId = id, title = "Order paid",
        message = "Rp199.000 via QRIS", contentType = "", encoding = "", notificationId = notificationId, priority = 3,
        tags = "", click = click, icon = null, actions = null, attachment = null, deleted = false
    )

    @Test
    fun pendingIntentTargetsClickActivityWithTheUrl() {
        val pi = NotificationService(context).clickPendingIntent(subscription(), notification("m1", url), "g")
        val shadow = shadowOf(pi)
        assertTrue(shadow.isActivityIntent)
        val intent = shadow.savedIntent
        assertEquals(NotificationClickActivity::class.java.name, intent.component?.className)
        assertEquals(url, intent.getStringExtra(NotificationClickActivity.EXTRA_CLICK))
        assertEquals(4242L, intent.getLongExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, 0))
        assertEquals("facemap-orders", intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC))
    }

    @Test
    fun displayedNotificationCarriesTheClickUrl() {
        val service = NotificationService(context)
        service.display(subscription(), notification("m2", url, notificationId = 778))
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(manager).getNotification(778)
        assertNotNull(posted)
        val saved = shadowOf(posted.contentIntent).savedIntent
        assertEquals(NotificationClickActivity::class.java.name, saved.component?.className)
        assertEquals(url, saved.getStringExtra(NotificationClickActivity.EXTRA_CLICK))
        assertEquals("kc-notif-app-facemap", posted.group) // Our group, so Android's auto-group summary never wraps it
    }

    @Test
    fun summaryOfSeveralTapsThroughToTheNewestUrl() {
        val service = NotificationService(context)
        service.display(subscription(), notification("a", "https://example.com/a", notificationId = 801))
        service.display(subscription(), notification("b", "https://example.com/b", notificationId = 802))
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val summary = shadowOf(manager).getNotification(NotificationService.groupSummaryId("kc-notif-app-facemap"))
        assertNotNull(summary)
        val saved = shadowOf(summary.contentIntent).savedIntent
        assertEquals(NotificationClickActivity::class.java.name, saved.component?.className)
        assertTrue(saved.getStringExtra(NotificationClickActivity.EXTRA_CLICK)!!.startsWith("https://example.com/"))
    }

    @Test
    fun tapWithClickOpensTheUrl() {
        val intent = NotificationClickActivity.intent(context, url, 4242L, "https://ntfy.kudcrafts.com", "facemap-orders",
            "Orders", true, 0L, "m3", null)
        val activity = Robolectric.buildActivity(NotificationClickActivity::class.java, intent).create().get()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url, started.dataString)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun tapWithoutClickOpensTheTopic() {
        val intent = NotificationClickActivity.intent(context, "", 4242L, "https://ntfy.kudcrafts.com", "facemap-orders",
            "Orders", true, 0L, "m4", null)
        val activity = Robolectric.buildActivity(NotificationClickActivity::class.java, intent).create().get()
        val shadow = shadowOf(activity)
        val first = shadow.nextStartedActivity
        val second = shadow.nextStartedActivity
        val classes = listOfNotNull(first, second).map { it.component?.className }
        assertTrue(classes.contains(DetailActivity::class.java.name))
        val detail = listOfNotNull(first, second).first { it.component?.className == DetailActivity::class.java.name }
        assertEquals(4242L, detail.getLongExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, 0))
        assertNull(listOfNotNull(first, second).firstOrNull { it.action == Intent.ACTION_VIEW })
    }
}
