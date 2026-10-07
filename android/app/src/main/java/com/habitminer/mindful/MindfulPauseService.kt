package com.habitminer.mindful

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.habitminer.goals.GoalsStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The mindful pause: when you open an app you want to use less, a short breathing screen comes
 * first, and you choose whether to carry on. It only reads which app came to the front (no
 * window content), and does nothing unless you turned the pause on in HabitMiner.
 */
@AndroidEntryPoint
class MindfulPauseService : AccessibilityService() {
    @Inject lateinit var goalsStore: GoalsStore

    private var foreground: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // The shade, keyboards and our own screens don't count as switching apps.
        if (pkg == packageName || pkg in IGNORED || pkg.contains("inputmethod")) return
        val previous = foreground
        foreground = pkg
        if (pkg == previous) return
        if (!MindfulPause.enabled(this) || pkg !in goalsStore.goals.value.useLess) return
        val allowedAt = allowed[pkg] ?: 0L
        if (System.currentTimeMillis() - allowedAt < MindfulPause.GRACE_MS) return
        startActivity(
            Intent(this, PauseActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(PauseActivity.EXTRA_PACKAGE, pkg),
        )
    }

    override fun onInterrupt() = Unit

    companion object {
        private val IGNORED = setOf("com.android.systemui", "android", "com.google.android.permissioncontroller")

        /** When you chose "Open" for an app, so it isn't paused again right away. */
        val allowed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }
}
