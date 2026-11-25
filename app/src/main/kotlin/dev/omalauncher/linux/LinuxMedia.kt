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
import android.system.Os
import android.system.OsConstants
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
import kotlinx.coroutines.delay
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
        // After a stop, the track that was set: the car's list isn't empty, and picking it plays it again.
        val v = now ?: last ?: return null
        val description =
            MediaDescription
                .Builder()
                .setMediaId(NOW_PLAYING)
                .setTitle(v.str("title"))
                .setSubtitle(listOfNotNull(v.str("artist"), v.str("album")).distinct().joinToString(" · ").ifEmpty { null })
                .setIconBitmap(if (now != null) art else lastArt)
                .build()
        // Playable only when a press reaches the player: tapping it in the car runs its command's play.
        return MediaBrowser.MediaItem(description, if ("play" in actions(v)) MediaBrowser.MediaItem.FLAG_PLAYABLE else 0)
    }

    /**
     * Linux stopping or removed ([clear] with forget): a set the bridge sends in its last moments would only bring the
     * player back. [open] when Linux is up again.
     */
    private var closed = false

    /** Linux is up: sets are taken again. */
    fun open() {
        closed = false
    }

    /** A press of next or previous until the player's next track arrives (or 3 s): what the car shows meanwhile. */
    private var skipping: String? = null
    private var skippingJob: Job? = null

    /** When the last set came, and whether the player's pipe ([press]) went unanswered: then presses run its command. */
    private var lastSetAt = 0L
    private var pipeDead = false

    /** What was last handed to the session and the notification: unchanged, they aren't sent again. */
    private var metaKey: List<Any?>? = null
    private var noteKey: List<Any?>? = null
    private var channelMade = false

    /** A play of the track from before a stop that's under way: a second tap doesn't start the player twice. */
    private var playLastJob: Job? = null

    /** What was set before the last clear, for the car's list: see [item]. */
    private var last: Obj? = null
    private var lastArt: Bitmap? = null

    /** The track from before a stop, picked in the car: its player's command plays again. */
    private fun playLast() {
        val v = last ?: return
        val command = v.str("command")?.takeIf { "play" in actions(v) } ?: return
        if (playLastJob?.isActive == true) return
        playLastJob = scope.launch(Dispatchers.IO) {
            val code = runCatching { proot.run("$command play") { Log.i(TAG, "[play last] $it") } }.getOrElse { -1 }
            if (code != 0) Log.w(TAG, "'$command play' exited $code")
        }
    }

    /** What's set, as `omalauncher media` shows it, the position as of now. */
    fun current(): Value = now?.let { Obj(it.fields + ("position" to Num(position(it)))) } ?: Null

    /** Publishes [v] (merged into what's set unless it names a new track); the error to answer with, or null. */
    fun set(v: Obj): String? {
        if (closed) return "media set: Linux is stopping"
        val bad = v.fields.keys - FIELDS
        if (bad.isNotEmpty()) return "media set: unknown ${bad.joinToString(", ")}; one of ${FIELDS.joinToString(", ")}"
        val next = if (v.fields.containsKey("title") || now == null) v else Obj(now!!.fields + v.fields)
        if (next.str("title").isNullOrEmpty()) return "media set: a track needs a title"
        now = next
        setAt = SystemClock.elapsedRealtime()
        // A new track: the one from before the last stop isn't needed for the car's list any more.
        if (v.fields.containsKey("title")) {
            last = null
            lastArt = null
            skipping = null
        }
        lastSetAt = SystemClock.elapsedRealtime()
        pipeDead = false
        val listed = LISTED.any { it in v.fields }
        loadArt(next.str("art"))
        publish()
        if (listed) onChanged?.invoke()
        return null
    }

    /** Takes the player away from Android's media controls. */
    /** [forget]: Linux stopped or removed, so nothing could play it again: the car's list doesn't offer it. */
    fun clear(forget: Boolean = false) {
        now?.let {
            last = it
            lastArt = art
        }
        if (forget) {
            last = null
            lastArt = null
            closed = true
        }
        now = null
        artJob?.cancel()
        art = null
        artSrc = null
        nm.cancel(NOTIFICATION)
        metaKey = null
        noteKey = null
        skipping = null
        pausedForFocus = false
        lostFocus = false
        syncFocus(false, pressed = false)
        // Kept, inactive and empty: a car's browser service holds its token (see [token]).
        session?.run {
            // With a track to play again ([item]), a car may ask for it: what it would press says it can.
            val again = last?.takeIf { it.str("command") != null && "play" in actions(it) } != null
            setPlaybackState(
                PlaybackState
                    .Builder()
                    .setState(PlaybackState.STATE_NONE, 0, 0f)
                    .setActions(if (again) PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH else 0)
                    .build(),
            )
            setMetadata(null)
            isActive = false
        }
        onChanged?.invoke()
    }

    /** [pressed]: from a press here (play asks for the focus), not from the player's own set. */
    private fun publish(pressed: Boolean = false) {
        val v = now ?: return
        val s = session()
        // The metadata (with its cover) and the notification only when what they show changed: a press or a position
        // changes the playback state alone, and that's what has to reach the car at once.
        val meta = listOf(v.str("title"), v.str("artist"), v.str("album"), v.num("duration"), art)
        if (meta != metaKey) {
            s.setMetadata(metadata(v))
            metaKey = meta
        }
        s.setPlaybackState(playback(v))
        s.isActive = true
        syncFocus(v.bool("playing") == true, pressed)
        val note = meta + listOf(v.bool("playing"), actions(v), skip(v))
        if (nm.areNotificationsEnabled() && note != noteKey) {
            if (!channelMade) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.linux_media_channel), NotificationManager.IMPORTANCE_LOW))
                channelMade = true
            }
            nm.notify(NOTIFICATION, notification(v, s))
            noteKey = note
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
                    override fun onPlay() = if (now == null) playLast() else press("play")

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
                    ) = if (now == null) playLast() else press("play")

                    // "Play … on Omalauncher" from the car or the Assistant: there's only the one player to play.
                    override fun onPlayFromSearch(
                        query: String?,
                        extras: android.os.Bundle?,
                    ) = if (now == null) playLast() else press("play")
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
        // Shown at once, before the player has done it (its next set says how it went): the car and the lock screen
        // answer the finger, not the round trip.
        val end = v.num("duration")?.takeIf { it > 0 }?.let { it - 2 }
        val shown =
            when (word) {
                "play", "pause" -> mapOf("playing" to Bool(word == "play"), "position" to Num(position(v)))
                "seek" -> mapOf("position" to Num((second ?: 0L).toDouble()))
                "rewind", "forward" -> {
                    val to = position(v) + (second ?: skip(v)) * (if (word == "rewind") -1 else 1)
                    mapOf("position" to Num(to.coerceAtLeast(0.0).let { if (end != null) it.coerceAtMost(end) else it }))
                }
                else -> emptyMap()
            }
        if (word == "next" || word == "previous") {
            skipping = word
            skippingJob?.cancel()
            skippingJob =
                scope.launch {
                    delay(SKIPPING_MS)
                    skipping = null
                    publish()
                }
        }
        if (shown.isNotEmpty() || skipping != null) {
            now = Obj(v.fields + shown)
            setAt = SystemClock.elapsedRealtime()
            publish(pressed = true)
        }
        deliver(v, word, second)
    }

    /**
     * Hands a press to the player: into its `pipe` (a FIFO in Linux its bridge reads, petrzpav's fork) if it has one,
     * a write and no process; else, or when the pipe has no reader, through its `command`. A pipe whose reader took a
     * press but sent nothing for [PIPE_ANSWER_MS] (the bridge lost its proot) is given up on, and a press it can
     * safely do twice (play, pause, a seek to a second) goes through the command too.
     */
    private fun deliver(
        v: Obj,
        word: String,
        second: Long?,
    ) {
        val line = word + (second?.let { " $it" } ?: "")
        val pipe = v.str("pipe")?.takeIf { !pipeDead }?.let { proot.guestFile(it) }
        if (pipe == null) {
            runCommand(v, line)
            return
        }
        val sentAt = SystemClock.elapsedRealtime()
        scope.launch(Dispatchers.IO) {
            val written =
                runCatching {
                    // Non-blocking: no reader is ENXIO at once, rather than an open that waits for one.
                    val fd = Os.open(pipe.path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
                    try {
                        val bytes = "$line\n".toByteArray()
                        Os.write(fd, bytes, 0, bytes.size) == bytes.size
                    } finally {
                        Os.close(fd)
                    }
                }.getOrElse { false }
            withContext(Dispatchers.Main) {
                if (!written) {
                    pipeDead = true
                    runCommand(v, line)
                    return@withContext
                }
                delay(PIPE_ANSWER_MS)
                if (lastSetAt < sentAt && now != null) {
                    Log.w(TAG, "the player's pipe didn't answer '$line'; its command instead")
                    pipeDead = true
                    if (word in AGAIN_SAFE) runCommand(v, line)
                }
            }
        }
    }

    /** `<command> <word> [second]` in Linux, a process: the way a player without a pipe is pressed. */
    private fun runCommand(
        v: Obj,
        line: String,
    ) {
        val command = v.str("command") ?: return
        val run = "$command $line"
        scope.launch(Dispatchers.IO) {
            val code = runCatching { proot.run(run) { Log.i(TAG, "[$line] $it") } }.getOrElse { -1 }
            if (code != 0) Log.w(TAG, "'$run' exited $code")
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
                // A circled arrow with the seconds in it, as podcast players have, not ◀◀ ▶▶ (that's scanning).
                if ("rewind" in acts) {
                    addCustomAction(PlaybackState.CustomAction.Builder("rewind", context.getString(R.string.linux_media_rewind, step), skipIcon(back = true, step)).build())
                }
                if ("forward" in acts) {
                    addCustomAction(PlaybackState.CustomAction.Builder("forward", context.getString(R.string.linux_media_forward, step), skipIcon(back = false, step)).build())
                }
            }
            .setState(
                when {
                    skipping == "next" -> PlaybackState.STATE_SKIPPING_TO_NEXT
                    skipping == "previous" -> PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                    playing -> PlaybackState.STATE_PLAYING
                    else -> PlaybackState.STATE_PAUSED
                },
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
                "previous".takeIf { it in acts }?.let { button(it, R.drawable.ic_linux_media_previous) },
                "rewind".takeIf { it in acts }?.let { button(it, skipIcon(back = true, skip(v))) },
                (if (playing) "pause" else "play").takeIf { it in acts }?.let { button(it, if (playing) R.drawable.ic_linux_media_pause else R.drawable.ic_linux_media_play) },
                "forward".takeIf { it in acts }?.let { button(it, skipIcon(back = false, skip(v))) },
                "next".takeIf { it in acts }?.let { button(it, R.drawable.ic_linux_media_next) },
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

        /** Media3's icons (Apache-2.0, res/drawable/ic_linux_media_*): the step's number in them for 5, 10, 15 and 30 s. */
        fun skipIcon(
            back: Boolean,
            step: Long,
        ): Int =
            when (step) {
                5L -> if (back) R.drawable.ic_linux_media_skip_back_5 else R.drawable.ic_linux_media_skip_forward_5
                10L -> if (back) R.drawable.ic_linux_media_skip_back_10 else R.drawable.ic_linux_media_skip_forward_10
                15L -> if (back) R.drawable.ic_linux_media_skip_back_15 else R.drawable.ic_linux_media_skip_forward_15
                30L -> if (back) R.drawable.ic_linux_media_skip_back_30 else R.drawable.ic_linux_media_skip_forward_30
                else -> if (back) R.drawable.ic_linux_media_skip_back else R.drawable.ic_linux_media_skip_forward
            }

        /** The step of rewind and forward, in whole seconds: the set's `skip`, else 30. */
        fun skip(v: Obj): Long = (v.num("skip") ?: 30.0).toLong().coerceIn(1, 3600)
        val DEFAULT_ACTIONS = listOf("play", "pause", "next", "previous", "seek")
        val FIELDS = setOf("title", "artist", "album", "art", "duration", "position", "playing", "speed", "command", "actions", "skip", "pipe")

        /** How long next or previous shows as skipping without the player's next track. */
        const val SKIPPING_MS = 3000L

        /** How long a press into the pipe may go without a set before the pipe is given up on. */
        const val PIPE_ANSWER_MS = 2000L

        /** Presses that do no harm done twice, run by the command too when the pipe went unanswered. */
        val AGAIN_SAFE = setOf("play", "pause", "seek", "stop")

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
