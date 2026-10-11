package com.habitminer.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.habitminer.analytics.ReleaseInfo
import com.habitminer.analytics.UpdateLogic
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface InstallState {
    data object Idle : InstallState

    data class Downloading(val percent: Int) : InstallState

    data object Verifying : InstallState

    data class Ready(val file: File) : InstallState

    data class Failed(val reason: String) : InstallState
}

/**
 * Downloads an update, checks it is really this app's next version, and hands it to Android's
 * installer. Nothing is installed without the person's tap on the system's own install screen.
 *
 * Checks before it's offered: the address is this repository's release asset over HTTPS; the
 * size and SHA-256 match what GitHub reported; it is the same package, newer, and signed by
 * the same key as the installed app (Android would refuse otherwise, but this explains why).
 */
@Singleton
class UpdateInstaller
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
        val state: StateFlow<InstallState> = _state

        suspend fun download(release: ReleaseInfo) =
            withContext(Dispatchers.IO) {
                if (_state.value is InstallState.Downloading) return@withContext
                if (!UpdateLogic.trustedDownload(release.apkUrl, UpdateConfig.REPO)) return@withContext fail("The download address isn't one HabitMiner trusts.")
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "HabitMinerExtended-${release.tag}.apk")
                _state.value = InstallState.Downloading(0)
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(release.apkUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.instanceFollowRedirects = false
                    var hops = 0
                    // GitHub redirects release downloads to its storage host; follow only to trusted ones.
                    while (conn!!.responseCode in 301..308 && hops++ < 4) {
                        val next = conn.getHeaderField("Location") ?: return@withContext fail("The download was redirected somewhere unexpected.")
                        if (!UpdateLogic.trustedDownload(next, UpdateConfig.REPO)) return@withContext fail("The download was redirected somewhere unexpected.")
                        conn.disconnect()
                        conn = (URL(next).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15_000
                            readTimeout = 30_000
                            instanceFollowRedirects = false
                        }
                    }
                    if (conn.responseCode != 200) return@withContext fail("The download failed (${conn.responseCode}).")
                    val total = release.apkBytes.takeIf { it > 0 } ?: conn.contentLengthLong
                    val digest = MessageDigest.getInstance("SHA-256")
                    var done = 0L
                    conn.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                done += n
                                if (total > 0) _state.value = InstallState.Downloading((done * 100 / total).toInt().coerceIn(0, 100))
                                if (done > MAX_BYTES) return@withContext fail("The file is larger than expected.")
                            }
                        }
                    }
                    _state.value = InstallState.Verifying
                    if (release.apkBytes > 0 && done != release.apkBytes) return@withContext fail("The download is incomplete. Try again.")
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (sha != release.sha256) return@withContext fail("The file doesn't match its checksum, so it was discarded.")
                    verifyPackage(file, release)?.let { return@withContext fail(it) }
                    _state.value = InstallState.Ready(file)
                } catch (e: Exception) {
                    fail("The download couldn't finish. Check your connection and try again.")
                } finally {
                    conn?.disconnect()
                }
            }

        private fun fail(reason: String) {
            _state.value = InstallState.Failed(reason)
            File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
        }

        /** Returns a reason when the file isn't an acceptable update, else null. */
        @Suppress("DEPRECATION")
        private fun verifyPackage(
            file: File,
            release: ReleaseInfo,
        ): String? {
            val pm = context.packageManager
            val flags = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return "The file isn't a valid app package."
            if (archive.packageName != context.packageName) return "The file is for a different app."
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) archive.longVersionCode.toInt() else archive.versionCode
            if (code != release.versionCode) return "The file's version doesn't match the release."
            val installed = pm.getPackageInfo(context.packageName, flags)
            val a = signers(archive)
            val b = signers(installed)
            if (a.isEmpty() || a != b) {
                return "This version is signed with a different key than the one installed (test builds use a shared key). " +
                    "To switch: export your data in Settings, uninstall, install the release from GitHub, then import."
            }
            return null
        }

        @Suppress("DEPRECATION")
        private fun signers(info: android.content.pm.PackageInfo): Set<String> {
            val sigs =
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    info.signingInfo?.apkContentsSigners
                } else {
                    info.signatures
                }
            return sigs.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        }

        fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

        /** Opens Android's "allow from this app" screen. */
        fun requestInstallPermission() {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** Hands the verified file to Android's installer; the person confirms there. */
        fun install(file: File) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun reset() {
            _state.value = InstallState.Idle
        }

        private companion object {
            const val MAX_BYTES = 150L * 1024 * 1024
        }
    }
