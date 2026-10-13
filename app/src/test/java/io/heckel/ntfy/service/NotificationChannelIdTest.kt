package io.heckel.ntfy.service

import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.msg.CatalogChannels
import io.heckel.ntfy.msg.CatalogSound
import io.heckel.ntfy.msg.CatalogTheme
import io.heckel.ntfy.ui.MainAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** kudcrafts: catalog. Channel ids per (app, sound class, sound theme, priority band), stale-channel cleanup, list sections. */
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
        assertEquals("kc-facemap-alert-cabin-low", CatalogChannels.channelId("facemap", "alert", "cabin", 1))
        assertEquals("kc-facemap-alert-cabin-low", CatalogChannels.channelId("facemap", "alert", "cabin", 2))
        assertEquals("kc-facemap-alert-cabin", CatalogChannels.channelId("facemap", "alert", "cabin", 3))
        assertEquals("kc-facemap-alert-cabin", CatalogChannels.channelId("facemap", "alert", "cabin", 4))
        assertEquals("kc-facemap-alert-cabin-max", CatalogChannels.channelId("facemap", "alert", "cabin", 5))
    }

    @Test
    fun channelIdPerSoundClassAndTheme() {
        listOf("silent", "default", "alert", "urgent", "cash", "done", "fail", "ask").forEach { cls ->
            assertEquals("kc-kudtrading-$cls-wood", CatalogChannels.channelId("kudtrading", cls, "wood", 3))
        }
        assertEquals("kc-kudtrading-default-cabin", CatalogChannels.channelId("kudtrading", null, null, 3))
        assertEquals("kc-kudtrading-default-cabin", CatalogChannels.channelId("kudtrading", "bogus", "bogus", 3))
        assertEquals("kc-app-facemap", CatalogChannels.groupId("facemap"))
    }

    @Test
    fun changedSoundClassMakesOldChannelsStale() {
        val subs = listOf(sub(1, "facemap-orders", "facemap", "urgent"), sub(2, "my-topic", null))
        val existing = listOf(
            "ntfy", "ntfy-high", "ntfy-subscription-2", // upstream channels: never touched
            "kc-facemap-default-cabin-low", "kc-facemap-default-cabin", "kc-facemap-default-cabin-max", // old class
            "kc-facemap-urgent-cabin-low", "kc-facemap-urgent-cabin", "kc-facemap-urgent-cabin-max", // current class
            "kc-gone-alert-cabin" // app left the catalog
        )
        val stale = CatalogChannels.staleChannelIds(existing, subs, "cabin").toSet()
        assertEquals(setOf("kc-facemap-default-cabin-low", "kc-facemap-default-cabin", "kc-facemap-default-cabin-max", "kc-gone-alert-cabin"), stale)
        assertEquals(listOf("kc-app-gone"), CatalogChannels.staleGroupIds(listOf("ntfy", "kc-app-facemap", "kc-app-gone"), subs))
    }

    @Test
    fun changedThemeReplacesOnlyCatalogChannels() {
        val subs = listOf(sub(1, "facemap-orders", "facemap", "cash"), sub(2, "facemap-ops", "facemap", "silent"), sub(3, "my-topic", null))
        val cabin = CatalogChannels.desiredChannelIds(subs, "cabin")
        val wood = CatalogChannels.desiredChannelIds(subs, "wood")
        assertEquals(6, cabin.size)
        assertTrue(cabin.intersect(wood).isEmpty()) // Every channel is per theme, also the ones without a clip
        val others = listOf(
            "ntfy", "ntfy-min", "ntfy-low", "ntfy-high", "ntfy-max", // upstream default group
            "ntfy-subscription-3", "ntfy-subscription-3-max", // a topic's dedicated channels, with the user's own settings
            "ntfy-subscriber", "ntfy-connection-alert", "kudcrafts_account" // service and sign-in prompt
        )
        val stale = CatalogChannels.staleChannelIds(others + cabin, subs, "wood")
        assertEquals(cabin, stale.toSet())
        assertTrue(CatalogChannels.staleChannelIds(others + wood, subs, "wood").isEmpty())
        // The group (the app's section in the system settings) survives a theme change
        assertTrue(CatalogChannels.staleGroupIds(listOf("ntfy", "kc-app-facemap"), subs).isEmpty())
    }

    @Test
    fun channelsOfReleasesBeforeThemesAreStale() {
        // kc-<app>-<class><band> can never equal kc-<app>-<class>-<theme><band>, not even for an app named like a theme
        val subs = listOf(sub(1, "a", "facemap", "default"), sub(2, "b", "facemap-cabin", "default"), sub(3, "c", "default-cabin", "alert"))
        val old = listOf(
            "kc-facemap-default-low", "kc-facemap-default", "kc-facemap-default-max",
            "kc-facemap-cabin-default", "kc-default-cabin-alert-max"
        )
        CatalogTheme.ALL.forEach { theme ->
            assertEquals(old, CatalogChannels.staleChannelIds(old, subs, theme))
        }
    }

    @Test
    fun twoTopicsOfOneAppWithDifferentClassesKeepBoth() {
        val subs = listOf(sub(1, "facemap-orders", "facemap", "alert"), sub(2, "facemap-ops", "facemap", "silent"))
        val desired = CatalogChannels.desiredChannelIds(subs, "glass")
        assertEquals(6, desired.size)
        assertTrue(CatalogChannels.staleChannelIds(desired, subs, "glass").isEmpty())
    }

    @Test
    fun themeResolution() {
        assertEquals("cabin", CatalogTheme.DEFAULT)
        assertEquals(10, CatalogTheme.ALL.size)
        assertEquals("cabin", CatalogTheme.resolve(null, null)) // Older server, no override
        assertEquals("glass", CatalogTheme.resolve(null, "glass")) // Follow the server
        assertEquals("wood", CatalogTheme.resolve("wood", "glass")) // This device's choice wins
        assertEquals("glass", CatalogTheme.resolve("bogus", "glass")) // A theme this build does not have: follow the server
        assertEquals("cabin", CatalogTheme.resolve(null, "vaporwave")) // Unknown server theme
        assertEquals("cabin", CatalogTheme.resolve("", ""))
    }

    @Test
    fun clipNames() {
        assertEquals("kc_cabin_urgent", CatalogTheme.clipName("cabin", "urgent"))
        assertEquals("kc_terminal_ask", CatalogTheme.clipName("terminal", "ask"))
        assertEquals("kc_cabin_default", CatalogTheme.clipName("bogus", "bogus")) // Unknown theme -> cabin, unknown class -> default
        assertEquals("kc_cabin_default", CatalogTheme.clipName(null, null))
        assertEquals(null, CatalogTheme.clipName("cabin", "silent"))
        assertEquals(7, CatalogSound.AUDIBLE.size)
        assertEquals(CatalogSound.ALL.toSet() - "silent", CatalogSound.AUDIBLE.toSet())
    }

    @Test
    fun everyClipIsBundled() {
        // Unit tests run in app/: the 70 clips are committed, not generated
        val raw = java.io.File("src/main/res/raw")
        CatalogTheme.ALL.forEach { theme ->
            CatalogSound.AUDIBLE.forEach { sound ->
                val clip = java.io.File(raw, CatalogTheme.clipName(theme, sound) + ".ogg")
                assertTrue("$clip is missing", clip.isFile && clip.length() > 0)
            }
        }
        assertEquals(70, raw.listFiles { f -> f.name.startsWith("kc_") }!!.size)
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
        val expected = if (io.heckel.ntfy.ui.KcStyle.enabled) {
            // fdroid look: a one-topic app is one row (no header), like the web sidebar
            listOf("H:FaceMap:4", "R:facemap-orders", "R:facemap-ops", "R:kudtrading", "H:Other:1", "R:mine")
        } else {
            listOf("H:FaceMap:4", "R:facemap-orders", "R:facemap-ops", "H:kudtrading:2", "R:kudtrading", "H:Other:1", "R:mine")
        }
        assertEquals(expected, shape)
    }

    @Test
    fun mainListWithoutCatalogHasNoHeaders() {
        val items = MainAdapter.buildItems(listOf(sub(1, "a", null), sub(2, "b", null)), "Other")
        assertTrue(items.all { it is MainAdapter.Item.Row })
    }
}
