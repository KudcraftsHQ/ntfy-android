package io.heckel.ntfy.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * kudcrafts: catalog. Room 18 -> 19 is additive: existing subscriptions survive untouched and come out unmanaged.
 * Instrumented (needs a device or emulator): ./gradlew connectedFdroidDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), Database::class.java)

    @Test
    fun migrate18To19() {
        helper.createDatabase(dbName, 18).apply {
            execSQL(
                "INSERT INTO Subscription (id, baseUrl, topic, instant, mutedUntil, minPriority, autoDelete, insistent, " +
                    "lastNotificationId, icon, upAppId, upConnectorToken, displayName, dedicatedChannels) " +
                    "VALUES (7, 'https://ntfy.sh', 'mytopic', 1, 0, 0, -1, -1, NULL, NULL, NULL, NULL, 'Mine', 0)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 19, true, Database.MIGRATION_18_19)
        db.query("SELECT topic, displayName, managed, catalogApp, catalogSound FROM Subscription WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("mytopic", c.getString(0))
            assertEquals("Mine", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertTrue(c.isNull(3))
            assertTrue(c.isNull(4))
        }
    }
}
