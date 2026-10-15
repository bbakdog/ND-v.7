package com.example.nowplaying

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 유튜브뮤직 등 다른 앱이 재생 중인 미디어세션(MediaController)에 연결해서
 * 앨범아트 / 곡정보 / 재생바 / 이전-재생-다음 버튼을 갱신하는 공용 컨트롤러.
 * 상단 시계/배터리, 그리고 "시계 화면" 모드 전환도 함께 관리한다.
 *
 * MainActivity와 NowPlayingDreamService(화면보호기) 양쪽에서 동일하게 재사용한다.
 */
class NowPlayingController(
    private val context: Context,
    private val rootView: View,
    private val listenerComponent: ComponentName
) {

    // 큰 화면(Mode A)
    private val imgBackground: ImageView = rootView.findViewById(R.id.imgBackground)
    private val contentColumn: View = rootView.findViewById(R.id.contentColumn)
    private val imgAlbumArt: ImageView = rootView.findViewById(R.id.imgAlbumArt)
    private val cardVinyl: View = rootView.findViewById(R.id.cardVinyl)
    private val imgVinylArt: ImageView = rootView.findViewById(R.id.imgVinylArt)
    private val txtTitle: TextView = rootView.findViewById(R.id.txtTitle)
    private val txtArtist: TextView = rootView.findViewById(R.id.txtArtist)
    private val txtSource: TextView = rootView.findViewById(R.id.txtSource)
    private val seekBar: SeekBar = rootView.findViewById(R.id.seekBar)
    private val txtPosition: TextView = rootView.findViewById(R.id.txtPosition)
    private val txtDuration: TextView = rootView.findViewById(R.id.txtDuration)
    private val btnPrev: ImageButton = rootView.findViewById(R.id.btnPrev)
    private val btnPlayPause: ImageButton = rootView.findViewById(R.id.btnPlayPause)
    private val btnNext: ImageButton = rootView.findViewById(R.id.btnNext)
    private val permissionPrompt: View = rootView.findViewById(R.id.permissionPrompt)
    private val txtWaiting: View = rootView.findViewById(R.id.txtWaiting)
    private val btnGrantPermission: View = rootView.findViewById(R.id.btnGrantPermission)

    // 가사 보기
    private val btnLyrics: ImageButton = rootView.findViewById(R.id.btnLyrics)
    private val normalInfoGroup: View = rootView.findViewById(R.id.normalInfoGroup)
    private val lyricsScroll: android.widget.ScrollView = rootView.findViewById(R.id.lyricsScroll)
    private val lyricsContainer: android.widget.LinearLayout = rootView.findViewById(R.id.lyricsContainer)
    private var isLyricsMode = false

    // 상단 상태바(시계/배터리) - 항상 표시
    private val txtClock: TextView = rootView.findViewById(R.id.txtClock)
    private val txtBatteryPercent: TextView = rootView.findViewById(R.id.txtBatteryPercent)
    private val imgBattery: ImageView = rootView.findViewById(R.id.imgBattery)
    private val batteryPill: View = rootView.findViewById(R.id.batteryPill)

    // 모드 전환 버튼 + 시계 화면(Mode B)
    private val btnToggleMode: ImageButton = rootView.findViewById(R.id.btnToggleMode)
    private val clockModeGroup: View = rootView.findViewById(R.id.clockModeGroup)
    private val txtBigClock: TextView = rootView.findViewById(R.id.txtBigClock)
    private val txtBigDate: TextView = rootView.findViewById(R.id.txtBigDate)
    private val miniPlayerRow: View = rootView.findViewById(R.id.miniPlayerRow)
    private val txtMiniTitle: TextView = rootView.findViewById(R.id.txtMiniTitle)
    private val txtMiniArtist: TextView = rootView.findViewById(R.id.txtMiniArtist)
    private val btnMiniPrev: ImageButton = rootView.findViewById(R.id.btnMiniPrev)
    private val btnMiniPlayPause: ImageButton = rootView.findViewById(R.id.btnMiniPlayPause)
    private val btnMiniNext: ImageButton = rootView.findViewById(R.id.btnMiniNext)

    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private var activeController: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var isClockMode = false

    private val clockFormat = SimpleDateFormat("H:mm", Locale.KOREA)
    private val dateFormat = SimpleDateFormat("M월 d일 EEEE", Locale.KOREA)

    /** CD 회전: 목표 속도를 향해 서서히 가속/감속하며 도는 수동 회전 애니메이션 */
    private var vinylVelocity = 0f          // 현재 각속도 (도/프레임)
    private var vinylTargetVelocity = 0f    // 목표 각속도 (재생 중이면 최고 속도, 아니면 0)
    private val vinylFullSpeed = 6.4f       // 최고 속도일 때 각속도 (약 0.9초에 한 바퀴)
    private val vinylEasing = 0.08f         // 클수록 가속/감속이 빨라짐

    private val vinylTicker = object : Runnable {
        override fun run() {
            vinylVelocity += (vinylTargetVelocity - vinylVelocity) * vinylEasing
            if (vinylVelocity > 0.01f || vinylTargetVelocity > 0f) {
                cardVinyl.rotation = (cardVinyl.rotation + vinylVelocity) % 360f
            }
            handler.postDelayed(this, 16)
        }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updatePlaybackState(state)
        }

        override fun onSessionDestroyed() {
            activeController = null
            refreshFromActiveSessions()
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            pickBestController(controllers ?: emptyList())
        }

    private val progressTicker = object : Runnable {
        override fun run() {
            tickProgress()
            tickClock()
            handler.postDelayed(this, 500)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            if (level < 0 || scale <= 0) return
            val percent = (level * 100 / scale)
            val isCharging = plugged != 0
            txtBatteryPercent.text = percent.toString()
            imgBattery.setImageResource(if (isCharging) R.drawable.ic_battery_charging else R.drawable.ic_battery)
            batteryPill.setBackgroundResource(if (isCharging) R.drawable.bg_pill_charging else R.drawable.bg_pill)
        }
    }

    init {
        btnPrev.setOnClickListener { activeController?.transportControls?.skipToPrevious() }
        btnNext.setOnClickListener { activeController?.transportControls?.skipToNext() }
        btnMiniPrev.setOnClickListener { activeController?.transportControls?.skipToPrevious() }
        btnMiniNext.setOnClickListener { activeController?.transportControls?.skipToNext() }
        val playPauseClick = View.OnClickListener {
            val state = activeController?.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING) {
                activeController?.transportControls?.pause()
            } else {
                activeController?.transportControls?.play()
            }
        }
        btnPlayPause.setOnClickListener(playPauseClick)
        btnMiniPlayPause.setOnClickListener(playPauseClick)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) txtPosition.text = formatMillis(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                activeController?.transportControls?.seekTo(sb?.progress?.toLong() ?: 0)
            }
        })
        btnGrantPermission.setOnClickListener {
            context.startActivity(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        btnToggleMode.setOnClickListener {
            if (isLyricsMode) exitLyricsMode()
            val toClock = !isClockMode
            val musicView = musicAreaView()
            if (toClock) {
                playSlideTransition(outgoing = musicView, incoming = clockModeGroup)
            } else {
                playSlideTransition(outgoing = clockModeGroup, incoming = musicView)
            }
            isClockMode = toClock
            updateToggleIcon()
        }
        btnLyrics.setOnClickListener {
            if (isLyricsMode) exitLyricsMode() else enterLyricsMode()
        }
    }

    /** 화면이 보이기 시작할 때 호출 */
    fun start() {
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.post(vinylTicker)
        applyModeVisibility()
        updateToggleIcon()

        if (!isNotificationAccessGranted()) {
            showPermissionPrompt()
        } else {
            permissionPrompt.visibility = View.GONE
            try {
                sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, listenerComponent)
                refreshFromActiveSessions()
            } catch (e: SecurityException) {
                showPermissionPrompt()
            }
        }
        handler.post(progressTicker)
    }

    /** 화면이 사라질 때 호출 (리스너/핸들러 정리) */
    fun stop() {
        handler.removeCallbacks(progressTicker)
        handler.removeCallbacks(vinylTicker)
        activeController?.unregisterCallback(controllerCallback)
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {
        }
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(context.packageName)
    }

    /** 큰 화면 모드인지 / 시계 화면 모드인지에 따라 관련 그룹의 표시 여부를 정리 */
    private fun applyModeVisibility() {
        if (isClockMode) {
            clockModeGroup.visibility = View.VISIBLE
            contentColumn.visibility = View.GONE
            permissionPrompt.visibility = View.GONE
            txtWaiting.visibility = View.GONE
        } else {
            clockModeGroup.visibility = View.GONE
            // 큰 화면 쪽 상태(권한 안내 / 대기중 / 재생정보)는 아래 각 함수가 다시 정리한다.
            if (activeController == null) {
                if (isNotificationAccessGranted()) showWaiting() else showPermissionPrompt()
            } else {
                updateMetadata(activeController?.metadata)
                updatePlaybackState(activeController?.playbackState)
            }
        }
    }

    /** 지금 보여야 하는 "음악 쪽" 화면(권한안내/대기중/재생정보) 중 하나를 반환 */
    private fun musicAreaView(): View = when {
        activeController == null && !isNotificationAccessGranted() -> permissionPrompt
        activeController == null -> txtWaiting
        else -> contentColumn
    }

    /** 전환 버튼 아이콘: 지금이 시계 화면이면 "음악으로 전환" 아이콘을, 음악 화면이면 "시계로 전환" 아이콘을 보여준다 */
    private fun updateToggleIcon() {
        btnToggleMode.setImageResource(
            if (isClockMode) R.drawable.ic_music_note else R.drawable.ic_clock_toggle
        )
    }

    /** 음악 화면으로 갈 때는 위로 올라오고, 나가는 화면은 아래로 내려가며 사라지는 슬라이드 전환 */
    private fun playSlideTransition(outgoing: View, incoming: View) {
        if (outgoing === incoming) return
        val distance = rootView.resources.displayMetrics.heightPixels * 0.45f

        incoming.translationY = distance
        incoming.alpha = 0f
        incoming.visibility = View.VISIBLE
        incoming.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(320)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        outgoing.animate()
            .translationY(distance)
            .alpha(0f)
            .setDuration(320)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                outgoing.visibility = View.GONE
                outgoing.translationY = 0f
                outgoing.alpha = 1f
            }
            .start()
    }

    private fun showPermissionPrompt() {
        if (isClockMode) return
        permissionPrompt.visibility = View.VISIBLE
        txtWaiting.visibility = View.GONE
        contentColumn.visibility = View.GONE
    }

    private fun refreshFromActiveSessions() {
        try {
            val controllers = sessionManager.getActiveSessions(listenerComponent)
            pickBestController(controllers)
        } catch (e: SecurityException) {
            showPermissionPrompt()
        }
    }

    /** 유튜브뮤직 세션을 우선하고, 없으면 재생 중인 아무 세션이나 선택 */
    private fun pickBestController(controllers: List<MediaController>) {
        if (controllers.isEmpty()) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = null
            showWaiting()
            miniPlayerRow.visibility = View.GONE
            return
        }
        val youtubeMusic = controllers.firstOrNull {
            it.packageName == "com.google.android.apps.youtube.music"
        }
        val playing = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        val chosen = youtubeMusic ?: playing ?: controllers.first()

        if (chosen != activeController) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = chosen
            chosen.registerCallback(controllerCallback, handler)
            txtSource.text = appLabelFor(chosen.packageName)
            updateMetadata(chosen.metadata)
            updatePlaybackState(chosen.playbackState)
        }
    }

    private fun appLabelFor(packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun showWaiting() {
        if (isClockMode) return
        permissionPrompt.visibility = View.GONE
        txtWaiting.visibility = View.VISIBLE
        contentColumn.visibility = View.GONE
    }

    private fun updateMetadata(metadata: MediaMetadata?) {
        if (metadata == null) return

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""

        if (!isClockMode) {
            permissionPrompt.visibility = View.GONE
            txtWaiting.visibility = View.GONE
            contentColumn.visibility = View.VISIBLE
        }

        txtTitle.text = title
        txtArtist.text = artist
        txtMiniTitle.text = title
        txtMiniArtist.text = artist
        miniPlayerRow.visibility = View.VISIBLE

        // 가사 모드로 보고 있는 상태에서 곡이 바뀌면 새 곡 가사로 다시 불러온다
        if (isLyricsMode) {
            loadLyrics()
        }

        val art: Bitmap? =
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        if (art != null) {
            imgAlbumArt.setImageBitmap(art)
            imgVinylArt.setImageBitmap(art)
            imgBackground.setImageBitmap(art)
            applyBackgroundBlur()
        }

        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration > 0) {
            seekBar.max = duration.toInt()
            txtDuration.text = formatMillis(duration)
        }
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        if (state == null) return
        val isPlaying = state.state == PlaybackState.STATE_PLAYING
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        btnPlayPause.setImageResource(icon)
        btnMiniPlayPause.setImageResource(icon)
        vinylTargetVelocity = if (isPlaying) vinylFullSpeed else 0f
    }

    private fun tickProgress() {
        val controller = activeController ?: return
        val state = controller.playbackState ?: return
        if (isUserSeeking) return

        val elapsedSincePositionUpdate =
            if (state.state == PlaybackState.STATE_PLAYING) {
                (android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed
            } else 0f

        val currentPosition = (state.position + elapsedSincePositionUpdate).toLong().coerceAtLeast(0)
        seekBar.progress = currentPosition.toInt().coerceAtMost(seekBar.max)
        txtPosition.text = formatMillis(currentPosition)

        if (isLyricsMode) {
            updateLyricsHighlight(currentPosition)
        }
    }

    private fun tickClock() {
        val now = Date()
        val timeText = clockFormat.format(now)
        txtClock.text = timeText
        txtBigClock.text = timeText
        txtBigDate.text = dateFormat.format(now)
    }

    private fun applyBackgroundBlur() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val blur = android.graphics.RenderEffect.createBlurEffect(
                60f, 60f, android.graphics.Shader.TileMode.CLAMP
            )
            imgBackground.setRenderEffect(blur)
        }
    }

    // 가사 줄 목록: (타임스탬프ms 또는 null, 가사 텍스트, 화면에 그려진 TextView)
    private var lyricLines: List<Triple<Long?, String, TextView>> = emptyList()
    private var currentLyricIndex = -1
    private var renderedLyricsKey: String? = null

    /** 가사 버튼을 눌러서 가사 모드로 들어갈 때: 정보 영역을 가사 목록으로 교체 */
    private fun enterLyricsMode() {
        isLyricsMode = true
        normalInfoGroup.visibility = View.GONE
        lyricsScroll.visibility = View.VISIBLE
        btnLyrics.setImageResource(R.drawable.ic_close)
        loadLyrics()
    }

    /** 가사 버튼을 다시 눌러서(또는 모드 전환 시) 평소 정보 화면으로 되돌아갈 때 */
    private fun exitLyricsMode() {
        isLyricsMode = false
        lyricsScroll.visibility = View.GONE
        normalInfoGroup.visibility = View.VISIBLE
        btnLyrics.setImageResource(R.drawable.ic_lyrics)
    }

    private fun loadLyrics() {
        val title = txtTitle.text.toString().trim()
        val artist = txtArtist.text.toString().trim()

        if (title.isEmpty()) {
            renderLyricsMessage("지금 재생 중인 곡이 없어요")
            renderedLyricsKey = null
            return
        }

        val key = "$artist|$title"
        if (key == renderedLyricsKey) return // 이미 같은 곡 가사가 표시되어 있음

        renderLyricsMessage("가사를 불러오는 중...")
        renderedLyricsKey = key

        Thread {
            val lines = fetchSyncedLyrics(artist, title)
            handler.post {
                // 그 사이에 곡이 바뀌었거나 가사 모드를 나갔으면 반영하지 않음
                if (!isLyricsMode) return@post
                if (txtTitle.text.toString().trim() != title) return@post

                if (lines.isNullOrEmpty()) {
                    renderLyricsMessage("가사를 찾을 수 없어요")
                } else {
                    renderLyricsLines(lines)
                }
            }
        }.start()
    }

    private fun renderLyricsMessage(message: String) {
        lyricsContainer.removeAllViews()
        lyricLines = emptyList()
        currentLyricIndex = -1
        val tv = TextView(context).apply {
            text = message
            setTextColor(context.resources.getColor(R.color.text_primary, null))
            textSize = 20f
        }
        lyricsContainer.addView(tv)
    }

    private fun renderLyricsLines(lines: List<Pair<Long?, String>>) {
        lyricsContainer.removeAllViews()
        currentLyricIndex = -1
        val density = context.resources.displayMetrics.density

        lyricLines = lines.map { (time, text) ->
            val tv = TextView(context)
            tv.text = text
            tv.setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            styleLyricLine(tv, active = false)
            lyricsContainer.addView(tv)
            Triple(time, text, tv)
        }

        // 초기 위치 기준으로 한 번 하이라이트
        updateLyricsHighlight(currentPlaybackPositionMs())
    }

    private fun styleLyricLine(tv: TextView, active: Boolean) {
        if (active) {
            tv.setTextColor(context.resources.getColor(R.color.text_primary, null))
            tv.textSize = 24f
            tv.setTypeface(null, android.graphics.Typeface.BOLD)
            tv.alpha = 1f
        } else {
            tv.setTextColor(context.resources.getColor(R.color.text_secondary, null))
            tv.textSize = 20f
            tv.setTypeface(null, android.graphics.Typeface.NORMAL)
            tv.alpha = 0.55f
        }
    }

    /** 현재 재생 위치를 기준으로 가사 중 몇 번째 줄이 활성화되어야 하는지 찾아 하이라이트+스크롤한다 */
    private fun updateLyricsHighlight(positionMs: Long) {
        if (lyricLines.isEmpty()) return
        var activeIndex = -1
        for (i in lyricLines.indices) {
            val t = lyricLines[i].first ?: continue
            if (t <= positionMs) activeIndex = i else break
        }
        if (activeIndex == currentLyricIndex || activeIndex < 0) return

        lyricLines.getOrNull(currentLyricIndex)?.let { styleLyricLine(it.third, active = false) }
        val target = lyricLines[activeIndex].third
        styleLyricLine(target, active = true)
        currentLyricIndex = activeIndex

        lyricsScroll.post {
            val scrollTo = target.top - lyricsScroll.height / 2 + target.height / 2
            lyricsScroll.smoothScrollTo(0, scrollTo.coerceAtLeast(0))
        }
    }

    private fun currentPlaybackPositionMs(): Long {
        val state = activeController?.playbackState ?: return 0
        val elapsed = if (state.state == PlaybackState.STATE_PLAYING) {
            (android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed
        } else 0f
        return (state.position + elapsed).toLong().coerceAtLeast(0)
    }

    /** lrclib.net 에서 아티스트/제목으로 싱크 가사(LRC)를 검색한다. 싱크 가사가 없으면 일반 가사로 대체. */
    private fun fetchSyncedLyrics(artist: String, title: String): List<Pair<Long?, String>>? {
        // 1차: lrclib - 제목+아티스트로 정확 검색 (싱크 가사 가능)
        searchLrcLib("track_name=${enc(title)}&artist_name=${enc(artist)}")?.let { return it }
        // 2차: lrclib - 통합 검색어로 넓게 검색
        searchLrcLib("q=${enc("$artist $title")}")?.let { return it }
        // 3차(보조 소스): lyrics.ovh - lrclib에 없는 곡 중 일부는 여기 있음 (싱크는 없음)
        fetchPlainLyricsFromLyricsOvh(artist, title)?.let { plain ->
            return plain.lines().filter { it.isNotBlank() }.map { null to it }
        }
        return null
    }

    private fun fetchPlainLyricsFromLyricsOvh(artist: String, title: String): String? {
        return try {
            val url = java.net.URL("https://api.lyrics.ovh/v1/${enc(artist)}/${enc(title)}")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "GET"

            if (connection.responseCode != 200) return null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            org.json.JSONObject(body).optString("lyrics", "").trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    private fun searchLrcLib(queryString: String): List<Pair<Long?, String>>? {
        return try {
            val url = java.net.URL("https://lrclib.net/api/search?$queryString")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "GET"
            // lrclib.net은 User-Agent가 없는 요청을 차단한다
            connection.setRequestProperty("User-Agent", "NOWDISPLAY/1.0 (Android now-playing app)")

            if (connection.responseCode != 200) return null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val results = org.json.JSONArray(body)
            if (results.length() == 0) return null

            var plainFallback: String? = null
            for (i in 0 until results.length()) {
                val obj = results.getJSONObject(i)
                val synced = obj.optString("syncedLyrics", "")
                if (synced.isNotBlank()) {
                    val parsed = parseLrc(synced)
                    if (parsed.isNotEmpty()) return parsed
                }
                if (plainFallback == null) {
                    val plain = obj.optString("plainLyrics", "")
                    if (plain.isNotBlank()) plainFallback = plain
                }
            }
            plainFallback?.lines()
                ?.filter { it.isNotBlank() }
                ?.map { null to it }
        } catch (e: Exception) {
            null
        }
    }

    /** "[00:12.34]가사 내용" 형식의 LRC 텍스트를 (타임스탬프ms, 텍스트) 목록으로 변환 */
    private fun parseLrc(lrc: String): List<Pair<Long?, String>> {
        val pattern = Regex("""\[(\d{2}):(\d{2}(?:\.\d{1,3})?)\](.*)""")
        return lrc.lines().mapNotNull { line ->
            val match = pattern.find(line) ?: return@mapNotNull null
            val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val seconds = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val text = match.groupValues[3].trim()
            if (text.isEmpty()) return@mapNotNull null
            val timeMs = minutes * 60_000L + (seconds * 1000).toLong()
            timeMs to text
        }
    }

    private fun formatMillis(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
