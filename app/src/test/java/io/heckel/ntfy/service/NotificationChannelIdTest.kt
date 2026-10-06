package io.heckel.ntfy.service

import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.msg.CatalogChannels
import io.heckel.ntfy.ui.MainAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** kudcrafts: catalog. Channel ids per (app, sound class, priority band), stale-channel cleanup, list sections. */
class NotificationChannelIdTest {
    private fun sub(id: Long, topic: String, app: String?, sound: String? = "default", appName: String? = app, newCount: Int = 0) = Subscription(
        id = id, baseUrl = "https://ntfy.kudcrafts.com", topic = topic, instant = true, mutedUntil = 0,
        minPriority = 0, autoDelete = -1, insistent = -1, lastNotificationId = null, icon = null,
        upAppId = null, upConnectorToken = null, displayName = null, dedicatedChannels = false,
        managed = app != null, catalogApp = app, catalogAppName = appName, catalogIcon = null,
        catalogSound = sound, catalogName = null, newCount = newCount
    )

    @Test
    fun channelIdPerPriorityBand() {
        assertEquals("kc-facemap-alert-low", CatalogChannels.channelId("facemap", "alert", 1))
        assertEquals("kc-facemap-alert-low", CatalogChannels.channelId("facemap", "alert", 2))
        assertEquals("kc-facemap-alert", CatalogChannels.channelId("facemap", "alert", 3))
        assertEquals("kc-facemap-alert", CatalogChannels.channelId("facemap", "alert", 4))
        assertEquals("kc-facemap-alert-max", CatalogChannels.channelId("facemap", "alert", 5))
    }

    @Test
    fun channelIdPerSoundClass() {
        listOf("silent", "default", "alert", "urgent").forEach { cls ->
            assertEquals("kc-kudtrading-$cls", CatalogChannels.channelId("kudtrading", cls, 3))
        }
        assertEquals("kc-kudtrading-default", CatalogChannels.channelId("kudtrading", null, 3))
        assertEquals("kc-kudtrading-default", CatalogChannels.channelId("kudtrading", "bogus", 3))
        assertEquals("kc-app-facemap", CatalogChannels.groupId("facemap"))
    }

    @Test
    fun changedSoundClassMakesOldChannelsStale() {
        val subs = listOf(sub(1, "facemap-orders", "facemap", "urgent"), sub(2, "my-topic", null))
        val existing = listOf(
            "ntfy", "ntfy-high", "ntfy-subscription-2", // upstream channels: never touched
            "kc-facemap-default-low", "kc-facemap-default", "kc-facemap-default-max", // old class
            "kc-facemap-urgent-low", "kc-facemap-urgent", "kc-facemap-urgent-max", // current class
            "kc-gone-alert" // app left the catalog
        )
        val stale = CatalogChannels.staleChannelIds(existing, subs).toSet()
        assertEquals(setOf("kc-facemap-default-low", "kc-facemap-default", "kc-facemap-default-max", "kc-gone-alert"), stale)
        assertEquals(listOf("kc-app-gone"), CatalogChannels.staleGroupIds(listOf("ntfy", "kc-app-facemap", "kc-app-gone"), subs))
    }

    @Test
    fun twoTopicsOfOneAppWithDifferentClassesKeepBoth() {
        val subs = listOf(sub(1, "facemap-orders", "facemap", "alert"), sub(2, "facemap-ops", "facemap", "silent"))
        val desired = CatalogChannels.desiredChannelIds(subs)
        assertEquals(6, desired.size)
        assertTrue(CatalogChannels.staleChannelIds(desired, subs).isEmpty())
    }

    @Test
    fun mainListSectionsByAppThenOther() {
        val subs = listOf(
            sub(1, "kudtrading", "kudtrading", appName = "kudtrading", newCount = 2),
            sub(2, "mine", null, newCount = 1),
            sub(3, "facemap-orders", "facemap", appName = "FaceMap", newCount = 3),
            sub(4, "facemap-ops", "facemap", appName = "FaceMap", newCount = 1)
        )
        val items = MainAdapter.buildItems(subs, "Other")
        val shape = items.map {
            when (it) {
                is MainAdapter.Item.Header -> "H:${it.name}:${it.unread}"
                is MainAdapter.Item.Row -> "R:${it.subscription.topic}"
            }
        }
        assertEquals(
            listOf("H:FaceMap:4", "R:facemap-orders", "R:facemap-ops", "H:kudtrading:2", "R:kudtrading", "H:Other:1", "R:mine"),
            shape
        )
    }

    @Test
    fun mainListWithoutCatalogHasNoHeaders() {
        val items = MainAdapter.buildItems(listOf(sub(1, "a", null), sub(2, "b", null)), "Other")
        assertTrue(items.all { it is MainAdapter.Item.Row })
    }
}
