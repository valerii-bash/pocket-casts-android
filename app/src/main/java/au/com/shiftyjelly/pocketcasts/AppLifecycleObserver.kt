package au.com.shiftyjelly.pocketcasts

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import au.com.shiftyjelly.pocketcasts.analytics.AppLifecycleAnalytics
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import javax.inject.Inject

class AppLifecycleObserver @Inject constructor(
    private val appLifecycleAnalytics: AppLifecycleAnalytics,
    private val settings: Settings,
) : DefaultLifecycleObserver {
    fun setup() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        val oldVersionCode = settings.getMigratedVersionCode()
        appLifecycleAnalytics.onApplicationInstalledOrUpgraded(oldVersionCode)
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        appLifecycleAnalytics.onApplicationEnterForeground()
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        appLifecycleAnalytics.onApplicationEnterBackground()
    }
}
