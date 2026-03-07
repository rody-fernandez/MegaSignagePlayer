package com.mega.signageplayer

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var imageView: ImageView
    private lateinit var txtServer: TextView
    private lateinit var txtPlayer: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtCode: TextView
    private lateinit var txtScreen: TextView
    private lateinit var txtDetail: TextView
    private lateinit var infoPanel: View

    private val serverUrl = "http://192.168.134.1:3000"
    private val playerName = "ANDROID-BOX"

    private var token: String = ""
    private var pairingCode: String = ""
    private var assignedScreen: String = ""
    private var player: ExoPlayer? = null

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("mega_signage_player", Context.MODE_PRIVATE) }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            mainHandler.postDelayed(this, 5000)
        }
    }

    private val configRunnable = object : Runnable {
        override fun run() {
            fetchConfig()
            mainHandler.postDelayed(this, 7000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        imageView = findViewById(R.id.imageView)
        infoPanel = findViewById(R.id.infoPanel)

        txtServer = findViewById(R.id.txtServer)
        txtPlayer = findViewById(R.id.txtPlayer)
        txtStatus = findViewById(R.id.txtStatus)
        txtCode = findViewById(R.id.txtCode)
        txtScreen = findViewById(R.id.txtScreen)
        txtDetail = findViewById(R.id.txtDetail)

        txtServer.text = "Servidor: $serverUrl"
        txtPlayer.text = "Player: $playerName"
        txtStatus.text = "Estado: iniciando..."
        txtCode.text = "------"
        txtScreen.visibility = View.GONE
        txtDetail.text = ""

        token = prefs.getString("token", "") ?: ""
        pairingCode = prefs.getString("pairing_code", "") ?: ""

        if (token.isNotBlank() && pairingCode.isNotBlank()) {
            txtStatus.text = "Estado: reconectando..."
            txtCode.text = pairingCode
            startLoops()
            fetchConfig()
        } else {
            registerPlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun startLoops() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.removeCallbacks(configRunnable)
        mainHandler.post(heartbeatRunnable)
        mainHandler.post(configRunnable)
    }

    private fun registerPlayer() {
        txtStatus.text = "Estado: registrando..."
        txtDetail.text = ""

        Thread {
            try {
                val bodyJson = JSONObject()
                bodyJson.put("name", playerName)

                val request = Request.Builder()
                    .url("$serverUrl/api/player/register")
                    .post(bodyJson.toString().toRequestBody(jsonType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseText = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        runOnUiThread {
                            txtStatus.text = "Estado: error register"
                            txtDetail.text = "HTTP ${response.code}: $responseText"
                        }
                        return@use
                    }

                    val json = JSONObject(responseText)
                    token = json.optString("token", "")
                    pairingCode = json.optString("pairing_code", "")

                    if (token.isBlank() || pairingCode.isBlank()) {
                        runOnUiThread {
                            txtStatus.text = "Estado: error register"
                            txtDetail.text = "Respuesta inválida"
                        }
                        return@use
                    }

                    prefs.edit()
                        .putString("token", token)
                        .putString("pairing_code", pairingCode)
                        .apply()

                    runOnUiThread {
                        txtStatus.text = "Estado: esperando vinculación"
                        txtCode.text = pairingCode
                        txtDetail.text = ""
                    }

                    startLoops()
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
                val bodyJson = JSONObject()
                bodyJson.put("token", token)

                val request = Request.Builder()
                    .url("$serverUrl/api/player/heartbeat")
                    .post(bodyJson.toString().toRequestBody(jsonType))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        runOnUiThread {
                            txtDetail.text = "Heartbeat HTTP ${response.code}"
                        }
                    }
                }
            } catch (_: IOException) {
                runOnUiThread {
                    txtDetail.text = "Heartbeat sin conexión"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    txtDetail.text = e.toString()
                }
            }
        }.start()
    }

    private fun fetchConfig() {
        if (token.isBlank()) return

        Thread {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/api/player/config?token=$token")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseText = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        runOnUiThread {
                            txtStatus.text = "Estado: error config"
                            txtDetail.text = "HTTP ${response.code}: $responseText"
                        }
                        return@use
                    }

                    val json = JSONObject(responseText)
                    val paired = json.optBoolean("paired", false)

                    if (!paired) {
                        runOnUiThread {
                            txtStatus.text = "Estado: esperando vinculación"
                            txtCode.text = pairingCode
                            txtScreen.visibility = View.GONE
                            txtDetail.text = ""
                        }
                        return@use
                    }

                    assignedScreen = json.optString("screen", "-")
                    val itemsArray = json.optJSONArray("items") ?: JSONArray()

                    runOnUiThread {
                        txtStatus.text = "Estado: vinculado"
                        txtCode.text = pairingCode
                        txtScreen.text = "Pantalla: $assignedScreen"
                        txtScreen.visibility = View.VISIBLE
                        txtDetail.text = ""
                    }

                    if (itemsArray.length() > 0) {
                        val firstItem = itemsArray.getJSONObject(0)
                        val remoteUrl = firstItem.optString("url", "")
                        if (remoteUrl.isNotBlank()) {
                            val fullUrl = if (remoteUrl.startsWith("http")) remoteUrl else "$serverUrl$remoteUrl"
                            val localFile = downloadFile(fullUrl)
                            playLocalFile(localFile)
                        }
                    }
                }
            } catch (_: IOException) {
                runOnUiThread {
                    txtDetail.text = "Config sin conexión"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    txtDetail.text = e.toString()
                }
            }
        }.start()
    }

    private fun downloadFile(url: String): File {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("HTTP download ${response.code}")
        }

        val body = response.body ?: throw Exception("Body vacío")
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw Exception("Directorio local no disponible")

        val fileName = Uri.parse(url).lastPathSegment ?: "media.bin"
        val file = File(dir, fileName)

        body.byteStream().use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }

        return file
    }

    private fun playLocalFile(file: File) {
        val name = file.name.lowercase()

        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp")) {
            runOnUiThread {
                releasePlayer()
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                imageView.setImageBitmap(bitmap)
                imageView.visibility = View.VISIBLE
                playerView.visibility = View.GONE
                infoPanel.visibility = View.GONE
            }
            return
        }

        runOnUiThread {
            imageView.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            infoPanel.visibility = View.GONE
            startVideoPlayback(file)
        }
    }

    private fun startVideoPlayback(file: File) {
        releasePlayer()

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL

        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        exo.setMediaItem(mediaItem)
        exo.prepare()
        exo.playWhenReady = true

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    exo.seekTo(0)
                    exo.playWhenReady = true
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                txtDetail.text = error.message ?: "Error reproduciendo"
                infoPanel.visibility = View.VISIBLE
            }
        })
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }
}