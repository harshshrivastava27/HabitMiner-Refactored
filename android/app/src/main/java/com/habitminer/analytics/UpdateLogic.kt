package com.habitminer.analytics

import java.security.MessageDigest

/** A release that can be installed over the current app. */
data class ReleaseInfo(
    val tag: String,
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val apkUrl: String,
    val apkBytes: Long,
    val sha256: String,
    val publishedAt: String,
)

/**
 * The rules for the in-app update check, kept free of Android so they can be tested:
 * which releases count, how a tag becomes a version number, when to check again, and which
 * download addresses are trusted.
 */
object UpdateLogic {
    const val CHECK_INTERVAL_MS = 24 * TimeUtil.HOUR

    /** `v2.1.3` → 20103. Pre-release tags (`v2.1.3-rc1`) and anything else don't count. */
    fun versionCodeOf(tag: String): Int? {
        val m = Regex("^v(\\d{1,2})\\.(\\d{1,2})\\.(\\d{1,2})$").matchEntire(tag.trim()) ?: return null
        val (a, b, c) = m.destructured
        return a.toInt() * 10_000 + b.toInt() * 100 + c.toInt()
    }

    /** Only HTTPS downloads from this repository's release assets on GitHub's own hosts. */
    fun trustedDownload(
        url: String,
        repo: String,
    ): Boolean {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (u.scheme != "https") return false
        val host = u.host?.lowercase() ?: return false
        if (host == "github.com") return u.path.lowercase().startsWith("/${repo.lowercase()}/releases/download/")
        return host == "objects.githubusercontent.com" || host == "release-assets.githubusercontent.com"
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** True when it's time to ask GitHub again: a day since the last check, and not inside a backoff. */
    fun due(
        now: Long,
        lastCheck: Long,
        notBefore: Long,
    ): Boolean = now >= notBefore && now - lastCheck >= CHECK_INTERVAL_MS

    /** How long to wait after GitHub said no (403/429): its own hint if given, else doubling from an hour up to a day. */
    fun backoffMs(
        failures: Int,
        retryAfterSec: Long?,
    ): Long {
        if (retryAfterSec != null && retryAfterSec > 0) return (retryAfterSec * 1000).coerceIn(TimeUtil.HOUR, CHECK_INTERVAL_MS)
        return (TimeUtil.HOUR shl (failures - 1).coerceIn(0, 5)).coerceAtMost(CHECK_INTERVAL_MS)
    }

    /** The newest installable release that is newer than [installedCode] and not skipped, or null. */
    fun newest(
        releases: List<ReleaseInfo>,
        installedCode: Int,
        skippedTag: String?,
    ): ReleaseInfo? =
        releases.filter { it.versionCode > installedCode && it.tag != skippedTag }.maxByOrNull { it.versionCode }

    /** Trims the release notes to something a banner can show. */
    fun shortNotes(
        notes: String,
        maxChars: Int = 600,
    ): String {
        val clean = notes.replace("\r", "").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return if (clean.length <= maxChars) clean else clean.take(maxChars).substringBeforeLast(' ') + "…"
    }
}
