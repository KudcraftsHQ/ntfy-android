package io.heckel.ntfy.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.work.testing.WorkManagerTestInitHelper
import io.heckel.ntfy.db.Action
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import java.io.File

/** kudcrafts: sample data and capture helpers for the screenshot tests (realistic FaceMap/Kudtrading/GlitchTip). */
object KcScreens {
    const val BASE = "https://ntfy.kudcrafts.com"
    val now = System.currentTimeMillis() / 1000

    fun outDir(): File? = System.getenv("KC_UI_SHOT_DIR")?.let { File(it).apply { mkdirs() } }

    fun init(context: Context) {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun avatar(context: Context, name: String, bg: Int, fg: Int, letter: String): String {
        val px = 192
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = bg
        c.drawCircle(px / 2f, px / 2f, px / 2f, p)
        p.color = fg
        p.textSize = 110f
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        c.drawText(letter, px / 2f, px / 2f + 40f, p)
        val f = File(context.filesDir, "shot-$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f).toString()
    }

    fun sub(id: Long, topic: String, app: String?, appName: String?, name: String?, sound: String = "default", icon: String? = null) = Subscription(
        id = id, baseUrl = BASE, topic = topic, instant = true, mutedUntil = 0, minPriority = 0, autoDelete = -1, insistent = -1,
        lastNotificationId = null, icon = icon, upAppId = null, upConnectorToken = null, displayName = null, dedicatedChannels = false,
        managed = app != null, catalogApp = app, catalogAppName = appName, catalogIcon = null, catalogSound = sound, catalogName = name
    )

    fun msg(id: String, subId: Long, agoSeconds: Long, title: String, message: String, priority: Int = 3, tags: String = "",
            click: String = "", markdown: Boolean = false, unread: Boolean = true, actions: List<Action>? = null) = Notification(
        id = id, subscriptionId = subId, timestamp = now - agoSeconds, sequenceId = id, title = title, message = message,
        contentType = if (markdown) "text/markdown" else "", encoding = "", notificationId = if (unread) id.hashCode() else 0,
        priority = priority, tags = tags, click = click, icon = null, actions = actions, attachment = null, deleted = false
    )

    /** Seeds the database; returns the GlitchTip subscription id (the richest topic). */
    fun seed(context: Context): Long {
        val repo = Repository.getInstance(context)
        repo.setBatteryOptimizationsRemindTime(Repository.BATTERY_OPTIMIZATIONS_REMIND_TIME_NEVER) // Banners dismissed
        repo.setWebSocketRemindTime(Repository.WEBSOCKET_REMIND_TIME_NEVER)
        val glitch = avatar(context, "glitchtip", Color.parseColor("#E5245B"), Color.WHITE, "G")
        val facemap = avatar(context, "facemap", Color.parseColor("#D9705F"), Color.WHITE, "☺")
        val coolify = avatar(context, "coolify", Color.parseColor("#6B37E6"), Color.WHITE, "C")
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            listOf(
                sub(11, "glitchtip", "glitchtip", "GlitchTip", null, "urgent", glitch),
                sub(21, "facemap-orders", "facemap", "FaceMap", "Orders", "alert", facemap),
                sub(22, "facemap-ops", "facemap", "FaceMap", "Ops", "default", facemap),
                sub(31, "kudtrading", "kudtrading", "Kudtrading", null),
                sub(41, "coolify", "coolify", "Coolify", null, "default", coolify),
                sub(51, "typemap-sales", "typemap", "TypeMap", "Sales"),
                sub(61, "my-backups", null, null, null)
            ).forEach { if (repo.getSubscription(it.id) == null) repo.addSubscription(it) } // DB singleton outlives a test
            listOf(
                msg("g1", 11, 180, "🚨 TypeError in createOrder", "**TypeError: Cannot read properties of undefined (reading 'id')**\n\n- **Project:** facemap\n- **Culprit:** `api/orders.ts` in `createOrder`\n- **Environment:** production\n- **Events:** 37 in the last 10 minutes\n\n[View issue FACEMAP-2K](https://errors.kudcrafts.com/kudcrafts/issues/280)",
                    priority = 5, tags = "facemap", click = "https://errors.kudcrafts.com/kudcrafts/issues/280", markdown = true,
                    actions = listOf(Action("a1", "view", "Open issue", true, "https://errors.kudcrafts.com/kudcrafts/issues/280", null, null, null, null, null, null, null, null),
                        Action("a2", "http", "Resolve", true, "https://errors.kudcrafts.com/api/resolve", "POST", null, null, null, null, null, null, null))),
                msg("g2", 11, 7_200, "Slow transaction: /api/checkout", "p95 2.4 s over the last hour (threshold 1.5 s). 412 events.", priority = 4, tags = "facemap,perf", unread = false),
                msg("g3", 11, 90_000, "Resolved: ZeptoMail timeout", "No new events for 24 hours.", priority = 2, unread = false),
                msg("f1", 21, 540, "💰 New order · Rp 349.000", "Paket Glow Up — Dinda Pratiwi\nPaid via QRIS (Midtrans) · order fm_8KQ2",
                    click = "https://board.kudcrafts.com/facemap/orders/fm_8KQ2"),
                msg("f2", 22, 3_000, "Pipeline healthy again", "Render queue drained, 0 jobs waiting.", priority = 2),
                msg("k1", 31, 1_560, "ℹ️ 📦 Warehouse batch submitted · hfparts", "Batch #2291 submitted by Rizky\nBrake pad HF-221 · 40 · A-03\nOil filter OF-9 · 120 · B-11\nAwaiting QC.",
                    tags = "warehouse,hfparts", click = "https://trading.kudcrafts.com/warehouse/batches/2291"),
                msg("c1", 41, 840, "✅ Deployment succeeded · facemap-web", "Commit 3f9c2ab — fix: order total rounding", unread = false,
                    click = "https://server.kudcrafts.com/project/fm/deployment/41"),
                msg("t1", 51, 2_460, "💰 Payment received · Rp 199.000", "Pro plan, yearly — doku raka@studio.id"),
                msg("b1", 61, 86_400 * 2, "Nightly backup OK", "412 MB, 3 min 12 s", unread = false)
            ).forEach { repo.addNotification(it) }
        }
        return 11
    }

    fun settle(activity: Activity, rounds: Int = 12) {
        repeat(rounds) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(80)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Dialog windows are not laid out by Robolectric; give them the activity's size */
    fun layoutLikeScreen(view: View, activity: Activity) {
        val root = activity.window.decorView
        val w = root.width.takeIf { it > 0 } ?: 1179
        val h = root.height.takeIf { it > 0 } ?: 2556
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    fun capture(view: View, name: String): Bitmap {
        val w = view.width.takeIf { it > 0 } ?: 1080
        val h = view.height.takeIf { it > 0 } ?: 2340
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        outDir()?.let { dir -> File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return bmp
    }
}
