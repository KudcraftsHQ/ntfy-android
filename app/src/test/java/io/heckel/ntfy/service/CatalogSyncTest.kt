package io.heckel.ntfy.service

import io.heckel.ntfy.db.CatalogFields
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.msg.Catalog
import io.heckel.ntfy.msg.CatalogApi
import io.heckel.ntfy.msg.CatalogApp
import io.heckel.ntfy.msg.CatalogTopic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** kudcrafts: catalog. Reconcile plan (contract 14.5) and catalog decoding (contract 14.1). */
class CatalogSyncTest {
    private val base = "https://ntfy.kudcrafts.com"

    private fun sub(
        id: Long,
        topic: String,
        baseUrl: String = base,
        managed: Boolean = false,
        app: String? = null,
        appName: String? = null,
        icon: String? = null,
        sound: String? = null,
        name: String? = null,
        displayName: String? = null,
        mutedUntil: Long = 0,
        upAppId: String? = null
    ) = Subscription(
        id = id, baseUrl = baseUrl, topic = topic, instant = true, mutedUntil = mutedUntil,
        minPriority = 0, autoDelete = -1, insistent = -1, lastNotificationId = null, icon = null,
        upAppId = upAppId, upConnectorToken = upAppId?.let { "tok-$it" }, displayName = displayName, dedicatedChannels = false,
        managed = managed, catalogApp = app, catalogAppName = appName, catalogIcon = icon,
        catalogSound = sound, catalogName = name
    )

    private fun catalog(vararg apps: CatalogApp) = Catalog(1L, base, 90, "st_sync", apps.toList())

    private val facemap = CatalogApp(
        id = "facemap", name = "FaceMap", icon = "https://facemap.fyi/icon.png", sound = "default",
        topics = listOf(
            CatalogTopic("facemap-orders", "Orders", "alert", "read-only"),
            CatalogTopic("facemap-alerts", "", "default", "read-write")
        )
    )

    private fun ids(start: Long = 1000L): () -> Long {
        var next = start
        return { next++ }
    }

    @Test
    fun addsMissingTopicsAsManaged() {
        val plan = CatalogSync.reconcile(emptyList(), base, catalog(facemap), ids(), 42L)
        assertEquals(2, plan.add.size)
        assertTrue(plan.update.isEmpty())
        assertTrue(plan.remove.isEmpty())
        val orders = plan.add.first { it.topic == "facemap-orders" }
        assertTrue(orders.managed)
        assertTrue(orders.instant)
        assertEquals(base, orders.baseUrl)
        assertEquals("facemap", orders.catalogApp)
        assertEquals("FaceMap", orders.catalogAppName)
        assertEquals("alert", orders.catalogSound)
        assertEquals("Orders", orders.catalogName)
        assertEquals("https://facemap.fyi/icon.png", orders.catalogIcon)
        assertNull(orders.displayName)
        assertEquals(0L, orders.mutedUntil)
        assertEquals(Repository.MIN_PRIORITY_USE_GLOBAL, orders.minPriority)
        assertEquals(Repository.AUTO_DELETE_THREE_MONTHS_SECONDS, orders.autoDelete)
        assertEquals(Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL, orders.insistent)
        assertFalse(orders.dedicatedChannels)
        assertEquals(42L, orders.lastActive)
    }

    @Test
    fun newIdsAvoidExistingSubscriptions() {
        val existing = sub(1000L, "something-else", baseUrl = "https://ntfy.sh")
        val plan = CatalogSync.reconcile(listOf(existing), base, catalog(facemap), ids(1000L), 0L)
        val newIds = plan.add.map { it.id }
        assertFalse(newIds.contains(1000L))
        assertEquals(newIds.size, newIds.toSet().size)
    }

    @Test
    fun updatesCatalogFieldsButKeepsUserSettings() {
        val existing = sub(
            1L, "facemap-orders", managed = true, app = "facemap", appName = "Facemap (old)",
            icon = "https://facemap.fyi/icon.png", sound = "default", name = "Old", displayName = "My orders",
            mutedUntil = 1L
        )
        val plan = CatalogSync.reconcile(listOf(existing), base, catalog(facemap), ids(), 0L)
        assertEquals(1, plan.add.size) // facemap-alerts
        assertEquals(1, plan.update.size)
        val update = plan.update.single()
        assertEquals(1L, update.subscription.id)
        assertEquals(CatalogFields(true, "facemap", "FaceMap", "https://facemap.fyi/icon.png", "alert", "Orders"), update.fields)
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun unchangedTopicProducesNoUpdate() {
        val existing = sub(1L, "facemap-orders", managed = true, app = "facemap", appName = "FaceMap",
            icon = "https://facemap.fyi/icon.png", sound = "alert", name = "Orders")
        val alerts = sub(2L, "facemap-alerts", managed = true, app = "facemap", appName = "FaceMap",
            icon = "https://facemap.fyi/icon.png", sound = "default", name = "")
        val plan = CatalogSync.reconcile(listOf(existing, alerts), base, catalog(facemap), ids(), 0L)
        assertTrue(plan.isEmpty())
    }

    @Test
    fun removesManagedTopicsNoLongerListed() {
        val gone = sub(3L, "facemap-legacy", managed = true, app = "facemap", appName = "FaceMap", sound = "default", name = "")
        val plan = CatalogSync.reconcile(listOf(gone), base, catalog(facemap), ids(), 0L)
        assertEquals(listOf(gone), plan.remove)
    }

    @Test
    fun emptyCatalogRemovesAllManagedOnThatServer() {
        val managed = sub(3L, "facemap-orders", managed = true, app = "facemap")
        val plan = CatalogSync.reconcile(listOf(managed), base, catalog(), ids(), 0L)
        assertEquals(listOf(managed), plan.remove)
        assertTrue(plan.add.isEmpty())
    }

    @Test
    fun neverTouchesUnmanagedSubscriptions() {
        val mine = sub(4L, "my-own-topic") // Same server, not in catalog, never decorated
        val otherServer = sub(5L, "facemap-orders", baseUrl = "https://ntfy.sh") // Same topic name, other server
        val unifiedPush = sub(6L, "upABC", upAppId = "org.example.app")
        val plan = CatalogSync.reconcile(listOf(mine, otherServer, unifiedPush), base, catalog(facemap), ids(), 0L)
        assertEquals(setOf("facemap-orders", "facemap-alerts"), plan.add.map { it.topic }.toSet())
        assertTrue(plan.add.all { it.baseUrl == base })
        assertTrue(plan.update.isEmpty())
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun handAddedCatalogTopicIsDecoratedButStaysUnmanaged() {
        val handAdded = sub(7L, "facemap-orders")
        val plan = CatalogSync.reconcile(listOf(handAdded), base, catalog(facemap), ids(), 0L)
        assertFalse(plan.add.any { it.topic == "facemap-orders" })
        val update = plan.update.single { it.subscription.id == 7L }
        assertFalse(update.fields.managed)
        assertEquals("facemap", update.fields.app)
    }

    @Test
    fun decoratedUnmanagedTopicIsUndecoratedNotRemoved() {
        val decorated = sub(8L, "facemap-legacy", managed = false, app = "facemap", appName = "FaceMap")
        val plan = CatalogSync.reconcile(listOf(decorated), base, catalog(facemap), ids(), 0L)
        assertTrue(plan.remove.isEmpty())
        assertEquals(CatalogFields.NONE, plan.update.single { it.subscription.id == 8L }.fields)
    }

    @Test
    fun topicListedTwiceKeepsFirstApp() {
        val other = CatalogApp("zz", "ZZ", "", "urgent", listOf(CatalogTopic("facemap-orders", "Dup", "urgent", "read-only")))
        val plan = CatalogSync.reconcile(emptyList(), base, catalog(facemap, other), ids(), 0L)
        assertEquals(2, plan.add.size)
        assertEquals("facemap", plan.add.first { it.topic == "facemap-orders" }.catalogApp)
    }

    @Test
    fun iconDownloadOnlyWhenSlotIsEmptyOrOurs() {
        val url = "https://facemap.fyi/icon.png"
        val noIcon = sub(1L, "t", app = "facemap", icon = url)
        assertTrue(CatalogSync.shouldDownloadIcon(noIcon, null))
        assertFalse(CatalogSync.shouldDownloadIcon(noIcon, url)) // Already downloaded this URL

        val catalogIcon = noIcon.copy(icon = "content://x/1")
        assertTrue(CatalogSync.shouldDownloadIcon(catalogIcon, "https://facemap.fyi/old.png")) // Icon changed upstream
        assertFalse(CatalogSync.shouldDownloadIcon(catalogIcon, null)) // User's own icon: never replaced

        assertFalse(CatalogSync.shouldDownloadIcon(noIcon.copy(catalogIcon = ""), null))
        assertFalse(CatalogSync.shouldDownloadIcon(noIcon.copy(catalogIcon = "http://insecure/icon.png"), null))
    }

    @Test
    fun parsesCatalogFromContract() {
        val json = """
            {
              "version": 1759740000123,
              "base_url": "https://ntfy.kudcrafts.com",
              "history_days": 90,
              "sync_topic": "st_abc",
              "apps": [
                {
                  "id": "facemap", "name": "FaceMap",
                  "icon": "https://facemap.fyi/icon-192.png",
                  "sound": "default",
                  "topics": [
                    { "topic": "facemap-orders", "name": "Orders", "sound": "alert",  "permission": "read-only" },
                    { "topic": "facemap-alerts", "name": "",       "sound": "default", "permission": "read-write" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val c = CatalogApi.parseCatalog(json)
        assertNotNull(c)
        c!!
        assertEquals(1759740000123L, c.version)
        assertEquals(90, c.historyDays)
        assertEquals("st_abc", c.syncTopic)
        assertEquals(1, c.apps.size)
        assertEquals("FaceMap", c.apps[0].name)
        assertEquals(listOf("facemap-orders", "facemap-alerts"), c.apps[0].topics.map { it.topic })
        assertEquals("alert", c.apps[0].topics[0].sound)
        assertEquals("read-write", c.apps[0].topics[1].permission)
    }

    @Test
    fun parsingNormalizesMissingAndUnsafeFields() {
        val json = """
            { "version": 2, "apps": [
              { "id": "kudtrading", "icon": "http://plain.example/icon.png", "sound": "boom",
                "topics": [ { "topic": "kudtrading" }, { "name": "no topic" }, null ] },
              { "name": "no id", "topics": [] }
            ] }
        """.trimIndent()
        val c = CatalogApi.parseCatalog(json)!!
        assertEquals("", c.syncTopic)
        assertEquals(1, c.apps.size)
        val app = c.apps.single()
        assertEquals("kudtrading", app.name) // Falls back to the id
        assertEquals("", app.icon) // http is dropped, https only
        assertEquals("default", app.sound) // Unknown class degrades to default
        assertEquals(1, app.topics.size)
        assertEquals("default", app.topics[0].sound) // Inherits the app sound
        assertEquals("", app.topics[0].name)
    }

    @Test
    fun tokenRequestNeverExpires() {
        // Review blocker: without "expires": 0 the server mints a 72 h token and the phone goes dark on day 3
        val json = com.google.gson.JsonParser.parseString(CatalogApi.tokenRequestJson("android-Pixel 8-a1b2c3")).asJsonObject
        assertEquals("android-Pixel 8-a1b2c3", json.get("label").asString)
        assertTrue(json.has("expires"))
        assertEquals(0L, json.get("expires").asLong)
        assertEquals(2, json.size())
    }

    @Test
    fun tokenLabelIsPerDevice() {
        assertEquals("android-Pixel 8-a1b2c3", CatalogApi.tokenLabel("Pixel 8", "a1b2c3"))
        assertEquals("android-Pixel 8", CatalogApi.tokenLabel("Pixel 8", null))
        assertEquals("android-device", CatalogApi.tokenLabel("  ", ""))
        assertTrue(CatalogApi.tokenLabel("x".repeat(500), "abcdef").length <= 127)
    }

    @Test
    fun backfillUsesServerRetentionNotSinceAll() {
        assertEquals("90d", CatalogSync.backfillSince(90))
        assertEquals("30d", CatalogSync.backfillSince(30))
        assertEquals("90d", CatalogSync.backfillSince(0)) // Server reported none (12 h default) -> our cap, never "all"
        assertEquals("90d", CatalogSync.backfillSince(365)) // Capped
        assertEquals("1d", CatalogSync.backfillSince(1))
    }

    @Test
    fun parsingRejectsGarbage() {
        assertNull(CatalogApi.parseCatalog("not json"))
        assertEquals("tk_abc", CatalogApi.parseToken("""{"token":"tk_abc","last_access":1}"""))
        assertNull(CatalogApi.parseToken("""{"code":40101}"""))
    }
}
