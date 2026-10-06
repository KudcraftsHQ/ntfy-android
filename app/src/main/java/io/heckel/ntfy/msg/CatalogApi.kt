package io.heckel.ntfy.msg

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.User
import io.heckel.ntfy.util.HttpUtil
import io.heckel.ntfy.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * kudcrafts: catalog. Talks to the server's catalog and token endpoints (spec section 3.1 and 14).
 *
 * The DTOs are deliberately nullable: Gson ignores Kotlin nullability, so [Catalog.normalize] turns
 * whatever arrived into a clean, non-null model before anything else sees it.
 */
class CatalogApi(private val context: Context) {
    private val repository = Repository.getInstance(context)

    suspend fun fetch(baseUrl: String, user: User, etag: String?): CatalogResult {
        val url = "$baseUrl/v1/catalog"
        val customHeaders = repository.getCustomHeaders(baseUrl)
        val builder = HttpUtil.requestBuilder(url, user, customHeaders)
        if (!etag.isNullOrEmpty()) {
            builder.addHeader("If-None-Match", etag)
        }
        return try {
            HttpUtil.defaultClient(context, baseUrl).newCall(builder.build()).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val catalog = parseCatalog(response.body.string())
                        if (catalog == null) {
                            CatalogResult.Failed("Invalid catalog JSON")
                        } else {
                            CatalogResult.Ok(catalog, response.header("ETag"))
                        }
                    }
                    304 -> CatalogResult.NotModified
                    401, 403 -> CatalogResult.AuthError(response.code)
                    else -> CatalogResult.Failed("Unexpected response ${response.code} from $url")
                }
            }
        } catch (e: Exception) {
            CatalogResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Mints an access token with username + password (Basic auth), see contract 14.4.
     * Throws [ApiService.UnauthorizedException] on 401/403.
     */
    suspend fun mintToken(baseUrl: String, username: String, password: String): String {
        val url = "$baseUrl/v1/account/token"
        val basicUser = User(baseUrl, username, password)
        val body = Gson().toJson(mapOf("label" to tokenLabel())).toRequestBody(JSON)
        val request = HttpUtil.requestBuilder(url, null, repository.getCustomHeaders(baseUrl))
            .header("Authorization", okhttp3.Credentials.basic(username, password, Charsets.UTF_8))
            .post(body)
            .build()
        HttpUtil.defaultClient(context, baseUrl).newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw ApiService.UnauthorizedException(basicUser)
            } else if (!response.isSuccessful) {
                throw Exception("Unexpected response ${response.code} when signing in")
            }
            val token = parseToken(response.body.string())
            if (token == null || !token.startsWith(HttpUtil.ACCESS_TOKEN_PREFIX)) {
                throw Exception("Server did not return an access token")
            }
            return token
        }
    }

    /** Revokes the token on the server. Best effort: sign-out continues locally if this fails. */
    suspend fun deleteToken(baseUrl: String, user: User) {
        val url = "$baseUrl/v1/account/token"
        try {
            val request = HttpUtil.requestBuilder(url, user, repository.getCustomHeaders(baseUrl))
                .addHeader("X-Token", user.password)
                .delete()
                .build()
            HttpUtil.defaultClient(context, baseUrl).newCall(request).execute().use { response ->
                Log.d(TAG, "Token revoke returned ${response.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to revoke token: ${e.message}", e)
        }
    }

    private fun tokenLabel(): String {
        return "android-${Build.MODEL}".take(200)
    }

    companion object {
        private const val TAG = "NtfyCatalogApi"
        private val JSON = "application/json".toMediaType()
        private val gson = Gson()

        /** Parses and normalizes a GET /v1/catalog body; null if it is not a catalog. Pure, unit-tested. */
        fun parseCatalog(json: String): Catalog? {
            return try {
                gson.fromJson(json, CatalogResponse::class.java)?.normalize()
            } catch (e: Exception) {
                null
            }
        }

        fun parseToken(json: String): String? {
            return try {
                gson.fromJson(json, TokenResponse::class.java)?.token
            } catch (e: Exception) {
                null
            }
        }
    }
}

sealed class CatalogResult {
    data class Ok(val catalog: Catalog, val etag: String?) : CatalogResult()
    object NotModified : CatalogResult()
    data class AuthError(val code: Int) : CatalogResult()
    data class Failed(val message: String) : CatalogResult()
}

/** Normalized catalog. Every field is non-null; sounds are always one of [CatalogSound.ALL]. */
data class Catalog(
    val version: Long,
    val baseUrl: String,
    val historyDays: Int,
    val syncTopic: String,
    val apps: List<CatalogApp>
)

data class CatalogApp(
    val id: String,
    val name: String,
    val icon: String, // "" = none
    val sound: String,
    val topics: List<CatalogTopic>
)

data class CatalogTopic(
    val topic: String,
    val name: String, // "" = show topic id
    val sound: String, // Resolved by the server (topic override or app default)
    val permission: String
)

object CatalogSound {
    const val SILENT = "silent"
    const val DEFAULT = "default"
    const val ALERT = "alert"
    const val URGENT = "urgent"
    val ALL = listOf(SILENT, DEFAULT, ALERT, URGENT)

    /** Anything unknown (a newer server, a typo) degrades to the platform default, never to silence. */
    fun normalize(sound: String?): String = if (sound in ALL) sound!! else DEFAULT
}

private data class CatalogResponse(
    @SerializedName("version") val version: Long?,
    @SerializedName("base_url") val baseUrl: String?,
    @SerializedName("history_days") val historyDays: Int?,
    @SerializedName("sync_topic") val syncTopic: String?,
    @SerializedName("apps") val apps: List<CatalogAppResponse?>?
) {
    fun normalize(): Catalog = Catalog(
        version = version ?: 0L,
        baseUrl = baseUrl ?: "",
        historyDays = historyDays ?: 0,
        syncTopic = syncTopic ?: "",
        apps = apps.orEmpty().filterNotNull().mapNotNull { it.normalize() }
    )
}

private data class CatalogAppResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("icon") val icon: String?,
    @SerializedName("sound") val sound: String?,
    @SerializedName("topics") val topics: List<CatalogTopicResponse?>?
) {
    fun normalize(): CatalogApp? {
        val appId = id?.takeIf { it.isNotBlank() } ?: return null
        val appSound = CatalogSound.normalize(sound)
        val safeIcon = icon?.takeIf { it.startsWith("https://") } ?: "" // https only (spec section 11)
        return CatalogApp(
            id = appId,
            name = name?.takeIf { it.isNotBlank() } ?: appId,
            icon = safeIcon,
            sound = appSound,
            topics = topics.orEmpty().filterNotNull().mapNotNull { t ->
                val topic = t.topic?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CatalogTopic(
                    topic = topic,
                    name = t.name ?: "",
                    sound = if (t.sound.isNullOrEmpty()) appSound else CatalogSound.normalize(t.sound),
                    permission = t.permission ?: "read-only"
                )
            }
        )
    }
}

private data class CatalogTopicResponse(
    @SerializedName("topic") val topic: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("sound") val sound: String?,
    @SerializedName("permission") val permission: String?
)

private data class TokenResponse(
    @SerializedName("token") val token: String?
)
