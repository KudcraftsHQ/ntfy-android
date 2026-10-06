package io.heckel.ntfy.ui

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.R
import io.heckel.ntfy.util.readBitmapFromUriOrNull

/**
 * kudcrafts: catalog. Header row above an app's topics in the main list: icon, name, unread total,
 * and an overflow menu (mute app, unmute app, Android notification settings).
 */
class AppHeaderViewHolder(itemView: View, private val actions: Actions?) : RecyclerView.ViewHolder(itemView) {
    interface Actions {
        fun onMuteApp(app: String, appName: String)
        fun onUnmuteApp(app: String)
        fun onAppNotificationSettings()
    }

    private val context = itemView.context
    private val iconView: ImageView = itemView.findViewById(R.id.main_app_header_icon)
    private val nameView: TextView = itemView.findViewById(R.id.main_app_header_name)
    private val unreadView: TextView = itemView.findViewById(R.id.main_app_header_unread)
    private val menuButton: ImageButton = itemView.findViewById(R.id.main_app_header_menu)

    fun bind(header: MainAdapter.Item.Header) {
        nameView.text = header.name
        unreadView.text = if (header.unread > 0) {
            if (header.unread <= 99) header.unread.toString() else "99+"
        } else {
            ""
        }
        val bitmap = header.icon?.readBitmapFromUriOrNull(context)
        if (bitmap != null) {
            iconView.setImageBitmap(bitmap)
            iconView.visibility = View.VISIBLE
        } else if (header.app != null) {
            iconView.setImageResource(R.drawable.ic_sms_gray_24dp)
            iconView.visibility = View.VISIBLE
        } else {
            iconView.visibility = View.GONE
        }
        val app = header.app
        if (app == null || actions == null) {
            menuButton.visibility = View.INVISIBLE
            menuButton.setOnClickListener(null)
            return
        }
        menuButton.visibility = View.VISIBLE
        menuButton.setOnClickListener { anchor ->
            val popup = PopupMenu(context, anchor)
            popup.menu.add(0, MENU_MUTE, 0, R.string.kc_main_app_header_mute)
            popup.menu.add(0, MENU_UNMUTE, 1, R.string.kc_main_app_header_unmute)
            popup.menu.add(0, MENU_SETTINGS, 2, R.string.kc_main_app_header_notification_settings)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_MUTE -> actions.onMuteApp(app, header.name)
                    MENU_UNMUTE -> actions.onUnmuteApp(app)
                    MENU_SETTINGS -> actions.onAppNotificationSettings()
                }
                true
            }
            popup.show()
        }
    }

    companion object {
        private const val MENU_MUTE = 1
        private const val MENU_UNMUTE = 2
        private const val MENU_SETTINGS = 3
    }
}
