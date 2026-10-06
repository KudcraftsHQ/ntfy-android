package io.heckel.ntfy.service

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import io.heckel.ntfy.db.CatalogFields
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.db.User
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.Catalog
import io.heckel.ntfy.msg.CatalogApi
import io.heckel.ntfy.msg.CatalogResult
import io.heckel.ntfy.msg.DownloadIconWorker
import io.heckel.ntfy.msg.DownloadManager
import io.heckel.ntfy.msg.DownloadType
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.msg.Poller
import io.heckel.ntfy.util.HttpUtil
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.SUBSCRIPTION_ICONS
import io.heckel.ntfy.util.randomSubscriptionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * kudcrafts: catalog. Keeps the local subscriptions in line with GET /v1/catalog (spec section 7.3).
 *
 * Triggers: sign-in, MainActivity.onResume, every stream (re)connect, a message on the account's
 * sync topic, and [CatalogSyncWorker] every 15 minutes. Only an HTTP 200 changes anything.
 */
object CatalogSync {
    private const val TAG = "NtfyCatalogSync"

    /** Pseudo subscription id for the account sync topic inside a connection's topic map; never stored */
    const val SYNC_SUBSCRIPTION_ID = -1L

    const val ICON_MAX_BYTES = 300 * 1024
    const val MAX_BACKFILL_DAYS = 90
    private const val ICON_RETRY_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
    private const val BACKFILL_ICON_DOWNLOADS = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val running = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)

    data class Update(val subscription: Subscription, val fields: CatalogFields)

    data class Plan(
        val add: List<Subscription>,
        val update: List<Update>,
        val remove: List<Subscription>
    ) {
        fun isEmpty() = add.isEmpty() && update.isEmpty() && remove.isEmpty()
    }

    /**
     * Pure reconcile (contract 14.5), unit-tested in CatalogSyncTest:
     * - catalog topic with no local subscription -> add as managed
     * - catalog topic with a local subscription -> refresh the catalog columns only (user mute/rename/icon stay)
     * - managed subscription no longer in the catalog -> remove
     * - unmanaged subscription: never added or removed. If the user had subscribed to a catalog topic by hand,
     *   it is decorated (grouped under its app) but stays unmanaged, and is undecorated when it leaves the catalog.
     * Only subscriptions on [baseUrl] are considered; UnifiedPush subscriptions are ignored.
     */
    fun reconcile(current: List<Subscription>, baseUrl: String, catalog: Catalog, newId: () -> Long, nowSeconds: Long): Plan {
        val wanted = LinkedHashMap<String, CatalogFields>()
        catalog.apps.forEach { app ->
            app.topics.forEach { t ->
                wanted.putIfAbsent(t.topic, CatalogFields(
                    managed = true,
                    app = app.id,
                    appName = app.name,
                    icon = app.icon,
                    sound = t.sound,
                    name = t.name
                ))
            }
        }
        val local = current.filter { it.baseUrl == baseUrl && it.upAppId == null && it.id != SYNC_SUBSCRIPTION_ID }
        val localTopics = local.map { it.topic }.toSet()
        val usedIds = current.map { it.id }.toMutableSet()

        val add = wanted
            .filterKeys { it !in localTopics }
            .map { (topic, fields) ->
                var id = newId()
                while (id in usedIds || id <= 0L) id = newId()
                usedIds.add(id)
                newManagedSubscription(id, baseUrl, topic, fields, nowSeconds)
            }
        val update = mutableListOf<Update>()
        val remove = mutableListOf<Subscription>()
        local.forEach { s ->
            val fields = wanted[s.topic]
            if (fields != null) {
                val target = fields.copy(managed = s.managed)
                if (CatalogFields.of(s) != target) update.add(Update(s, target))
            } else if (s.managed) {
                remove.add(s)
            } else if (s.catalogApp != null) {
                update.add(Update(s, CatalogFields.NONE))
            }
        }
        return Plan(add, update, remove)
    }

    /**
     * Whether to (re)download the app icon into the subscription icon. Only when the icon slot is
     * empty or still holds a catalog icon ([storedCatalogIconUrl] != null); a custom icon the user
     * picked is never replaced.
     */
    fun shouldDownloadIcon(subscription: Subscription, storedCatalogIconUrl: String?): Boolean {
        val url = subscription.catalogIcon
        if (url.isNullOrEmpty() || !url.startsWith("https://")) return false
        if (storedCatalogIconUrl == url) return false
        return subscription.icon == null || storedCatalogIconUrl != null
    }

    fun newManagedSubscription(id: Long, baseUrl: String, topic: String, fields: CatalogFields, nowSeconds: Long): Subscription {
        return Subscription(
            id = id,
            baseUrl = baseUrl,
            topic = topic,
            instant = true, // Our server has no FCM
            mutedUntil = 0,
            minPriority = Repository.MIN_PRIORITY_USE_GLOBAL,
            autoDelete = Repository.AUTO_DELETE_THREE_MONTHS_SECONDS,
            insistent = Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL,
            lastNotificationId = null,
            icon = null,
            upAppId = null,
            upConnectorToken = null,
            displayName = null,
            dedicatedChannels = false,
            managed = true,
            catalogApp = fields.app,
            catalogAppName = fields.appName,
            catalogIcon = fields.icon,
            catalogSound = fields.sound,
            catalogName = fields.name,
            lastActive = nowSeconds
        )
    }

    /** In-memory stand-in so the sync topic can travel through the normal connection callbacks */
    fun syncSubscription(baseUrl: String, topic: String): Subscription {
        return Subscription(
            id = SYNC_SUBSCRIPTION_ID,
            baseUrl = baseUrl,
            topic = topic,
            instant = true,
            mutedUntil = 0,
            minPriority = Repository.MIN_PRIORITY_USE_GLOBAL,
            autoDelete = Repository.AUTO_DELETE_NEVER,
            insistent = Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL,
            lastNotificationId = null,
            icon = null,
            upAppId = null,
            upConnectorToken = null,
            displayName = null,
            dedicatedChannels = false
        )
    }

    fun isSignedIn(repository: Repository): Boolean = repository.getCatalogBaseUrl() != null

    /** Fire-and-forget sync. Calls while a sync is running are coalesced into one follow-up run. */
    fun now(context: Context) {
        val appContext = context.applicationContext
        pending.set(true)
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (pending.getAndSet(false)) {
                    try {
                        sync(appContext)
                    } catch (e: Exception) {
                        Log.w(TAG, "Catalog sync failed: ${e.message}", e)
                    }
                }
            } finally {
                running.set(false)
            }
            if (pending.get()) now(appContext)
        }
    }

    suspend fun sync(context: Context) = mutex.withLock {
        val repository = Repository.getInstance(context)
        val baseUrl = repository.getCatalogBaseUrl() ?: return@withLock
        val user = repository.getUser(baseUrl)
        if (user == null || !user.password.startsWith(HttpUtil.ACCESS_TOKEN_PREFIX)) {
            Log.d(TAG, "No access token for $baseUrl, skipping catalog sync")
            return@withLock
        }
        when (val result = CatalogApi(context).fetch(baseUrl, user, repository.getCatalogEtag(baseUrl))) {
            is CatalogResult.NotModified -> {
                Log.d(TAG, "Catalog not modified")
                CatalogAuthPrompt.clear(context, repository)
                repository.setCatalogLastSync(System.currentTimeMillis())
                runPendingBackfills(context, repository)
                val now = System.currentTimeMillis()
                if (now - repository.getCatalogIconRetryTime() > ICON_RETRY_INTERVAL_MILLIS) {
                    retryIcons(context, repository)
                }
            }
            is CatalogResult.AuthError -> {
                // Token revoked or expired: never fail silently, ask the user to sign in again
                Log.w(TAG, "Catalog fetch refused (HTTP ${result.code}); user must sign in again")
                CatalogAuthPrompt.show(context, repository, user.username)
            }
            is CatalogResult.Failed -> Log.w(TAG, "Catalog fetch failed: ${result.message}")
            is CatalogResult.Ok -> apply(context, repository, baseUrl, result.catalog, result.etag)
        }
    }

    private suspend fun apply(context: Context, repository: Repository, baseUrl: String, catalog: Catalog, etag: String?) {
        CatalogAuthPrompt.clear(context, repository)
        val plan = reconcile(repository.getSubscriptions(), baseUrl, catalog, ::randomSubscriptionId, System.currentTimeMillis() / 1000)
        Log.d(TAG, "Catalog v${catalog.version}: add ${plan.add.size}, update ${plan.update.size}, remove ${plan.remove.size}")
        var allAdded = true
        plan.add.forEach { s ->
            try {
                repository.addSubscription(s)
                repository.setCatalogBackfillPending(s.id, true)
            } catch (e: Exception) {
                allAdded = false
                Log.w(TAG, "Unable to add ${s.topic}: ${e.message}", e)
            }
        }
        plan.update.forEach { u -> repository.updateSubscriptionCatalogFields(u.subscription.id, u.fields) }
        plan.remove.forEach { s -> removeManagedSubscription(context, repository, s) }

        val oldSyncTopic = repository.getCatalogSyncTopic(baseUrl)
        repository.setCatalogSyncTopic(baseUrl, catalog.syncTopic)
        repository.setCatalogHistoryDays(catalog.historyDays)
        // Only after the whole plan is applied: a failed add (or a crash) must not be hidden behind a 304
        repository.setCatalogEtag(baseUrl, if (allAdded) etag else null)
        repository.setCatalogLastSync(System.currentTimeMillis())

        // History first, then (re)connect: the stream picks up from the newest message we now have.
        // A failed backfill stays pending and is retried on the next sync (200 or 304).
        runPendingBackfills(context, repository)
        retryIcons(context, repository)
        val after = repository.getSubscriptions()
        NotificationService(context).reconcileCatalogChannels(after)
        if (!plan.isEmpty() || oldSyncTopic != catalog.syncTopic) {
            SubscriberServiceManager.refresh(context)
        }
    }

    /** "<n>d" for the backfill poll: the server's retention, capped, 90 days if the server reports none */
    fun backfillSince(historyDays: Int): String {
        val days = if (historyDays > 0) historyDays.coerceAtMost(MAX_BACKFILL_DAYS) else MAX_BACKFILL_DAYS
        return "${days}d"
    }

    private suspend fun runPendingBackfills(context: Context, repository: Repository) {
        val pending = repository.getCatalogBackfillPending()
        if (pending.isEmpty()) return
        val since = backfillSince(repository.getCatalogHistoryDays())
        pending.forEach { id ->
            val subscription = repository.getSubscription(id)
            if (subscription == null) {
                repository.setCatalogBackfillPending(id, false)
            } else if (backfill(context, repository, subscription, since)) {
                repository.setCatalogBackfillPending(id, false)
            }
        }
    }

    private suspend fun backfill(context: Context, repository: Repository, subscription: Subscription, since: String): Boolean {
        return try {
            val added = Poller(ApiService(context), repository).backfill(subscription, since) // One transaction
            repository.markAllAsRead(subscription.id) // History is not "new"
            added
                .filter { it.icon != null }
                .sortedByDescending { it.timestamp }
                .take(BACKFILL_ICON_DOWNLOADS)
                .forEach { DownloadManager.enqueue(context, it.id, userAction = false, DownloadType.ICON) }
            Log.d(TAG, "Backfilled ${added.size} message(s) for ${subscription.topic} (since=$since)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Backfill failed for ${subscription.topic}, will retry: ${e.message}", e)
            false
        }
    }

    /** Downloads each app icon URL once per pass (not once per topic) and writes it into every topic that wants it */
    private suspend fun retryIcons(context: Context, repository: Repository) {
        repository.setCatalogIconRetryTime(System.currentTimeMillis())
        val downloaded = mutableMapOf<String, ByteArray?>()
        repository.getSubscriptions()
            .filter { it.catalogApp != null && shouldDownloadIcon(it, repository.getCatalogIconUrl(it.id)) }
            .forEach { s ->
                val url = s.catalogIcon ?: return@forEach
                val bytes = downloaded.getOrPut(url) { fetchIcon(context, s.baseUrl, url) } ?: return@forEach
                storeIcon(context, repository, s, url, bytes)
            }
    }

    private suspend fun fetchIcon(context: Context, baseUrl: String, url: String): ByteArray? {
        return try {
            val request = HttpUtil.requestBuilder(url).build() // No credentials to third-party hosts
            val client = HttpUtil.defaultClient(context, baseUrl).newBuilder()
                .followSslRedirects(false) // https only, also after redirects
                .build()
            val bytes = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                if (response.body.contentLength() > ICON_MAX_BYTES) throw Exception("Icon larger than $ICON_MAX_BYTES bytes")
                readAtMost(response.body.byteStream(), ICON_MAX_BYTES)
            }
            if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) throw Exception("Not an image")
            bytes
        } catch (e: Exception) {
            Log.w(TAG, "Unable to download app icon: ${e.message}")
            null
        }
    }

    private suspend fun storeIcon(context: Context, repository: Repository, subscription: Subscription, url: String, bytes: ByteArray) {
        try {
            val fresh = repository.getSubscription(subscription.id) ?: return
            if (!shouldDownloadIcon(fresh, repository.getCatalogIconUrl(fresh.id))) return // User picked an icon meanwhile
            val dir = File(context.filesDir, SUBSCRIPTION_ICONS)
            if (!dir.exists() && !dir.mkdirs()) throw Exception("Cannot create $dir")
            val file = File(dir, subscription.id.toString())
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(context, DownloadIconWorker.FILE_PROVIDER_AUTHORITY, file)
            repository.updateSubscriptionIcon(subscription.id, uri.toString())
            repository.setCatalogIconUrl(subscription.id, url)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to store app icon for ${subscription.topic}: ${e.message}")
        }
    }

    private fun readAtMost(input: java.io.InputStream, max: Int): ByteArray {
        input.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > max) throw Exception("Icon larger than $max bytes")
            }
            return out.toByteArray()
        }
    }

    private suspend fun removeManagedSubscription(context: Context, repository: Repository, subscription: Subscription) {
        Log.d(TAG, "Removing ${subscription.topic}: no longer in the catalog")
        repository.removeSubscription(subscription) // Also clears the ETag and a pending backfill for managed topics
        if (repository.getCatalogIconUrl(subscription.id) != null) {
            File(File(context.filesDir, SUBSCRIPTION_ICONS), subscription.id.toString()).delete()
            repository.setCatalogIconUrl(subscription.id, null)
        }
    }

    /** Mints a token, stores it as the server's user, makes the server the default, and syncs */
    suspend fun signIn(context: Context, baseUrl: String, username: String, password: String) {
        val repository = Repository.getInstance(context)
        val token = CatalogApi(context).mintToken(baseUrl, username, password)
        val existing = repository.getUser(baseUrl)
        val user = User(baseUrl, username, token)
        val wasOurs = repository.getCatalogBaseUrl() == baseUrl && existing?.password?.startsWith(HttpUtil.ACCESS_TOKEN_PREFIX) == true
        if (existing == null) {
            repository.addUser(user)
        } else {
            if (wasOurs) {
                CatalogApi(context).deleteToken(baseUrl, existing) // Do not leave the previous device token behind
            } else if (repository.getCatalogPreviousUser(baseUrl) == null) {
                repository.setCatalogPreviousUser(baseUrl, existing) // Hand-added credentials: restored on sign-out
            }
            repository.updateUser(user)
        }
        val previous = repository.getCatalogBaseUrl()
        if (previous != null && previous != baseUrl) {
            signOutLocal(context, repository, previous)
        }
        repository.setDefaultBaseUrl(baseUrl)
        repository.setCatalogBaseUrl(baseUrl)
        repository.setCatalogEtag(baseUrl, null)
        CatalogAuthPrompt.clear(context, repository)
        SubscriberServiceManager.refresh(context) // Credentials changed -> reconnect
        now(context)
    }

    /** Revokes the token, forgets the user and removes the topics the catalog added */
    suspend fun signOut(context: Context) {
        val repository = Repository.getInstance(context)
        val baseUrl = repository.getCatalogBaseUrl() ?: return
        mutex.withLock {
            val user = repository.getUser(baseUrl)
            if (user != null && user.password.startsWith(HttpUtil.ACCESS_TOKEN_PREFIX)) {
                CatalogApi(context).deleteToken(baseUrl, user)
                repository.deleteUser(baseUrl)
            }
            repository.getCatalogPreviousUser(baseUrl)?.let { previousUser ->
                repository.addUser(previousUser) // Hand-added topics on this server keep their credentials
                repository.setCatalogPreviousUser(baseUrl, null)
            }
            signOutLocal(context, repository, baseUrl)
        }
        SubscriberServiceManager.refresh(context)
    }

    private suspend fun signOutLocal(context: Context, repository: Repository, baseUrl: String) {
        repository.getSubscriptions()
            .filter { it.baseUrl == baseUrl && it.upAppId == null }
            .forEach { s ->
                if (s.managed) {
                    removeManagedSubscription(context, repository, s)
                } else if (s.catalogApp != null) {
                    repository.updateSubscriptionCatalogFields(s.id, CatalogFields.NONE)
                }
            }
        repository.setCatalogBaseUrl(null)
        repository.setCatalogEtag(baseUrl, null)
        repository.setCatalogSyncTopic(baseUrl, null)
        CatalogAuthPrompt.clear(context, repository)
        NotificationService(context).reconcileCatalogChannels(repository.getSubscriptions())
    }
}
