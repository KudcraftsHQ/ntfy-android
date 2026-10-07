package io.heckel.ntfy.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import io.heckel.ntfy.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * kudcrafts: renders the real VectorDrawables (launcher foreground, themed monochrome, notification icon) with
 * Robolectric's native graphics. Asserts the status-bar icon is a white silhouette, and writes PNGs to
 * $KC_ICON_RENDER_DIR when set, to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class KcIconRenderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun render(resId: Int, px: Int, background: Int = Color.TRANSPARENT): Bitmap {
        val drawable = ContextCompat.getDrawable(context, resId)!!
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        drawable.setBounds(0, 0, px, px)
        drawable.draw(canvas)
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = System.getenv("KC_ICON_RENDER_DIR") ?: return
        File(dir).mkdirs()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun notificationIconIsAWhiteSilhouette() {
        for (px in listOf(24, 48, 96, 240)) {
            val bitmap = render(R.drawable.ic_notification, px)
            var opaque = 0
            for (x in 0 until px) for (y in 0 until px) {
                val c = bitmap.getPixel(x, y)
                if (Color.alpha(c) > 0) {
                    opaque++
                    assertTrue("non-white pixel", Color.red(c) > 240 && Color.green(c) > 240 && Color.blue(c) > 240)
                }
            }
            assertTrue("icon too faint at $px px", opaque > px * px / 4)
            save(render(R.drawable.ic_notification, px, Color.parseColor("#202124")), "vector-notification-$px.png")
        }
    }

    @Test
    fun launcherLayersRender() {
        save(render(R.drawable.ic_launcher_kc_foreground, 432, ContextCompat.getColor(context, R.color.ic_launcher_kc_background)), "vector-foreground.png")
        save(render(R.drawable.ic_launcher_kc_monochrome, 432, Color.parseColor("#1B3A30")), "vector-monochrome.png")
        save(render(R.drawable.ic_sms_gray_48dp, 192, Color.WHITE), "vector-logo-light.png")
        save(render(R.drawable.ic_sms_gray_48dp, 192, Color.parseColor("#121212")), "vector-logo-dark.png")
        val fg = render(R.drawable.ic_launcher_kc_foreground, 108)
        assertTrue(Color.alpha(fg.getPixel(54, 60)) > 0) // The mascot sits in the middle
        assertTrue(Color.alpha(fg.getPixel(2, 2)) == 0) // Nothing outside the safe zone
    }
}
