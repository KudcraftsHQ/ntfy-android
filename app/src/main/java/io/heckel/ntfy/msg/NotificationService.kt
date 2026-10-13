package io.heckel.ntfy.msg

import android.app.*
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.db.*
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.ui.Colors
import io.heckel.ntfy.ui.DetailActivity
import io.heckel.ntfy.ui.MainActivity
import io.heckel.ntfy.util.*
import java.util.*
import androidx.core.net.toUri
import kotlinx.coroutines.launch

class NotificationService(val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val repository = Repository.getInstance(context)
    private val markwon = MarkwonFactory.createForNotification(context)
    private val appBaseUrl = context.getString(R.string.app_base_url)

    fun display(subscription: Subscription, notification: Notification) {
        Log.d(TAG, "Displaying notification $notification")
        displayInternal(subscription, notification)
    }

    fun update(subscription: Subscription, notification: Notification) {
        val active = notificationManager.activeNotifications.find { it.id == notification.notificationId } != null
        if (active) {
            Log.d(TAG, "Updating notification $notification")
            displayInternal(subscription, notification, update = true)
        }
    }

    fun cancel(notification: Notification) {
        if (notification.notificationId != 0) {
            Log.d(TAG, "Cancelling notification ${notification.id}: ${decodeMessage(notification)}")
            cancel(notification.notificationId)
        }
    }

    fun cancel(notificationId: Int) {
        if (notificationId != 0) {
            Log.d(TAG, "Cancelling notification $notificationId")
            val group = try {
                notificationManager.activeNotifications.find { it.id == notificationId }?.notification?.group
            } catch (_: Exception) {
                null
            }
            notificationManager.cancel(notificationId)
            group?.let { refreshGroupSummary(it, excludeId = notificationId) }
        }
    }

    fun createDefaultNotificationChannels() {
        maybeCreateNotificationGroup(DEFAULT_GROUP, context.getString(R.string.channel_notifications_group_default_name))
        ALL_PRIORITIES.forEach { priority -> maybeCreateNotificationChannel(DEFAULT_GROUP, priority) }
    }

    fun createSubscriptionNotificationChannels(subscription: Subscription) {
        val groupId = subscriptionGroupId(subscription)
        maybeCreateNotificationGroup(groupId, subscriptionGroupName(subscription))
        ALL_PRIORITIES.forEach { priority -> maybeCreateNotificationChannel(groupId, priority) }
    }

    fun deleteSubscriptionNotificationChannels(subscription: Subscription) {
        val groupId = subscriptionGroupId(subscription)
        ALL_PRIORITIES.forEach { priority -> maybeDeleteNotificationChannel(groupId, priority) }
        maybeDeleteNotificationGroup(groupId)
    }

    private fun subscriptionGroupId(subscription: Subscription): String {
        return SUBSCRIPTION_GROUP_PREFIX + subscription.id.toString()
    }

    private fun subscriptionGroupName(subscription: Subscription): String {
        return subscription.displayName ?: subscriptionTopicShortUrl(subscription)
    }

    private fun displayInternal(subscription: Subscription, notification: Notification, update: Boolean = false) {
        val title = formatTitle(appBaseUrl, subscription, notification)
        val catalogApp = subscription.catalogApp // kudcrafts: catalog, channels per (app, sound class, sound theme)
        val theme = repository.getSoundTheme()
        val groupId = if (catalogApp != null) {
            CatalogChannels.groupId(catalogApp)
        } else if (subscription.dedicatedChannels) {
            subscriptionGroupId(subscription)
        } else {
            DEFAULT_GROUP
        }
        val channelId = if (catalogApp != null) {
            CatalogChannels.channelId(catalogApp, subscription.catalogSound, theme, notification.priority)
        } else {
            toChannelId(groupId, notification.priority)
        }
        val insistent = notification.priority == PRIORITY_MAX &&
                (repository.getInsistentMaxPriorityEnabled() || subscription.insistent == Repository.INSISTENT_MAX_PRIORITY_ENABLED)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Colors.notificationIcon(context))
            .setContentTitle(title)
            .setWhen(notification.timestamp * 1000) // Set timestamp (convert seconds to millis)
            .setShowWhen(true)
            .setOnlyAlertOnce(true) // Do not vibrate or play sound if already showing (updates!)
            .setAutoCancel(true) // Cancel when notification is clicked
        setStyleAndText(builder, subscription, notification) // Preview picture or big text style
        val group = notificationGroup(subscription)
        val groupName = if (subscription.catalogApp != null) subscription.catalogAppName ?: subscription.catalogApp else displayName(appBaseUrl, subscription)
        builder
            .setGroup(group) // kudcrafts: our own group, so Android never auto-groups us under a summary that opens the app
            .addExtras(Bundle().apply { putString(EXTRA_GROUP_NAME, groupName) })
        setClickAction(builder, subscription, notification, group)
        maybeSetDeleteIntent(builder, insistent)
        maybeSetSound(builder, insistent, update)
        maybeSetProgress(builder, notification)
        maybeAddOpenAction(builder, notification)
        maybeAddBrowseAction(builder, notification)
        maybeAddDownloadAction(builder, notification)
        maybeAddCancelAction(builder, notification)
        maybeAddUserActions(builder, notification)

        if (catalogApp != null) {
            maybeCreateCatalogChannels(catalogApp, subscription.catalogAppName ?: catalogApp, subscription.catalogSound, theme)
            maybePlayInsistentSound(CatalogChannels.channelId(catalogApp, subscription.catalogSound, theme, CatalogChannels.Band.MAX), insistent)
        } else {
            maybeCreateNotificationGroup(groupId, subscriptionGroupName(subscription))
            maybeCreateNotificationChannel(groupId, notification.priority)
            maybePlayInsistentSound(toChannelId(groupId, PRIORITY_MAX), insistent)
        }

        val built = builder.build()
        notificationManager.notify(notification.notificationId, built)
        refreshGroupSummary(group, current = notification.notificationId to built)
    }

    private fun maybeSetDeleteIntent(builder: NotificationCompat.Builder, insistent: Boolean) {
        if (!insistent) {
            return
        }
        val intent = Intent(context, DeleteBroadcastReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
        builder.setDeleteIntent(pendingIntent)
    }

    private fun maybeSetSound(builder: NotificationCompat.Builder, insistent: Boolean, update: Boolean) {
        // Note that the sound setting is ignored in Android => O (26) in favor of notification channels
        val hasSound = !update && !insistent
        if (hasSound) {
            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            builder.setSound(defaultSoundUri)
        } else {
            builder.setSound(null)
        }
    }

    private fun setStyleAndText(builder: NotificationCompat.Builder, subscription: Subscription, notification: Notification) {
        val contentUri = notification.attachment?.contentUri
        val isSupportedImage = supportedImage(notification.attachment?.type)
        val subscriptionIcon = subscription.icon?.readBitmapFromUriOrNull(context)
        val notificationIcon = notification.icon?.contentUri?.readBitmapFromUriOrNull(context)
        val largeIcon = notificationIcon ?: subscriptionIcon
        if (contentUri != null && isSupportedImage) {
            try {
                val attachmentBitmap = contentUri.readBitmapFromUri(context)
                builder
                    .setContentText(maybeAppendActionErrors(maybeMarkdown(formatMessage(notification), notification), notification))
                    .setLargeIcon(attachmentBitmap)
                    .setStyle(NotificationCompat.BigPictureStyle()
                        .bigPicture(attachmentBitmap)
                        .bigLargeIcon(largeIcon)) // May be null
            } catch (_: Exception) {
                val message = maybeAppendActionErrors(formatMessageMaybeWithAttachmentInfos(notification), notification)
                builder
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            }
        } else {
            val message = maybeAppendActionErrors(formatMessageMaybeWithAttachmentInfos(notification), notification)
            builder
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setLargeIcon(largeIcon) // May be null
        }
    }

    private fun formatMessageMaybeWithAttachmentInfos(notification: Notification): CharSequence {
        val message = maybeMarkdown(formatMessage(notification), notification)
        val attachment = notification.attachment ?: return message
        val attachmentInfos = if (attachment.size != null) {
            "${attachment.name}, ${formatBytes(attachment.size)}"
        } else {
            attachment.name
        }
        if (attachment.progress in 0..99) {
            return context.getString(R.string.notification_popup_file_downloading, attachmentInfos, attachment.progress, message)
        }
        if (attachment.progress == ATTACHMENT_PROGRESS_DONE) {
            return context.getString(R.string.notification_popup_file_download_successful, message, attachmentInfos)
        }
        if (attachment.progress == ATTACHMENT_PROGRESS_FAILED) {
            return context.getString(R.string.notification_popup_file_download_failed, message, attachmentInfos)
        }
        return context.getString(R.string.notification_popup_file, message, attachmentInfos)
    }

    /**
     * kudcrafts: a tap opens the message's click URL, or the topic when there is none (NotificationClickActivity).
     * The request code is the notification id, so every notification keeps its own PendingIntent and an update
     * (icon downloaded, attachment progress) replaces it instead of piling up random ones.
     */
    private fun setClickAction(builder: NotificationCompat.Builder, subscription: Subscription, notification: Notification, group: String) {
        builder.setContentIntent(clickPendingIntent(subscription, notification, group))
    }

    fun clickPendingIntent(subscription: Subscription, notification: Notification, group: String?): PendingIntent {
        val intent = NotificationClickActivity.intent(
            context = context,
            click = notification.click,
            subscriptionId = subscription.id,
            baseUrl = subscription.baseUrl,
            topic = subscription.topic,
            displayName = displayName(appBaseUrl, subscription),
            instant = subscription.instant,
            mutedUntil = subscription.mutedUntil,
            sequenceId = notification.sequenceId.ifEmpty { notification.id },
            group = group
        )
        return PendingIntent.getActivity(context, notification.notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notificationGroup(subscription: Subscription): String {
        return subscription.catalogApp?.let { GROUP_KEY_PREFIX + "app-" + it } ?: (GROUP_KEY_PREFIX + "sub-" + subscription.id)
    }

    /**
     * kudcrafts: with several notifications in one group, post a summary whose tap does what tapping the newest
     * message does (its click URL). Without our own summary, Android auto-groups 4+ notifications under a system
     * summary whose tap opens the app instead of the URL. Removed again when the group is empty.
     */
    fun refreshGroupSummary(group: String, excludeId: Int = 0, current: Pair<Int, android.app.Notification>? = null) {
        try {
            val summaryId = groupSummaryId(group)
            val active = notificationManager.activeNotifications
                .filter { it.notification.group == group && it.id != summaryId && it.id != excludeId && it.id != current?.first }
                .map { it.id to it.notification }
            val children = (active + listOfNotNull(current))
                .filter { (_, n) -> n.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }
                .sortedByDescending { (_, n) -> n.`when` }
            if (children.isEmpty()) {
                notificationManager.cancel(summaryId)
                return
            }
            if (children.size < 2) {
                return // A lone child shows as itself; an existing summary just stays out of the way
            }
            val newest = children.first().second
            val groupName = newest.extras?.getString(EXTRA_GROUP_NAME) ?: context.getString(R.string.app_name)
            val inbox = NotificationCompat.InboxStyle()
            children.take(5).forEach { (_, n) ->
                val title = n.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)
                val text = n.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)
                inbox.addLine(listOfNotNull(title, text).joinToString(" · "))
            }
            inbox.setSummaryText(context.resources.getQuantityString(R.plurals.kc_notification_group_summary, children.size, children.size))
            val summary = NotificationCompat.Builder(context, newest.channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(Colors.notificationIcon(context))
                .setContentTitle(groupName)
                .setContentText(newest.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE))
                .setStyle(inbox)
                .setGroup(group)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN) // The children alert, never the summary
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(newest.contentIntent)
                .build()
            notificationManager.notify(summaryId, summary)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to refresh group summary for $group", e)
        }
    }

    private fun maybeSetProgress(builder: NotificationCompat.Builder, notification: Notification) {
        val progress = notification.attachment?.progress
        if (progress in 0..99) {
            builder.setProgress(100, progress!!, false)
        } else {
            builder.setProgress(0, 0, false) // Remove progress bar
        }
    }

    private fun maybeAddOpenAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (!canOpenAttachment(notification.attachment)) {
            return
        }
        if (notification.attachment?.contentUri != null) {
            val contentUri = notification.attachment.contentUri.toUri()
            val intent = Intent(Intent.ACTION_VIEW, contentUri).apply {
                setDataAndType(contentUri, notification.attachment.type ?: "application/octet-stream") // Required for Android <= P
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_open), pendingIntent).build())
        }
    }

    private fun maybeAddBrowseAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (notification.attachment?.contentUri != null) {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_browse), pendingIntent).build())
        }
    }

    private fun maybeAddDownloadAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (notification.attachment?.contentUri == null && listOf(ATTACHMENT_PROGRESS_NONE, ATTACHMENT_PROGRESS_FAILED).contains(notification.attachment?.progress)) {
            val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
                putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_DOWNLOAD_START)
                putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_download), pendingIntent).build())
        }
    }

    private fun maybeAddCancelAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (notification.attachment?.contentUri == null && notification.attachment?.progress in 0..99) {
            val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
                putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_DOWNLOAD_CANCEL)
                putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_cancel), pendingIntent).build())
        }
    }

    private fun maybeAddUserActions(builder: NotificationCompat.Builder, notification: Notification) {
        notification.actions?.forEach { action ->
            val actionType = action.action.lowercase(Locale.getDefault())
            if (actionType == ACTION_VIEW) {
                // Hack: Action "view" with "clear=true" is a special case, because it's apparently impossible to start a
                // URL activity from PendingIntent.getActivity() and also close the notification. To clear it, we
                // launch our own Activity (ViewActionWithClearActivity) which then calls the actual activity

                if (action.clear == true) {
                    addViewUserActionWithClear(builder, notification, action)
                } else {
                    addViewUserActionWithoutClear(builder, action)
                }
            } else {
                addHttpBroadcastOrCopyUserAction(builder, notification, action)
            }
        }
    }

    /**
     * Open the URL and do NOT cancel the notification (clear=false). This uses a normal Intent with the given URL.
     * The other case is much more interesting.
     */
    private fun addViewUserActionWithoutClear(builder: NotificationCompat.Builder, action: Action) {
        try {
            val url = action.url ?: return
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, action.label, pendingIntent).build())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to add open user action", e)
        }
    }

    /**
     * HACK: Open the URL and CANCEL the notification (clear=true). This is a SPECIAL case with a horrible workaround.
     * We call our own activity ViewActionWithClearActivity and open the URL from there.
     */
    private fun addViewUserActionWithClear(builder: NotificationCompat.Builder, notification: Notification, action: Action) {
        try {
            val url = action.url ?: return
            val intent = Intent(context, ViewActionWithClearActivity::class.java).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(VIEW_ACTION_EXTRA_URL, url)
                putExtra(VIEW_ACTION_EXTRA_NOTIFICATION_ID, notification.notificationId)
                putExtra(VIEW_ACTION_EXTRA_SUBSCRIPTION_ID, notification.subscriptionId)
                putExtra(VIEW_ACTION_EXTRA_SEQUENCE_ID, notification.sequenceId)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, action.label, pendingIntent).build())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to add open user action", e)
        }
    }

    private fun addHttpBroadcastOrCopyUserAction(builder: NotificationCompat.Builder, notification: Notification, action: Action) {
        val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
            putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_USER_ACTION)
            putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            putExtra(BROADCAST_EXTRA_ACTION_ID, action.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label = formatActionLabel(action)
        builder.addAction(NotificationCompat.Action.Builder(0, label, pendingIntent).build())
    }

    /**
     * Receives the broadcast from
     * - the "http", "broadcast", and "copy" action button (the "view" action is handled differently)
     * - the "download"/"cancel" action button
     *
     * Then queues a Worker via WorkManager to execute the action in the background
     */
    class UserActionBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val type = intent.getStringExtra(BROADCAST_EXTRA_TYPE) ?: return
            val notificationId = intent.getStringExtra(BROADCAST_EXTRA_NOTIFICATION_ID) ?: return
            when (type) {
                BROADCAST_TYPE_DOWNLOAD_START -> DownloadManager.enqueue(context, notificationId, userAction = true, DownloadType.ATTACHMENT)
                BROADCAST_TYPE_DOWNLOAD_CANCEL -> DownloadManager.cancel(context, notificationId)
                BROADCAST_TYPE_USER_ACTION -> {
                    val actionId = intent.getStringExtra(BROADCAST_EXTRA_ACTION_ID) ?: return
                    UserActionManager.enqueue(context, notificationId, actionId)
                }
            }
        }
    }

    /**
     * Receives a broadcast when a notification is swiped away. This is currently
     * only called for notifications with an insistent sound.
     */
    class DeleteBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Media player: Stopping insistent ring")
            val mediaPlayer = Repository.getInstance(context).mediaPlayer
            mediaPlayer.stop()
        }
    }

    private fun detailActivityIntent(subscription: Subscription): PendingIntent? {
        val intent = Intent(context, DetailActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscription.id)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME, displayName(appBaseUrl, subscription))
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, subscription.instant)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_MUTED_UNTIL, subscription.mutedUntil)
        }
        return TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(intent) // Add the intent, which inflates the back stack
            getPendingIntent(Random().nextInt(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) // Get the PendingIntent containing the entire back stack
        }
    }

    private fun maybeCreateNotificationChannel(group: String, priority: Int) {
        // Note: To change a notification channel, you must delete the old one and create a new one!

        val channelId = toChannelId(group, priority)
        val pause = 300L
        val channel = when (priority) {
            PRIORITY_MIN -> NotificationChannel(channelId, context.getString(R.string.common_priority_min_name), NotificationManager.IMPORTANCE_MIN)
            PRIORITY_LOW -> NotificationChannel(channelId, context.getString(R.string.common_priority_low_name), NotificationManager.IMPORTANCE_LOW)
            PRIORITY_HIGH -> {
                val channel = NotificationChannel(channelId, context.getString(R.string.common_priority_high_name), NotificationManager.IMPORTANCE_HIGH)
                channel.enableVibration(true)
                channel.vibrationPattern = longArrayOf(
                    pause, 100, pause, 100, pause, 100,
                    pause, 2000
                )
                channel
            }
            PRIORITY_MAX -> {
                val channel = NotificationChannel(channelId, context.getString(R.string.common_priority_max_name), NotificationManager.IMPORTANCE_HIGH) // IMPORTANCE_MAX does not exist
                channel.enableLights(true)
                channel.enableVibration(true)
                channel.setBypassDnd(true)
                channel.vibrationPattern = longArrayOf(
                    pause, 100, pause, 100, pause, 100,
                    pause, 2000,
                    pause, 100, pause, 100, pause, 100,
                    pause, 2000,
                    pause, 100, pause, 100, pause, 100,
                    pause, 2000
                )
                channel
            }
            else -> NotificationChannel(channelId, context.getString(R.string.common_priority_default_name), NotificationManager.IMPORTANCE_DEFAULT)
        }
        channel.group = group
        notificationManager.createNotificationChannel(channel)
    }

    private fun maybeDeleteNotificationChannel(group: String, priority: Int) {
        notificationManager.deleteNotificationChannel(toChannelId(group, priority))
    }

    private fun maybeCreateNotificationGroup(id: String, name: String) {
        notificationManager.createNotificationChannelGroup(NotificationChannelGroup(id, name))
    }

    private fun maybeDeleteNotificationGroup(id: String) {
        notificationManager.deleteNotificationChannelGroup(id)
    }

    fun toChannelId(groupId: String, priority: Int): String {
        return when (priority) {
            PRIORITY_MIN -> groupId + GROUP_SUFFIX_PRIORITY_MIN
            PRIORITY_LOW -> groupId + GROUP_SUFFIX_PRIORITY_LOW
            PRIORITY_HIGH -> groupId + GROUP_SUFFIX_PRIORITY_HIGH
            PRIORITY_MAX -> groupId + GROUP_SUFFIX_PRIORITY_MAX
            else -> groupId + GROUP_SUFFIX_PRIORITY_DEFAULT
        }
    }

    private fun maybePlayInsistentSound(maxChannelId: String, insistent: Boolean) {
        if (!insistent) {
            return
        }
        try {
            val mediaPlayer = repository.mediaPlayer
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
                Log.d(TAG, "Media player: Playing insistent alarm on alarm channel")
                mediaPlayer.reset()
                mediaPlayer.setDataSource(context, getInsistentSound(maxChannelId))
                mediaPlayer.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                mediaPlayer.isLooping = true
                mediaPlayer.prepare()
                mediaPlayer.start()
            } else {
                Log.d(TAG, "Media player: Alarm volume is 0; not playing insistent alarm")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Media player: Failed to play insistent alarm", e)
        }
    }

    private fun getInsistentSound(maxChannelId: String): Uri {
        val channel = notificationManager.getNotificationChannel(maxChannelId)
        return channel?.sound ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    }

    // kudcrafts: catalog channels (spec section 7.5) and sound themes

    /** Creates the group and the three channels of one (app, sound class, theme). Creating an existing channel only renames it. */
    fun maybeCreateCatalogChannels(app: String, appName: String, sound: String?, theme: String) {
        val soundClass = CatalogSound.normalize(sound)
        maybeCreateNotificationGroup(CatalogChannels.groupId(app), appName)
        CatalogChannels.Band.entries.forEach { band ->
            notificationManager.createNotificationChannel(buildCatalogChannel(app, appName, soundClass, theme, band))
        }
    }

    /**
     * Creates channels for every (app, sound class) in use, in the theme in use, and deletes catalog channels
     * and groups that nothing uses anymore (the theme changed, the app's default sound changed, or the app left
     * the catalog). User edits to a live channel are kept by Android; a deleted channel takes its user edits
     * with it (Android brings them back only if the very same id is created again, i.e. on switching back to
     * that theme). Non-catalog channels are never touched.
     */
    fun reconcileCatalogChannels(subscriptions: List<Subscription>) {
        try {
            val theme = repository.getSoundTheme()
            subscriptions
                .filter { it.catalogApp != null }
                .distinctBy { it.catalogApp to CatalogSound.normalize(it.catalogSound) }
                .forEach { maybeCreateCatalogChannels(it.catalogApp!!, it.catalogAppName ?: it.catalogApp, it.catalogSound, theme) }
            CatalogChannels.staleChannelIds(notificationManager.notificationChannels.map { it.id }, subscriptions, theme)
                .forEach { notificationManager.deleteNotificationChannel(it) }
            CatalogChannels.staleGroupIds(notificationManager.notificationChannelGroups.map { it.id }, subscriptions)
                .forEach { notificationManager.deleteNotificationChannelGroup(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to reconcile catalog channels", e)
        }
    }

    private fun buildCatalogChannel(app: String, appName: String, soundClass: String, theme: String, band: CatalogChannels.Band): NotificationChannel {
        val id = CatalogChannels.channelId(app, soundClass, theme, band)
        val soundLabel = catalogSoundLabel(context, soundClass)
        val pause = 300L
        val shortVibration = longArrayOf(pause, 100, pause, 100, pause, 100)
        val longVibration = longArrayOf(pause, 100, pause, 100, pause, 100, pause, 2000)
        val channel = when (band) {
            CatalogChannels.Band.LOW -> NotificationChannel(id, context.getString(R.string.kc_channel_name_low, appName), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
            CatalogChannels.Band.MAX -> NotificationChannel(id, context.getString(R.string.kc_channel_name_max, appName), NotificationManager.IMPORTANCE_HIGH).apply {
                enableLights(true)
                enableVibration(true)
                setBypassDnd(true)
                vibrationPattern = longVibration + longVibration + longVibration
                setSound(catalogSoundUri(theme, CatalogSound.URGENT), catalogAudioAttributes())
            }
            CatalogChannels.Band.NORMAL -> {
                // Heads-up and vibration for the classes that want a reaction; a plain sound for the ones that only inform
                val importance = when (soundClass) {
                    CatalogSound.SILENT -> NotificationManager.IMPORTANCE_LOW
                    CatalogSound.DEFAULT, CatalogSound.CASH, CatalogSound.DONE -> NotificationManager.IMPORTANCE_DEFAULT
                    else -> NotificationManager.IMPORTANCE_HIGH // alert, urgent, fail, ask
                }
                NotificationChannel(id, context.getString(R.string.kc_channel_name, appName, soundLabel), importance).apply {
                    when (soundClass) {
                        CatalogSound.SILENT -> {
                            setSound(null, null)
                            enableVibration(false)
                        }
                        CatalogSound.DEFAULT, CatalogSound.CASH, CatalogSound.DONE -> {
                            setSound(catalogSoundUri(theme, soundClass), catalogAudioAttributes())
                            enableVibration(false)
                        }
                        CatalogSound.URGENT -> {
                            setSound(catalogSoundUri(theme, soundClass), catalogAudioAttributes())
                            enableVibration(true)
                            vibrationPattern = longVibration
                        }
                        else -> { // alert, fail, ask
                            setSound(catalogSoundUri(theme, soundClass), catalogAudioAttributes())
                            enableVibration(true)
                            vibrationPattern = shortVibration
                        }
                    }
                }
            }
        }
        channel.group = CatalogChannels.groupId(app)
        return channel
    }

    /** The bundled clip of (theme, class). Should a build lack it, fall back to the system notification sound. */
    private fun catalogSoundUri(theme: String, soundClass: String): Uri {
        return catalogClipUri(context, theme, soundClass) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    /**
     * Activity used to launch a URL.
     * .
     * Horrible hack: Action "view" with "clear=true" is a special case, because it's apparently impossible to start a
     * URL activity from PendingIntent.getActivity() and also close the notification. To clear it, we
     * launch this activity which then calls the actual activity.
     */
    class ViewActionWithClearActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            Log.d(TAG, "Created $this")
            val url = intent.getStringExtra(VIEW_ACTION_EXTRA_URL)
            val notificationId = intent.getIntExtra(VIEW_ACTION_EXTRA_NOTIFICATION_ID, 0)
            val subscriptionId = intent.getLongExtra(VIEW_ACTION_EXTRA_SUBSCRIPTION_ID, 0)
            val sequenceId = intent.getStringExtra(VIEW_ACTION_EXTRA_SEQUENCE_ID) ?: ""
            if (url == null) {
                finish()
                return
            }

            // Immediately start the actual activity
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start activity from URL $url", e)
                val message = if (e is ActivityNotFoundException) url else e.message
                Toast
                    .makeText(this, getString(R.string.detail_item_cannot_open_url, message), Toast.LENGTH_LONG)
                    .show()
            }

            // Cancel notification
            val notifier = NotificationService(this)
            notifier.cancel(notificationId)

            // Mark notification as read; We can't use lifecycleScope here, because we
            // call finish() right after, so we do this awkward ioScope thing.
            if (subscriptionId != 0L && sequenceId.isNotEmpty()) {
                val app = applicationContext as io.heckel.ntfy.app.Application
                app.ioScope.launch {
                    val repository = Repository.getInstance(app)
                    repository.markAsReadBySequenceId(subscriptionId, sequenceId)
                }
            }

            // Close this activity
            finish()
        }
    }

    private fun maybeMarkdown(message: String, notification: Notification): CharSequence {
        if (notification.contentType == "text/markdown") {
            return markwon.toMarkdown(message)
        }
        return message
    }

    companion object {
        const val ACTION_VIEW = "view"
        const val ACTION_HTTP = "http"
        const val ACTION_BROADCAST = "broadcast"
        const val ACTION_COPY = "copy"

        const val BROADCAST_EXTRA_TYPE = "type"
        const val BROADCAST_EXTRA_NOTIFICATION_ID = "notificationId"
        const val BROADCAST_EXTRA_ACTION_ID = "actionId"

        const val BROADCAST_TYPE_DOWNLOAD_START = "io.heckel.ntfy.DOWNLOAD_ACTION_START"
        const val BROADCAST_TYPE_DOWNLOAD_CANCEL = "io.heckel.ntfy.DOWNLOAD_ACTION_CANCEL"
        const val BROADCAST_TYPE_USER_ACTION = "io.heckel.ntfy.USER_ACTION_RUN"

        private const val TAG = "NtfyNotifService"

        /** kudcrafts: android.resource URI of res/raw/kc_<theme>_<class>.ogg, or null if there is no such clip (silent) */
        fun catalogClipUri(context: Context, theme: String?, soundClass: String?): Uri? {
            val name = CatalogTheme.clipName(theme, soundClass) ?: return null
            @Suppress("DiscouragedApi")
            val resId = context.resources.getIdentifier(name, "raw", context.packageName)
            return if (resId != 0) "android.resource://${context.packageName}/raw/$name".toUri() else null
        }

        fun catalogAudioAttributes(): AudioAttributes {
            return AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }

        fun catalogSoundLabel(context: Context, soundClass: String?): String {
            return when (CatalogSound.normalize(soundClass)) {
                CatalogSound.SILENT -> context.getString(R.string.kc_sound_silent)
                CatalogSound.ALERT -> context.getString(R.string.kc_sound_alert)
                CatalogSound.URGENT -> context.getString(R.string.kc_sound_urgent)
                CatalogSound.CASH -> context.getString(R.string.kc_sound_cash)
                CatalogSound.DONE -> context.getString(R.string.kc_sound_done)
                CatalogSound.FAIL -> context.getString(R.string.kc_sound_fail)
                CatalogSound.ASK -> context.getString(R.string.kc_sound_ask)
                else -> context.getString(R.string.kc_sound_default)
            }
        }

        const val DEFAULT_GROUP = "ntfy"
        private const val GROUP_KEY_PREFIX = "kc-notif-" // Notification group key (not a channel group)
        private const val EXTRA_GROUP_NAME = "kc_group_name"

        fun groupSummaryId(group: String): Int {
            val hash = ("summary:$group").hashCode()
            return if (hash == 0 || hash == Int.MIN_VALUE) 2 else -kotlin.math.abs(hash) // Negative: never clashes with message ids
        }
        private const val SUBSCRIPTION_GROUP_PREFIX = "ntfy-subscription-"
        private const val GROUP_SUFFIX_PRIORITY_MIN = "-min"
        private const val GROUP_SUFFIX_PRIORITY_LOW = "-low"
        private const val GROUP_SUFFIX_PRIORITY_DEFAULT = ""
        private const val GROUP_SUFFIX_PRIORITY_HIGH = "-high"
        private const val GROUP_SUFFIX_PRIORITY_MAX = "-max"

        private const val VIEW_ACTION_EXTRA_URL = "url"
        private const val VIEW_ACTION_EXTRA_NOTIFICATION_ID = "notificationId"
        private const val VIEW_ACTION_EXTRA_SUBSCRIPTION_ID = "subscriptionId"
        private const val VIEW_ACTION_EXTRA_SEQUENCE_ID = "sequenceId"
    }
}
