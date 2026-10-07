package io.heckel.ntfy.ui

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * kudcrafts: renders the screens Hammas uses, light and dark, with realistic data. PNGs go to $KC_UI_SHOT_DIR.
 * These are look-at-them tests: they fail only if a screen cannot be built or drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class KcScreenshotTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        KcScreens.init(context)
    }

    private fun mode(night: Boolean) = if (night) "dark" else "light"

    private fun mainList(night: Boolean) {
        if (night) RuntimeEnvironment.setQualifiers("+night")
        KcScreens.seed(context)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        KcScreens.settle(activity)
        KcScreens.capture(activity.window.decorView, "main-${mode(night)}")
    }

    private fun topic(night: Boolean) {
        if (night) RuntimeEnvironment.setQualifiers("+night")
        val id = KcScreens.seed(context)
        val intent = Intent(context, DetailActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, id)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL, KcScreens.BASE)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC, "glitchtip")
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME, "GlitchTip")
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, true)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_MUTED_UNTIL, 0L)
        }
        val activity = Robolectric.buildActivity(DetailActivity::class.java, intent).setup().get()
        KcScreens.settle(activity)
        KcScreens.capture(activity.window.decorView, "topic-${mode(night)}")
    }

    private fun message(night: Boolean) {
        if (night) RuntimeEnvironment.setQualifiers("+night")
        val id = KcScreens.seed(context)
        val activity = Robolectric.buildActivity(MessageDetailActivity::class.java, MessageDetailActivity.intent(context, "g1", id)).setup().get()
        KcScreens.settle(activity)
        KcScreens.capture(activity.window.decorView, "message-${mode(night)}")
    }

    private fun signIn(night: Boolean) {
        if (night) RuntimeEnvironment.setQualifiers("+night")
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val dialog = CatalogLoginDialog.newInstance()
        dialog.show(activity.supportFragmentManager, CatalogLoginDialog.TAG)
        KcScreens.settle(activity)
        val decor = dialog.dialog!!.window!!.decorView
        KcScreens.layoutLikeScreen(decor, activity)
        KcScreens.capture(decor, "signin-${mode(night)}")
    }

    private fun settings(night: Boolean) {
        if (night) RuntimeEnvironment.setQualifiers("+night")
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        KcScreens.settle(activity)
        KcScreens.capture(activity.window.decorView, "settings-${mode(night)}")
    }

    @Test fun settingsLight() = settings(false)
    @Test fun settingsDark() = settings(true)
    @Test fun signInLight() = signIn(false)
    @Test fun signInDark() = signIn(true)
    @Test fun messageLight() = message(false)
    @Test fun messageDark() = message(true)
    @Test fun mainLight() = mainList(false)
    @Test fun mainDark() = mainList(true)
    @Test fun topicLight() = topic(false)
    @Test fun topicDark() = topic(true)
}
