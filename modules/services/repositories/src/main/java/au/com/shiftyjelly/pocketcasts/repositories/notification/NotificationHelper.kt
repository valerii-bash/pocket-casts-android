package au.com.shiftyjelly.pocketcasts.repositories.notification

import android.app.Activity
import androidx.core.app.NotificationCompat

interface NotificationHelper {

    companion object {
        const val NOTIFICATION_ID_OPML = 21483646
        const val NOTIFICATION_ID_PLAYING = 21483647
        const val NOTIFICATION_ID_DOWNLOADING = 21483648
        const val NOTIFICATION_ID_SIGN_IN_ERROR = 21483649
    }

    fun setupNotificationChannels()

    fun downloadChannelBuilder(): NotificationCompat.Builder
    fun playbackChannelBuilder(): NotificationCompat.Builder
    fun episodeNotificationChannelBuilder(): NotificationCompat.Builder
    fun playbackErrorChannelBuilder(): NotificationCompat.Builder
    fun podcastImportChannelBuilder(): NotificationCompat.Builder
    fun openEpisodeNotificationSettings(activity: Activity?)
    fun signInErrorChannelBuilder(): NotificationCompat.Builder
    fun isShowing(notificationId: Int): Boolean
}
