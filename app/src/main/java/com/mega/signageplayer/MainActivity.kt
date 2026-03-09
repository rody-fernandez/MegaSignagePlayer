package com.example.megasignageplayer

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var mediaContainer: FrameLayout
    private lateinit var videoView: VideoView
    private lateinit var imageView: ImageView
    private lateinit var infoPanel: LinearLayout
    private lateinit var txtStatus: TextView
    private lateinit var txtCode: TextView
    private lateinit var txtScreen: TextView
    private lateinit var txtDetail: TextView

    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // CAMBIA ESTA IP POR LA DE TU SERVIDOR CMS
    private val server = "http://192.168.134.1:3000"
    private val playerName = "ANDROID-BOX"

    private var token: String = ""
    private var pairingCode: String = ""
    private var assignedScreen: String = ""

    private var heartbeatTimer: Timer? = null
    private var configTimer: Timer? = null
    private var imageTimer: Timer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scheduledSyncRunnable: Runnable? = null
    private var lastSyncSeq = 0

    private var playlistSignature: String = ""
    private var playlistItems: MutableList<PlaylistItem> = mutableListOf()
    private var currentIndex: Int = 0

    private var isPlayingMedia = false
    private var isDownloading = false

    private var screenWidthPx: Int = 128
    private var screenHeightPx: Int = 512
    private var screenFit: String = "contain"
    private var screenOrientation: String = "vertical"
    private var screenXOffset: Int = 0
    private var screenYOffset: Int = 0

    data class PlaylistItem(
        val id: Int,
        val name: String,
        val url: String,
        val localPath: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_main)
        hideSystemUi()

        rootLayout = findViewById(R.id.rootLayout)
        mediaContainer = findViewById(R.id.mediaContainer)
        videoView = findViewById(R.id.videoView)
        imageView = findViewById(R.id.imageView)
        infoPanel = findViewById(R.id.infoPanel)
        txtStatus = findViewById(R.id.txtStatus)
        txtCode = findViewById(R.id.txtCode)
        txtScreen = findViewById(R.id.txtScreen)
        txtDetail = findViewById(R.id.txtDetail)

        txtStatus.text = "Estado: iniciando..."
        txtCode.text = "------"
        txtScreen.visibility = View.GONE
        txtDetail.text = ""

        val prefs = getSharedPreferences("mega_signage_player", Context.MODE_PRIVATE)
        token = prefs.getString("token", "") ?: ""
        pairingCode = prefs.getString("pairing_code", "") ?: ""

        if (token.isNotBlank() && pairingCode.isNotBlank()) {
            txtStatus.text = "Estado: reconectando..."
            txtCode.text = pairingCode
            startHeartbeat()
            startConfigPolling()
            fetchConfig()
        } else {
            registerPlayer()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatTimer?.cancel()
        configTimer?.cancel()
        imageTimer?.cancel()
        scheduledSyncRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    private fun hideSystemUi() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun startHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = Timer()

        heartbeatTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                sendHeartbeat()
            }
        }, 0, 5000)
    }

    private fun startConfigPolling() {
        configTimer?.cancel()
        configTimer = Timer()

        configTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                fetchConfig()
            }
        }, 0, 4000)
    }

    private fun registerPlayer() {
        runOnUiThread {
            txtStatus.text = "Estado: registrando..."
            txtDetail.text = ""
        }

        Thread {
            try {
                val json = JSONObject()
                json.put("name", playerName)

                val request = Request.Builder()
                    .url("$server/api/player/register")
                    .post(json.toString().toRequestBody(jsonType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        runOnUiThread {
                            txtStatus.text = "Estado: error register"
                            txtDetail.text = "HTTP ${response.code}: $body"
                        }
                        return@use
                    }

                    val data = JSONObject(body)
                    token = data.optString("token", "")
                    pairingCode = data.optString("pairing_code", "")

                    if (token.isBlank() || pairingCode.isBlank()) {
                        runOnUiThread {
                            txtStatus.text = "Estado: respuesta inválida"
                            txtDetail.text = body
                        }
                        return@use
                    }

                    val prefs = getSharedPreferences("mega_signage_player", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("token", token)
                        .putString("pairing_code", pairingCode)
                        .apply()

                    runOnUiThread {
                        txtStatus.text = "Estado: esperando vinculación"
                        txtCode.text = pairingCode
                        txtDetail.text = ""
                    }

                    startHeartbeat()
                    startConfigPolling()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    txtStatus.text = "Estado: error register"
                    txtDetail.text = e.toString()
                }
            }
        }.start()
    }

    private fun sendHeartbeat() {
        if (token.isBlank()) return

        Thread {
            try {
                val json = JSONObject()
                json.put("token", token)

                val request = Request.Builder()
                    .url("$server/api/player/heartbeat")
                    .post(json.toString().toRequestBody(jsonType))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && !isPlayingMedia) {
                        runOnUiThread {
                            txtDetail.text = "Heartbeat HTTP ${response.code}"
                        }
                    }
                }
            } catch (_: IOException) {
                if (!isPlayingMedia) {
                    runOnUiThread {
                        txtDetail.text = "Heartbeat sin conexión"
                    }
                }
            } catch (e: Exception) {
                if (!isPlayingMedia) {
                    runOnUiThread {
                        txtDetail.text = e.toString()
                    }
                }
            }
        }.start()
    }

    private fun fetchConfig() {
        if (token.isBlank()) return

        Thread {
            try {
                val request = Request.Builder()
                    .url("$server/api/player/config?token=$token")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        if (!isPlayingMedia) {
                            runOnUiThread {
                                txtStatus.text = "Estado: error config"
                                txtDetail.text = "HTTP ${response.code}: $body"
                            }
                        }
                        return@use
                    }

                    val data = JSONObject(body)
                    val paired = data.optBoolean("paired", false)

                    if (!paired) {
                        runOnUiThread {
                            isPlayingMedia = false
                            infoPanel.visibility = View.VISIBLE
                            mediaContainer.visibility = View.GONE
                            txtStatus.text = "Estado: esperando vinculación"
                            txtCode.text = pairingCode
                            txtScreen.visibility = View.GONE
                            txtDetail.text = ""
                        }
                        return@use
                    }

                    assignedScreen = data.optString("screen", "-")

                    val screenCfg = data.optJSONObject("screen_cfg")
                    if (screenCfg != null) {
                        screenWidthPx = screenCfg.optInt("width_px", 128)
                        screenHeightPx = screenCfg.optInt("height_px", 512)
                        screenFit = screenCfg.optString("fit", "contain")
                        screenOrientation = screenCfg.optString("orientation", "vertical")
                        screenXOffset = screenCfg.optInt("x_offset", 0)
                        screenYOffset = screenCfg.optInt("y_offset", 0)
                    }

                    val items = data.optJSONArray("items") ?: JSONArray()

                    runOnUiThread {
                        txtStatus.text = "Estado: vinculado"
                        txtCode.text = pairingCode
                        txtScreen.text = "Pantalla: $assignedScreen"
                        txtScreen.visibility = View.VISIBLE
                        applyViewport()
                    }

                    val newSignature = buildPlaylistSignature(items)

                    if (newSignature != playlistSignature) {
                        if (!isDownloading) {
                            isDownloading = true
                            try {
                                val newList = mutableListOf<PlaylistItem>()

                                for (i in 0 until items.length()) {
                                    val obj = items.getJSONObject(i)
                                    val id = obj.optInt("id", 0)
                                    val name = obj.optString("name", "")
                                    val relativeUrl = obj.optString("url", "")
                                    if (relativeUrl.isBlank()) continue

                                    val fullUrl = if (relativeUrl.startsWith("http")) {
                                        relativeUrl
                                    } else {
                                        "$server$relativeUrl"
                                    }

                                    val localFile = downloadFile(fullUrl)

                                    newList.add(
                                        PlaylistItem(
                                            id = id,
                                            name = name,
                                            url = fullUrl,
                                            localPath = localFile.absolutePath
                                        )
                                    )
                                }

                                playlistItems = newList
                                playlistSignature = newSignature
                                currentIndex = 0

                                if (playlistItems.isNotEmpty() && !isPlayingMedia) {
                                    playItem(playlistItems[currentIndex])
                                }
                            } finally {
                                isDownloading = false
                            }
                        }
                    }

                    // sincronización
                    val sync = data.optJSONObject("sync")
                    if (sync != null) {
                        val startAt = sync.optLong("startAt", 0L)
                        val seq = sync.optInt("seq", 0)

                        if (seq > lastSyncSeq && startAt > 0) {
                            lastSyncSeq = seq
                            scheduleSyncStart(startAt)
                        }
                    }
                }
            } catch (_: IOException) {
                if (!isPlayingMedia) {
                    runOnUiThread {
                        txtDetail.text = "Config sin conexión"
                    }
                }
            } catch (e: Exception) {
                if (!isPlayingMedia) {
                    runOnUiThread {
                        txtDetail.text = e.toString()
                    }
                }
            }
        }.start()
    }

    private fun scheduleSyncStart(startAt: Long) {
        scheduledSyncRunnable?.let { mainHandler.removeCallbacks(it) }

        val delay = startAt - System.currentTimeMillis()

        val runnable = Runnable {
            if (playlistItems.isEmpty()) return@Runnable

            currentIndex = 0
            playItem(playlistItems[currentIndex])

            runOnUiThread {
                txtDetail.text = "SYNC OK @ $startAt"
            }
        }

        scheduledSyncRunnable = runnable

        if (delay <= 0) {
            mainHandler.post(runnable)
        } else {
            mainHandler.postDelayed(runnable, delay)
            runOnUiThread {
                txtDetail.text = "SYNC esperando ${delay}ms"
            }
        }
    }

    private fun applyViewport() {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        val srcW = max(1, screenWidthPx)
        val srcH = max(1, screenHeightPx)

        val scale = when (screenFit.lowercase()) {
            "cover" -> max(screenW.toFloat() / srcW, screenH.toFloat() / srcH)
            "stretch" -> -1f
            else -> min(screenW.toFloat() / srcW, screenH.toFloat() / srcH)
        }

        val targetW: Int
        val targetH: Int

        if (scale < 0f) {
            targetW = screenW
            targetH = screenH
        } else {
            targetW = (srcW * scale).roundToInt()
            targetH = (srcH * scale).roundToInt()
        }

        val params = mediaContainer.layoutParams as FrameLayout.LayoutParams
        params.width = targetW
        params.height = targetH
        params.gravity = Gravity.TOP or Gravity.START
        mediaContainer.layoutParams = params
    }

    private fun buildPlaylistSignature(items: JSONArray): String {
        val parts = mutableListOf<String>()
        for (i in 0 until items.length()) {
            val obj = items.getJSONObject(i)
            parts.add("${obj.optInt("id", 0)}|${obj.optString("url", "")}")
        }
        return parts.joinToString("||")
    }

    private fun downloadFile(url: String): File {
        val fileName = Uri.parse(url).lastPathSegment ?: "media.bin"
        val file = File(filesDir, fileName)

        if (file.exists() && file.length() > 0) {
            return file
        }

        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP download ${response.code}")
            }

            val body = response.body ?: throw Exception("Body vacío")

            body.byteStream().use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }

        return file
    }

    private fun playItem(item: PlaylistItem) {
        if (isImage(item.localPath)) {
            playImage(item)
        } else {
            playVideo(item)
        }
    }

    private fun playImage(item: PlaylistItem) {
        runOnUiThread {
            hideSystemUi()
            isPlayingMedia = true

            videoView.stopPlayback()
            videoView.visibility = View.GONE

            val bitmap = BitmapFactory.decodeFile(item.localPath)
            imageView.setImageBitmap(bitmap)
            imageView.visibility = View.VISIBLE

            mediaContainer.visibility = View.VISIBLE
            infoPanel.visibility = View.GONE

            imageTimer?.cancel()
            imageTimer = Timer()
            imageTimer?.schedule(object : TimerTask() {
                override fun run() {
                    playNextItem()
                }
            }, 8000)
        }
    }

    private fun playVideo(item: PlaylistItem) {
        runOnUiThread {
            hideSystemUi()
            isPlayingMedia = true

            imageTimer?.cancel()
            imageView.visibility = View.GONE
            videoView.visibility = View.VISIBLE
            mediaContainer.visibility = View.VISIBLE
            infoPanel.visibility = View.GONE

            videoView.stopPlayback()
            videoView.setVideoPath(item.localPath)

            videoView.setOnPreparedListener { mp ->
                mp.isLooping = false
                mp.seekTo(0)
                videoView.start()
            }

            videoView.setOnCompletionListener {
                playNextItem()
            }

            videoView.setOnErrorListener { _, _, _ ->
                playNextItem()
                true
            }
        }
    }

    private fun playNextItem() {
        if (playlistItems.isEmpty()) return

        currentIndex++
        if (currentIndex >= playlistItems.size) {
            currentIndex = 0
        }

        val next = playlistItems[currentIndex]
        playItem(next)
    }

    private fun isImage(path: String): Boolean {
        val p = path.lowercase()
        return p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".webp")
    }
}