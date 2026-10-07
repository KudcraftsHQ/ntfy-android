package io.heckel.ntfy.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.util.Linkify
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Action
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.db.isMarkdown
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.MarkwonFactory
import io.heckel.ntfy.util.PRIORITY_HIGH
import io.heckel.ntfy.util.PRIORITY_MAX
import io.heckel.ntfy.util.copyToClipboard
import io.heckel.ntfy.util.decodeMessage
import io.heckel.ntfy.util.formatActionLabel
import io.heckel.ntfy.util.formatMessage
import io.heckel.ntfy.util.formatTitle
import io.heckel.ntfy.util.isDarkThemeOn
import io.heckel.ntfy.util.readBitmapFromUriOrNull
import io.heckel.ntfy.util.splitTags
import io.heckel.ntfy.util.unmatchedTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.bettermovementmethod.BetterLinkMovementMethod

/**
 * kudcrafts: a single message, as the web app shows it (fdroid look). Opened by tapping a row in a topic.
 * Upstream's row tap (open click URL / copy) moves into the "Open link" and "Copy" buttons here.
 */
class MessageDetailActivity : AppCompatActivity() {
    private lateinit var repository: Repository
    private var notification: Notification? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kc_message)
        repository = Repository.getInstance(this)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !isDarkThemeOn(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.kc_message_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = ""

        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return finish()
        val subscriptionId = intent.getLongExtra(EXTRA_SUBSCRIPTION_ID, 0L)
        lifecycleScope.launch(Dispatchers.IO) {
            val n = repository.getNotification(notificationId)
            val s = repository.getSubscription(subscriptionId)
            withContext(Dispatchers.Main) {
                if (n == null) {
                    finish()
                } else {
                    notification = n
                    render(n, s)
                    invalidateOptionsMenu()
                }
            }
        }
    }

    private fun render(n: Notification, s: Subscription?) {
        val appName = s?.catalogAppName ?: s?.let { displayNameOf(it) } ?: ""
        title = appName
        val iconView = findViewById<ImageView>(R.id.kc_message_icon)
        val icon = n.icon?.contentUri?.readBitmapFromUriOrNull(this) ?: s?.icon?.readBitmapFromUriOrNull(this)
        iconView.setImageBitmap(icon ?: KcStyle.letterAvatar(this, appName.ifEmpty { "?" }, 144))

        val titleView = findViewById<TextView>(R.id.kc_message_title)
        val bodyView = findViewById<TextView>(R.id.kc_message_body)
        val message = formatMessage(n)
        if (n.title.isNotEmpty()) {
            titleView.text = formatTitle(n)
        } else {
            titleView.text = message.lineSequence().firstOrNull() ?: ""
        }
        if (n.isMarkdown()) {
            MarkwonFactory.createForMessage(this).setMarkdown(bodyView, message)
        } else {
            bodyView.text = message
            Linkify.addLinks(bodyView, Linkify.WEB_URLS)
        }
        bodyView.movementMethod = BetterLinkMovementMethod.getInstance()
        bodyView.visibility = if (n.title.isEmpty() && message.lines().size <= 1) View.GONE else View.VISIBLE

        findViewById<TextView>(R.id.kc_message_date).text = KcStyle.fullTime(n.timestamp)
        val badge = findViewById<TextView>(R.id.kc_message_badge)
        when (n.priority) {
            PRIORITY_MAX -> badge.kcBadge(R.string.kc_priority_urgent, R.color.kc_urgent, R.drawable.kc_badge_urgent)
            PRIORITY_HIGH -> badge.kcBadge(R.string.kc_priority_high, R.color.kc_high, R.drawable.kc_badge_high)
            else -> badge.visibility = View.GONE
        }

        n.attachment?.contentUri?.readBitmapFromUriOrNull(this)?.let {
            findViewById<ImageView>(R.id.kc_message_attachment).apply { setImageBitmap(it); visibility = View.VISIBLE }
        }

        val buttons = findViewById<ChipGroup>(R.id.kc_message_buttons)
        buttons.removeAllViews()
        if (n.click.isNotEmpty()) {
            buttons.addView(button(getString(R.string.kc_message_open_link), primary = true, iconRes = R.drawable.ic_open_in_new_kc) { openUrl(n.click) })
        }
        n.actions.orEmpty().take(3).forEach { action ->
            buttons.addView(button(formatActionLabel(action), primary = n.click.isEmpty() && buttons.childCount == 0) { runAction(n, action) })
        }
        buttons.visibility = if (buttons.childCount > 0) View.VISIBLE else View.GONE

        val tags = unmatchedTags(splitTags(n.tags))
        findViewById<TextView>(R.id.kc_message_tags).apply {
            visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
            text = KcStyle.tagChips(this@MessageDetailActivity, tags)
        }
        val footer = n.click.ifEmpty { s?.let { "${it.baseUrl.removePrefix("https://")}/${it.topic}" } ?: "" }
        findViewById<TextView>(R.id.kc_message_footer).text = footer
    }

    private fun displayNameOf(s: Subscription): String = io.heckel.ntfy.util.displayName(getString(R.string.app_base_url), s)

    private fun TextView.kcBadge(text: Int, color: Int, background: Int) {
        setText(text)
        setTextColor(ContextCompat.getColor(context, color))
        setBackgroundResource(background)
        visibility = View.VISIBLE
    }

    private fun button(label: String, primary: Boolean, iconRes: Int? = null, onClick: () -> Unit): View {
        // Theme attrs point at Widget.Kc.Button (filled accent pill) / Widget.Kc.Button.Pill (soft pill) in the fdroid theme
        val button = MaterialButton(this, null, if (primary) R.attr.materialButtonStyle else R.attr.borderlessButtonStyle)
        button.text = label
        button.isAllCaps = false
        button.minHeight = 0
        button.minimumHeight = 0
        button.insetTop = 0
        button.insetBottom = 0
        val h = (42 * resources.displayMetrics.density).toInt()
        button.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h)
        iconRes?.let {
            button.icon = ContextCompat.getDrawable(this, it)
            button.iconSize = (16 * resources.displayMetrics.density).toInt()
            button.iconPadding = (6 * resources.displayMetrics.density).toInt()
        }
        button.setOnClickListener { onClick() }
        return button
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.detail_item_cannot_open_url, url), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open $url", e)
        }
    }

    private fun runAction(n: Notification, action: Action) {
        when (action.action) {
            NotificationService.ACTION_VIEW -> action.url?.let { openUrl(it) }
            NotificationService.ACTION_COPY -> action.value?.let { copyToClipboard(this, action.label, it) }
            else -> {
                val intent = Intent(this, NotificationService.UserActionBroadcastReceiver::class.java).apply {
                    putExtra(NotificationService.BROADCAST_EXTRA_TYPE, NotificationService.BROADCAST_TYPE_USER_ACTION)
                    putExtra(NotificationService.BROADCAST_EXTRA_NOTIFICATION_ID, n.id)
                    putExtra(NotificationService.BROADCAST_EXTRA_ACTION_ID, action.id)
                }
                sendBroadcast(intent)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val tint = ContextCompat.getColor(this, R.color.kc_ink_2)
        menu.add(0, MENU_COPY, 0, R.string.kc_message_copy).apply {
            setIcon(R.drawable.ic_content_copy_white_24dp); icon?.setTint(tint); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(0, MENU_DELETE, 1, R.string.kc_message_delete).apply {
            setIcon(R.drawable.ic_delete_white_20dp); icon?.setTint(tint); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val n = notification ?: return super.onOptionsItemSelected(item)
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            MENU_COPY -> { copyToClipboard(this, "notification", decodeMessage(n)); true }
            MENU_DELETE -> {
                lifecycleScope.launch(Dispatchers.IO) { repository.markAsDeleted(n.id) }
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val TAG = "NtfyKcMessage"
        private const val EXTRA_NOTIFICATION_ID = "kc_notification_id"
        private const val EXTRA_SUBSCRIPTION_ID = "kc_subscription_id"
        private const val MENU_COPY = 1
        private const val MENU_DELETE = 2

        fun intent(context: Context, notificationId: String, subscriptionId: Long): Intent {
            return Intent(context, MessageDetailActivity::class.java)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(EXTRA_SUBSCRIPTION_ID, subscriptionId)
        }
    }
}
