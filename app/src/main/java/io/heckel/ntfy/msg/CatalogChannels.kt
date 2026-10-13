package io.heckel.ntfy.msg

import io.heckel.ntfy.db.Subscription

/**
 * kudcrafts: catalog. Pure naming rules for per-app notification channels (spec section 7.5).
 *
 * One channel per (app, sound class, sound theme), in three priority bands. Class and theme are part
 * of the id because Android channels are immutable once created: a changed default or a changed theme
 * yields a new channel instead of an edit that Android would silently ignore.
 *
 * Ids are kc-<app>-<class>-<theme><band>. Ids of releases before sound themes were kc-<app>-<class><band>;
 * themes and classes are disjoint sets, so an old id can never equal a new one, whatever the app is called,
 * and the old channels are simply stale.
 */
object CatalogChannels {
    const val CHANNEL_PREFIX = "kc-"
    const val GROUP_PREFIX = "kc-app-"

    enum class Band(val suffix: String) {
        LOW("-low"), // Priorities 1-2
        NORMAL(""), // Priorities 3-4
        MAX("-max") // Priority 5
    }

    fun groupId(app: String) = GROUP_PREFIX + app

    fun band(priority: Int): Band = when {
        priority <= 2 -> Band.LOW
        priority >= 5 -> Band.MAX
        else -> Band.NORMAL
    }

    fun channelId(app: String, sound: String?, theme: String?, band: Band): String {
        return CHANNEL_PREFIX + app + "-" + CatalogSound.normalize(sound) + "-" + CatalogTheme.normalize(theme) + band.suffix
    }

    fun channelId(app: String, sound: String?, theme: String?, priority: Int): String = channelId(app, sound, theme, band(priority))

    /** (app, sound class) pairs that the current subscriptions need */
    fun desiredPairs(subscriptions: List<Subscription>): Set<Pair<String, String>> {
        return subscriptions
            .filter { it.catalogApp != null }
            .map { it.catalogApp!! to CatalogSound.normalize(it.catalogSound) }
            .toSet()
    }

    fun desiredChannelIds(subscriptions: List<Subscription>, theme: String?): Set<String> {
        return desiredPairs(subscriptions)
            .flatMap { (app, sound) -> Band.entries.map { channelId(app, sound, theme, it) } }
            .toSet()
    }

    fun desiredGroupIds(subscriptions: List<Subscription>): Set<String> {
        return desiredPairs(subscriptions).map { groupId(it.first) }.toSet()
    }

    /**
     * Catalog channels/groups that exist on the device but are no longer needed: another theme, another
     * class, an app that left the catalog. Never touches non-catalog ids (upstream's all start with "ntfy",
     * the sign-in prompt's is "kudcrafts_account").
     */
    fun staleChannelIds(existing: Collection<String>, subscriptions: List<Subscription>, theme: String?): List<String> {
        val desired = desiredChannelIds(subscriptions, theme)
        // Channel and group ids are separate namespaces on Android, so an app called "app" is fine here
        return existing.filter { it.startsWith(CHANNEL_PREFIX) && it !in desired }
    }

    fun staleGroupIds(existing: Collection<String>, subscriptions: List<Subscription>): List<String> {
        val desired = desiredGroupIds(subscriptions)
        return existing.filter { it.startsWith(GROUP_PREFIX) && it !in desired }
    }
}
