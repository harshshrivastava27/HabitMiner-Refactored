package com.habitminer.update

import android.content.Context
import com.habitminer.analytics.ReleaseInfo
import com.habitminer.analytics.UpdateLogic
import com.habitminer.data.PrefsKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks GitHub which releases exist, at most once a day. The request carries no identifier and
 * no body; it's conditional (ETag), so an unchanged answer costs nothing against GitHub's
 * rate limit, and a 403/429 backs the next check off.
 */
@Singleton
class UpdateChecker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs get() = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        var enabled: Boolean
            get() = prefs.getBoolean(PrefsKeys.UPDATE_CHECK_ENABLED, true)
            set(value) = prefs.edit().putBoolean(PrefsKeys.UPDATE_CHECK_ENABLED, value).apply()

        val installedCode: Int
            get() =
                runCatching {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).let {
                        if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode
                    }
                }.getOrDefault(0)

        val installedName: String
            get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

        /** The update waiting to be installed, from the last answer, or null. */
        fun pending(): ReleaseInfo? =
            runCatching { UpdateJson.fromCache(prefs.getString(PrefsKeys.UPDATE_CACHED, null)) }.getOrNull()
                ?.let { UpdateLogic.newest(listOf(it), installedCode, prefs.getString(PrefsKeys.UPDATE_SKIPPED, null)) }

        fun skip(tag: String) {
            prefs.edit().putString(PrefsKeys.UPDATE_SKIPPED, tag).apply()
        }

        /** Checks if a day has passed (or [force]); returns the pending update after the check. */
        suspend fun check(force: Boolean = false): ReleaseInfo? {
            if (!enabled && !force) return null
            val now = System.currentTimeMillis()
            val last = prefs.getLong(PrefsKeys.UPDATE_LAST_CHECK, 0L)
            val notBefore = prefs.getLong(PrefsKeys.UPDATE_NOT_BEFORE, 0L)
            // A manual check is allowed any time except while GitHub has asked us to wait.
            val allowed = if (force) now >= notBefore else UpdateLogic.due(now, last, notBefore)
            if (!allowed) return pending()
            withContext(Dispatchers.IO) { fetch(now) }
            return pending()
        }

        private fun fetch(now: Long) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("https://api.github.com/repos/${UpdateConfig.REPO}/releases?per_page=10").openConnection() as HttpURLConnection)
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                conn.setRequestProperty("User-Agent", "HabitMinerExtended/${installedName}")
                prefs.getString(PrefsKeys.UPDATE_ETAG, null)?.let { conn.setRequestProperty("If-None-Match", it) }
                when (val code = conn.responseCode) {
                    200 -> {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val best = UpdateJson.newestFrom(JSONArray(body), UpdateConfig.REPO)
                        prefs.edit()
                            .putString(PrefsKeys.UPDATE_ETAG, conn.getHeaderField("ETag"))
                            .putString(PrefsKeys.UPDATE_CACHED, best?.let { UpdateJson.toCache(it) })
                            .putLong(PrefsKeys.UPDATE_LAST_CHECK, now)
                            .putInt(PrefsKeys.UPDATE_FAILURES, 0)
                            .apply()
                    }
                    304 -> prefs.edit().putLong(PrefsKeys.UPDATE_LAST_CHECK, now).putInt(PrefsKeys.UPDATE_FAILURES, 0).apply()
                    403, 429 -> backOff(now, conn.getHeaderField("Retry-After")?.toLongOrNull())
                    404 -> prefs.edit().putLong(PrefsKeys.UPDATE_LAST_CHECK, now).apply() // no releases yet
                    else -> backOff(now, null).also { android.util.Log.w(TAG, "Update check answered $code") }
                }
            } catch (e: Exception) {
                // Offline or a bad answer: try again at the next scheduled check.
                android.util.Log.i(TAG, "Update check failed: ${e.javaClass.simpleName}")
            } finally {
                conn?.disconnect()
            }
        }

        private fun backOff(
            now: Long,
            retryAfterSec: Long?,
        ) {
            val failures = prefs.getInt(PrefsKeys.UPDATE_FAILURES, 0) + 1
            prefs.edit()
                .putInt(PrefsKeys.UPDATE_FAILURES, failures)
                .putLong(PrefsKeys.UPDATE_NOT_BEFORE, now + UpdateLogic.backoffMs(failures, retryAfterSec))
                .apply()
        }

        /** Remembers that the "update ready" notification went out for this version, so it's only sent once. */
        fun shouldNotify(tag: String): Boolean {
            if (prefs.getString(PrefsKeys.UPDATE_NOTIFIED, null) == tag) return false
            prefs.edit().putString(PrefsKeys.UPDATE_NOTIFIED, tag).apply()
            return true
        }

        companion object {
            private const val TAG = "HabitMiner"
        }
    }

object UpdateConfig {
    /** Where releases are published. */
    const val REPO = "harshshrivastava27/HabitMiner-Extended"
}

internal object UpdateJson {
    fun newestFrom(
        releases: JSONArray,
        repo: String,
    ): ReleaseInfo? {
        val list = ArrayList<ReleaseInfo>()
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            val code = com.habitminer.analytics.UpdateLogic.versionCodeOf(tag) ?: continue
            val assets = r.optJSONArray("assets") ?: continue
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
            val url = apk.optString("browser_download_url")
            if (!com.habitminer.analytics.UpdateLogic.trustedDownload(url, repo)) continue
            val digest = apk.optString("digest").removePrefix("sha256:").lowercase()
            if (!Regex("^[0-9a-f]{64}$").matches(digest)) continue // no checksum, no install
            list +=
                ReleaseInfo(
                    tag = tag,
                    versionName = tag.removePrefix("v"),
                    versionCode = code,
                    notes = com.habitminer.analytics.UpdateLogic.shortNotes(r.optString("body")),
                    apkUrl = url,
                    apkBytes = apk.optLong("size"),
                    sha256 = digest,
                    publishedAt = r.optString("published_at"),
                )
        }
        return list.maxByOrNull { it.versionCode }
    }

    fun toCache(r: ReleaseInfo): String =
        JSONObject()
            .put("tag", r.tag).put("name", r.versionName).put("code", r.versionCode).put("notes", r.notes)
            .put("url", r.apkUrl).put("size", r.apkBytes).put("sha", r.sha256).put("at", r.publishedAt)
            .toString()

    fun fromCache(json: String?): ReleaseInfo? {
        if (json.isNullOrBlank()) return null
        val o = JSONObject(json)
        return ReleaseInfo(o.getString("tag"), o.getString("name"), o.getInt("code"), o.optString("notes"), o.getString("url"), o.optLong("size"), o.getString("sha"), o.optString("at"))
    }
}
