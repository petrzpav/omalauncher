package dev.omalauncher.linux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.omalauncher.MainActivity
import dev.omalauncher.R
import dev.omalauncher.system.registerNotExported
import dev.omalauncher.widgets.lang.Bool
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "linux-media"

/**
 * What a Linux player plays, in Android's media controls. Guest audio already plays as Android media (AudioBridge);
 * `omalauncher media set` adds the rest of what makes it a player to the phone: a media session and its notification, so
 * the lock screen, the notification shade's player, a car's display and steering-wheel buttons over Bluetooth,
 * headset and AirPods buttons and a watch show what plays and steer it. Their presses run the player's `command` in
 * the guest with the action after it: `play`, `pause`, `next`, `previous`, `stop`, or `seek` and the second to go to.
 *
 * A set with a `title` describes a new track and replaces what was set; one without changes only the fields it has,
 * as a player says it paused or went elsewhere in the track: `{"playing": false, "position": 63}`.
 */
class LinuxMedia(
    private val context: Context,
    private val proot: Proot,
    /** Main: [set], [clear] and the session's callbacks run on it. */
    private val scope: CoroutineScope,
) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

    /**
     * Audio focus while the Linux player plays (petrzpav's fork): it's what tells Android Auto, and a car's head unit,
     * to switch from the radio to the phone, and it lets a call or another player pause this one.
     */
    private val focusRequest =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(::onFocus, Handler(Looper.getMainLooper()))
            .build()
    private var focused = false

    /** Paused for a moment's loss (a call, a navigation prompt): the focus is kept, and it plays again when it's back. */
    private var pausedForFocus = false

    /**
     * Paused for good by another app taking the focus: the player saying it still plays (its pause not through yet)
     * doesn't take the focus back; it's asked for again once it said it stopped, or on a press of play.
     */
    private var lostFocus = false
    private var session: MediaSession? = null
    private var receiver: BroadcastReceiver? = null

    /** What was set, with the position as of [setAt] (elapsedRealtime). */
    private var now: Obj? = null
    private var setAt = 0L
    private var art: Bitmap? = null
    private var artSrc: String? = null
    private var artJob: Job? = null

    /**
     * Something set or cleared, for the car's browser ([LinuxMediaBrowserService]) to list it again. Main.
     */
    var onChanged: (() -> Unit)? = null

    /**
     * The session's token, for [LinuxMediaBrowserService]: a browser service takes one token for its life, so the session
     * stays (inactive while nothing is set) once it's made.
     */
    val token: MediaSession.Token get() = session().sessionToken

    /** What plays, as the one item a car's media browser lists (petrzpav's fork); null while nothing is set. */
    fun item(): MediaBrowser.MediaItem? {
        val v = now ?: return null
        val description =
            MediaDescription
                .Builder()
                .setMediaId(NOW_PLAYING)
                .setTitle(v.str("title"))
                .setSubtitle(listOfNotNull(v.str("artist"), v.str("album")).distinct().joinToString(" · ").ifEmpty { null })
                .setIconBitmap(art)
                .build()
        // Playable only when a press reaches the player: tapping it in the car runs its command's play.
        return MediaBrowser.MediaItem(description, if ("play" in actions(v)) MediaBrowser.MediaItem.FLAG_PLAYABLE else 0)
    }

    /** What's set, as `omalauncher media` shows it, the position as of now. */
    fun current(): Value = now?.let { Obj(it.fields + ("position" to Num(position(it)))) } ?: Null

    /** Publishes [v] (merged into what's set unless it names a new track); the error to answer with, or null. */
    fun set(v: Obj): String? {
        val bad = v.fields.keys - FIELDS
        if (bad.isNotEmpty()) return "media set: unknown ${bad.joinToString(", ")}; one of ${FIELDS.joinToString(", ")}"
        val next = if (v.fields.containsKey("title") || now == null) v else Obj(now!!.fields + v.fields)
        if (next.str("title").isNullOrEmpty()) return "media set: a track needs a title"
        now = next
        setAt = SystemClock.elapsedRealtime()
        val listed = LISTED.any { it in v.fields }
        loadArt(next.str("art"))
        publish()
        if (listed) onChanged?.invoke()
        return null
    }

    /** Takes the player away from Android's media controls. */
    fun clear() {
        now = null
        artJob?.cancel()
        art = null
        artSrc = null
        nm.cancel(NOTIFICATION)
        pausedForFocus = false
        lostFocus = false
        syncFocus(false, pressed = false)
        // Kept, inactive and empty: a car's browser service holds its token (see [token]).
        session?.run {
            setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_NONE, 0, 0f).build())
            setMetadata(null)
            isActive = false
        }
        onChanged?.invoke()
    }

    /** [pressed]: from a press here (play asks for the focus), not from the player's own set. */
    private fun publish(pressed: Boolean = false) {
        val v = now ?: return
        val s = session()
        s.setMetadata(metadata(v))
        s.setPlaybackState(playback(v))
        s.isActive = true
        syncFocus(v.bool("playing") == true, pressed)
        if (nm.areNotificationsEnabled()) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.linux_media_channel), NotificationManager.IMPORTANCE_LOW))
            nm.notify(NOTIFICATION, notification(v, s))
        }
    }

    /**
     * Holds the focus while it plays, and while it's paused for a moment's loss; gives it back otherwise. After a loss
     * the player's own "still playing" (its pause not through yet) changes nothing: only a press of play, or the player
     * starting again after it said it stopped, asks for the focus again.
     */
    private fun syncFocus(
        playing: Boolean,
        pressed: Boolean,
    ) {
        if (playing) {
            if (!pressed && (pausedForFocus || lostFocus)) return
            pausedForFocus = false
            lostFocus = false
            if (!focused) {
                focused = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                if (!focused) Log.w(TAG, "audio focus denied: Android Auto and a car's radio won't switch to it")
            }
        } else {
            lostFocus = false
            if (focused && !pausedForFocus) {
                audio.abandonAudioFocusRequest(focusRequest)
                focused = false
            }
        }
    }

    /** Another app took the focus or gave it back; ducking is Android's own. Main. */
    private fun onFocus(change: Int) {
        val v = now
        val playing = v?.bool("playing") == true
        // Only a player that can be paused from here: one without a command or a pause plays on regardless.
        val canPause = v != null && v.str("command") != null && "pause" in actions(v)
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focused = false
                pausedForFocus = false
                if (playing) {
                    lostFocus = true
                    if (canPause) press("pause")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                if (playing && canPause) {
                    pausedForFocus = true
                    press("pause")
                }
            AudioManager.AUDIOFOCUS_GAIN ->
                if (pausedForFocus) {
                    pausedForFocus = false
                    press("play")
                }
        }
    }

    private fun session(): MediaSession =
        session ?: MediaSession(context, TAG).also { s ->
            s.setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = press("play")

                    override fun onPause() = press("pause")

                    override fun onSkipToNext() = press("next")

                    override fun onSkipToPrevious() = press("previous")

                    override fun onStop() = press("stop")

                    override fun onSeekTo(pos: Long) = press("seek", pos / 1000)

                    // −30 s / +30 s (petrzpav's fork): the player's own buttons, the step its `skip`.
                    override fun onCustomAction(
                        action: String,
                        extras: android.os.Bundle?,
                    ) {
                        if (action in SKIPS) press(action, skip(now ?: return))
                    }

                    // The car's list has the one track: picking it plays it.
                    override fun onPlayFromMediaId(
                        mediaId: String?,
                        extras: android.os.Bundle?,
                    ) = press("play")

                    // "Play … on Omalauncher" from the car or the Assistant: there's only the one player to play.
                    override fun onPlayFromSearch(
                        query: String?,
                        extras: android.os.Bundle?,
                    ) = press("play")
                },
            )
            // Nothing until the first set, for the car's browser that has the session before anything plays.
            s.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_NONE, 0, 0f).build())
            session = s
            // The notification's buttons, where Android draws them from it rather than from the session (before 13).
            receiver = context.registerNotExported(IntentFilter(ACTION)) { intent -> intent.getStringExtra(EXTRA)?.let { press(it) } }
        }

    /**
     * A press from Android: shown at once (playing or paused, where a seek went), then the player's command runs; the
     * player's next set says how it went.
     */
    private fun press(
        word: String,
        second: Long? = null,
    ) {
        val v = now ?: return
        if (word !in actions(v)) return
        val shown =
            when (word) {
                "play", "pause" -> mapOf("playing" to Bool(word == "play"), "position" to Num(position(v)))
                "seek" -> mapOf("position" to Num((second ?: 0L).toDouble()))
                else -> emptyMap()
            }
        if (shown.isNotEmpty()) {
            now = Obj(v.fields + shown)
            setAt = SystemClock.elapsedRealtime()
            publish(pressed = true)
        }
        val command = v.str("command") ?: return
        val line = command + " " + word + (second?.let { " $it" } ?: "")
        scope.launch(Dispatchers.IO) {
            val code = runCatching { proot.run(line) { Log.i(TAG, "[$word] $it") } }.getOrElse { -1 }
            if (code != 0) Log.w(TAG, "'$line' exited $code")
        }
    }

    /** The position now: as set, run on by the time since while it plays. */
    private fun position(v: Obj): Double {
        val at = v.num("position") ?: 0.0
        if (v.bool("playing") != true) return at
        val ran = at + (SystemClock.elapsedRealtime() - setAt) / 1000.0 * (v.num("speed") ?: 1.0)
        return v.num("duration")?.takeIf { it > 0 }?.let { minOf(ran, it) } ?: ran
    }

    private fun metadata(v: Obj): MediaMetadata =
        MediaMetadata
            .Builder()
            .apply {
                putString(MediaMetadata.METADATA_KEY_TITLE, v.str("title"))
                v.str("artist")?.let { putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
                v.str("album")?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
                v.num("duration")?.takeIf { it > 0 }?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, (it * 1000).toLong()) }
                art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            }.build()

    private fun playback(v: Obj): PlaybackState {
        val playing = v.bool("playing") == true
        val acts = actions(v)
        var mask = 0L
        if ("play" in acts) {
            mask = mask or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
        }
        if ("pause" in acts) mask = mask or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        if ("next" in acts) mask = mask or PlaybackState.ACTION_SKIP_TO_NEXT
        if ("previous" in acts) mask = mask or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if ("seek" in acts && (v.num("duration") ?: 0.0) > 0) mask = mask or PlaybackState.ACTION_SEEK_TO
        if ("stop" in acts) mask = mask or PlaybackState.ACTION_STOP
        val step = skip(v)
        return PlaybackState
            .Builder()
            .apply {
                // Android Auto, the lock screen and the shade's player (Android 13+) show these beside the others.
                if ("rewind" in acts) addCustomAction(PlaybackState.CustomAction.Builder("rewind", "−$step s", android.R.drawable.ic_media_rew).build())
                if ("forward" in acts) addCustomAction(PlaybackState.CustomAction.Builder("forward", "+$step s", android.R.drawable.ic_media_ff).build())
            }
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                ((v.num("position") ?: 0.0) * 1000).toLong(),
                (v.num("speed") ?: 1.0).toFloat(),
                setAt,
            ).setActions(mask)
            .build()
    }

    private fun notification(
        v: Obj,
        s: MediaSession,
    ): Notification {
        val playing = v.bool("playing") == true
        val acts = actions(v)
        val buttons =
            listOfNotNull(
                "previous".takeIf { it in acts }?.let { button(it, android.R.drawable.ic_media_previous) },
                "rewind".takeIf { it in acts }?.let { button(it, android.R.drawable.ic_media_rew) },
                (if (playing) "pause" else "play").takeIf { it in acts }?.let { button(it, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play) },
                "forward".takeIf { it in acts }?.let { button(it, android.R.drawable.ic_media_ff) },
                "next".takeIf { it in acts }?.let { button(it, android.R.drawable.ic_media_next) },
            )
        // The small player before Android 13 has room for three: play or pause with what's either side of it.
        val middle = buttons.indexOfFirst { it.title == "play" || it.title == "pause" }
        val compact = listOf(middle - 1, middle, middle + 1).filter { it in buttons.indices }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification
            .Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(v.str("title"))
            .setContentText(listOfNotNull(v.str("artist"), v.str("album")).joinToString(" · "))
            .setLargeIcon(art)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .apply { buttons.forEach { addAction(it) } }
            .setStyle(
                Notification
                    .MediaStyle()
                    .setMediaSession(s.sessionToken)
                    .setShowActionsInCompactView(*(if (middle >= 0) compact else buttons.indices.take(3)).toIntArray()),
            ).build()
    }

    private fun button(
        word: String,
        icon: Int,
    ): Notification.Action {
        val intent = Intent(ACTION).setPackage(context.packageName).putExtra(EXTRA, word)
        val pending = PendingIntent.getBroadcast(context, word.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(context, icon), word, pending).build()
    }

    /** The cover, from an https URL or a guest file, loaded once per track and shown when it's there. */
    private fun loadArt(src: String?) {
        if (src == artSrc) return
        artSrc = src
        art = null
        artJob?.cancel()
        if (src.isNullOrEmpty()) return
        artJob =
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { runCatching { readArt(src) }.onFailure { Log.w(TAG, "cover $src: ${it.message}") }.getOrNull() }
                if (bitmap != null && src == artSrc) {
                    art = bitmap
                    publish()
                    // The car's list shows the cover too.
                    onChanged?.invoke()
                }
            }
    }

    private fun readArt(src: String): Bitmap? {
        val bytes =
            if (src.startsWith("https://")) {
                (URL(src).openConnection() as HttpURLConnection).run {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    try {
                        if (contentLengthLong > MAX_ART_BYTES) return null
                        inputStream.use { it.readBytes() }
                    } finally {
                        disconnect()
                    }
                }
            } else {
                val path = if (src.startsWith("~/")) "/root/" + src.removePrefix("~/") else src
                if (!path.startsWith("/")) return null
                proot.hostFile(path).takeIf { it.length() <= MAX_ART_BYTES }?.readBytes() ?: return null
            }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ART_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        const val CHANNEL = "media"
        const val NOTIFICATION = 2
        const val ACTION = "dev.omalauncher.LINUX_MEDIA"
        const val EXTRA = "action"
        const val NOW_PLAYING = "now"

        /** The fields the car's list shows: a set with any of them lists it again. */
        val LISTED = setOf("title", "artist", "album", "art", "command", "actions")
        const val ART_PX = 512
        const val MAX_ART_BYTES = 8 shl 20
        val ACTIONS = listOf("play", "pause", "next", "previous", "seek", "stop", "rewind", "forward")

        /** The step buttons (petrzpav's fork): a press runs `<command> rewind|forward <skip>`. */
        val SKIPS = setOf("rewind", "forward")

        /** The step of rewind and forward, in whole seconds: the set's `skip`, else 30. */
        fun skip(v: Obj): Long = (v.num("skip") ?: 30.0).toLong().coerceIn(1, 3600)
        val DEFAULT_ACTIONS = listOf("play", "pause", "next", "previous", "seek")
        val FIELDS = setOf("title", "artist", "album", "art", "duration", "position", "playing", "speed", "command", "actions", "skip")

        /** What the player can do: `actions` as set, the usual ones otherwise; none without a command. */
        fun actions(v: Obj): List<String> {
            if (v.str("command").isNullOrEmpty()) return emptyList()
            val given = (v.fields["actions"] as? Lst)?.items?.mapNotNull { (it as? Str)?.v }
            return given?.filter { it in ACTIONS } ?: DEFAULT_ACTIONS
        }

        fun Obj.str(key: String) = (fields[key] as? Str)?.v

        fun Obj.num(key: String) = (fields[key] as? Num)?.v?.takeIf { it.isFinite() }

        fun Obj.bool(key: String) = (fields[key] as? Bool)?.v
    }
}
