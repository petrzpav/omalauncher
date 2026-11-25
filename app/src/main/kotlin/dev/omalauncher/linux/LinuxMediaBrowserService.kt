package dev.omalauncher.linux

import android.media.browse.MediaBrowser
import android.os.Bundle
import android.service.media.MediaBrowserService
import dev.omalauncher.App

/**
 * What a Linux player plays ([LinuxMedia], `omalauncher media`), for Android Auto and other media browsers (petrzpav's
 * fork): Android Auto lists only apps with a media browser service, and drives the player through the session this
 * hands it. The list is what plays now, one track, or nothing while nothing is set.
 */
class LinuxMediaBrowserService : MediaBrowserService() {
    private val media get() = (application as App).runtime.linuxMedia

    override fun onCreate() {
        super.onCreate()
        sessionToken = media.token
        media.onChanged = { notifyChildrenChanged(ROOT) }
    }

    override fun onDestroy() {
        media.onChanged = null
        super.onDestroy()
    }

    /**
     * No root for System UI's media resumption (EXTRA_RECENT): its card would outlive `media clear`, with a play button
     * that has no player to reach.
     */
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? = if (rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) == true) null else BrowserRoot(ROOT, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowser.MediaItem>>,
    ) {
        result.sendResult(if (parentId == ROOT) listOfNotNull(media.item()).toMutableList() else mutableListOf())
    }

    private companion object {
        const val ROOT = "root"
    }
}
