package io.heckel.ntfy.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.db.ConnectionState
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.util.displayName
import io.heckel.ntfy.util.readBitmapFromUriOrNull
import java.text.DateFormat
import java.util.*

class MainAdapter(
    private val repository: Repository,
    private val onClick: (Subscription) -> Unit,
    private val onLongClick: (Subscription) -> Unit,
    private val countDrawable: Drawable,
    private val onPrimaryColor: Int,
    private val headerActions: AppHeaderViewHolder.Actions? = null // kudcrafts: catalog app headers
) :
    ListAdapter<MainAdapter.Item, RecyclerView.ViewHolder>(ItemDiffCallback) {
    val selected = mutableSetOf<Long>() // Subscription IDs

    /**
     * kudcrafts: catalog. The list is sectioned by catalog app; subscriptions without an app go under
     * "Other". Without any catalog subscription the list looks exactly like upstream (no headers).
     */
    sealed class Item {
        data class Header(val app: String?, val name: String, val icon: String?, val unread: Int) : Item()
        data class Row(val subscription: Subscription) : Item()
    }

    fun submitSubscriptions(subscriptions: List<Subscription>, otherLabel: String) {
        submitList(buildItems(subscriptions, otherLabel))
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is Item.Header -> VIEW_TYPE_HEADER
            is Item.Row -> VIEW_TYPE_ROW
        }
    }

    /* Creates and inflates view and return TopicViewHolder. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == VIEW_TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.fragment_main_app_header, parent, false)
            return AppHeaderViewHolder(view, headerActions)
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fragment_main_item, parent, false)
        return SubscriptionViewHolder(view, repository, selected, onClick, onLongClick, countDrawable, onPrimaryColor)
    }

    /* Gets current topic and uses it to bind view. */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Item.Header -> (holder as AppHeaderViewHolder).bind(item)
            is Item.Row -> (holder as SubscriptionViewHolder).bind(item.subscription)
        }
    }

    fun toggleSelection(subscriptionId: Long) {
        if (selected.contains(subscriptionId)) {
            selected.remove(subscriptionId)
        } else {
            selected.add(subscriptionId)
        }

        if (selected.isNotEmpty()) {
            val subscriptionPosition = currentList.indexOfFirst { it is Item.Row && it.subscription.id == subscriptionId }
            if (subscriptionPosition >= 0) {
                notifyItemChanged(subscriptionPosition)
            }
        }
    }

    /* ViewHolder for Topic, takes in the inflated view and the onClick behavior. */
    class SubscriptionViewHolder(
        itemView: View,
        private val repository: Repository,
        private val selected: Set<Long>,
        val onClick: (Subscription) -> Unit,
        val onLongClick: (Subscription) -> Unit,
        private val countDrawable: Drawable,
        private val onPrimaryColor: Int
    ) :
        RecyclerView.ViewHolder(itemView) {
        private var subscription: Subscription? = null
        private val context: Context = itemView.context
        private val imageView: ImageView = itemView.findViewById(R.id.main_item_image)
        private val nameView: TextView = itemView.findViewById(R.id.main_item_text)
        private val statusView: TextView = itemView.findViewById(R.id.main_item_status)
        private val dateView: TextView = itemView.findViewById(R.id.main_item_date)
        private val connectionErrorImageView: View = itemView.findViewById(R.id.main_item_connection_error_image)
        private val notificationDisabledUntilImageView: View = itemView.findViewById(R.id.main_item_notification_disabled_until_image)
        private val notificationDisabledForeverImageView: View = itemView.findViewById(R.id.main_item_notification_disabled_forever_image)
        private val instantImageView: View = itemView.findViewById(R.id.main_item_instant_image)
        private val newItemsView: TextView = itemView.findViewById(R.id.main_item_new)
        private val appBaseUrl = context.getString(R.string.app_base_url)

        fun bind(subscription: Subscription) {
            this.subscription = subscription
            val isUnifiedPush = subscription.upAppId != null
            var statusMessage = if (isUnifiedPush) {
                context.getString(R.string.main_item_status_unified_push, subscription.upAppId)
            } else if (subscription.totalCount == 1) {
                context.getString(R.string.main_item_status_text_one, subscription.totalCount)
            } else {
                context.getString(R.string.main_item_status_text_not_one, subscription.totalCount)
            }
            if (subscription.instant && subscription.connectionDetails.state == ConnectionState.CONNECTING) {
                statusMessage += ", " + context.getString(R.string.main_item_status_reconnecting)
            }
            val date = Date(subscription.lastActive * 1000)
            val dateStr = DateFormat.getDateInstance(DateFormat.SHORT).format(date)
            val moreThanOneDay = System.currentTimeMillis()/1000 - subscription.lastActive > 24 * 60 * 60
            val sameDay = dateStr == DateFormat.getDateInstance(DateFormat.SHORT).format(Date()) // Omg this is horrible
            val dateText = if (subscription.lastActive == 0L) {
                ""
            } else if (sameDay) {
                DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
            } else if (!moreThanOneDay) {
                context.getString(R.string.main_item_date_yesterday)
            } else {
                dateStr
            }
            val globalMutedUntil = repository.getGlobalMutedUntil()
            val showMutedForeverIcon = (subscription.mutedUntil == 1L || globalMutedUntil == 1L) && !isUnifiedPush
            val showMutedUntilIcon = !showMutedForeverIcon && (subscription.mutedUntil > 1L || globalMutedUntil > 1L) && !isUnifiedPush
            if (subscription.icon != null) {
                imageView.setImageBitmap(subscription.icon.readBitmapFromUriOrNull(context))
            } else {
                imageView.setImageResource(R.drawable.ic_sms_gray_24dp)
            }
            nameView.text = displayName(appBaseUrl, subscription)
            statusView.text = if (subscription.catalogApp != null && !subscription.lastMessage.isNullOrBlank()) {
                subscription.lastMessage.lineSequence().first() // kudcrafts: catalog rows show the last message
            } else {
                statusMessage
            }
            dateView.text = dateText
            dateView.visibility = View.VISIBLE
            val showConnectionError = subscription.instant && subscription.connectionDetails.hasError()
            connectionErrorImageView.visibility = if (showConnectionError) View.VISIBLE else View.GONE
            notificationDisabledUntilImageView.visibility = if (showMutedUntilIcon) View.VISIBLE else View.GONE
            notificationDisabledForeverImageView.visibility = if (showMutedForeverIcon) View.VISIBLE else View.GONE
            instantImageView.visibility = if (subscription.instant && BuildConfig.FIREBASE_AVAILABLE) View.VISIBLE else View.GONE
            if (isUnifiedPush || subscription.newCount == 0) {
                newItemsView.visibility = View.GONE
            } else {
                newItemsView.visibility = View.VISIBLE
                newItemsView.text = if (subscription.newCount <= 99) subscription.newCount.toString() else "99+"
                newItemsView.setTextColor(onPrimaryColor)
                newItemsView.background = countDrawable
            }
            itemView.setOnClickListener { onClick(subscription) }
            itemView.setOnLongClickListener { onLongClick(subscription); true }
            if (selected.contains(subscription.id)) {
                itemView.setBackgroundColor(Colors.itemSelectedBackground(context))
            } else {
                itemView.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    object ItemDiffCallback : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean {
            return when {
                oldItem is Item.Row && newItem is Item.Row -> oldItem.subscription.id == newItem.subscription.id
                oldItem is Item.Header && newItem is Item.Header -> oldItem.app == newItem.app
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        const val TAG = "NtfyMainAdapter"
        private const val VIEW_TYPE_HEADER = 1
        private const val VIEW_TYPE_ROW = 2

        /** Pure: sections by catalog app (sorted by app name), then "Other". No headers if nothing is from the catalog. */
        fun buildItems(subscriptions: List<Subscription>, otherLabel: String): List<Item> {
            val (catalog, other) = subscriptions.partition { it.catalogApp != null }
            if (catalog.isEmpty()) {
                return other.map { Item.Row(it) }
            }
            val items = mutableListOf<Item>()
            catalog
                .groupBy { it.catalogApp!! }
                .entries
                .sortedWith(compareBy({ (it.value.first().catalogAppName ?: it.key).lowercase() }, { it.key }))
                .forEach { (app, subs) ->
                    val name = subs.first().catalogAppName ?: app
                    val icon = subs.firstNotNullOfOrNull { it.icon }
                    items.add(Item.Header(app, name, icon, subs.sumOf { it.newCount }))
                    subs.forEach { items.add(Item.Row(it)) }
                }
            if (other.isNotEmpty()) {
                items.add(Item.Header(null, otherLabel, null, other.sumOf { it.newCount }))
                other.forEach { items.add(Item.Row(it)) }
            }
            return items
        }
    }
}
