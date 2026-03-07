package com.example.megasignageplayer

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
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

class MainActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var infoPanel: LinearLayout
    private lateinit var txtStatus: TextView
    private lateinit var txtCode: TextView
    private lateinit var txtScreen: TextView
    private lateinit var txtDetail: TextView

    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val server = "http://192.168.134.1:3000"

    private val playerName = "ANDROID-BOX"

    private var token: String = ""
    private var pairingCode: String = ""
    private var assignedScreen: String = ""

    private var heartbeatTimer: Timer? = null
    private var configTimer: Timer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        videoView = findViewById(R.id.videoView)
        infoPanel = findViewById(R.id.infoPanel)
        txtStatus = findViewById(R.id.txtStatus)
        txtCode = findViewById(R.id.txtCode)
        txtScreen = findViewById(R.id.txtScreen)
        txtDetail = findViewById(R.id.txtDetail)

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

    override fun onDestroy() {
        super.onDestroy()
        heartbeatTimer?.cancel()
        configTimer?.cancel()
    }

    private fun registerPlayer() {
        txtStatus.text = "Estado: registrando..."
        txtDetail.text = ""

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

    private fun startHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = Timer()

        heartbeatTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                sendHeartbeat()
            }
        }, 0, 5000)
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

    private fun startConfigPolling() {
        configTimer?.cancel()
        configTimer = Timer()

        configTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                fetchConfig()
            }
        }, 0, 6000)
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
                        runOnUiThread {
                            txtStatus.text = "Estado: error config"
                            txtDetail.text = "HTTP ${response.code}: $body"
                        }
                        return@use
                    }

                    val data = JSONObject(body)
                    val paired = data.optBoolean("paired", false)

                    if (!paired) {
                        runOnUiThread {
                            infoPanel.visibility = View.VISIBLE
                            videoView.visibility = View.GONE
                            txtStatus.text = "Estado: esperando vinculación"
                            txtCode.text = pairingCode
                            txtScreen.visibility = View.GONE
                            txtDetail.text = ""
                        }
                        return@use
                    }

                    assignedScreen = data.optString("screen", "-")
                    val items = data.optJSONArray("items") ?: JSONArray()

                    runOnUiThread {
                        txtStatus.text = "Estado: vinculado"
                        txtCode.text = pairingCode
                        txtScreen.text = "Pantalla: $assignedScreen"
                        txtScreen.visibility = View.VISIBLE
                    }

                    if (items.length() > 0) {
                        val firstItem = items.getJSONObject(0)
                        val relativeUrl = firstItem.optString("url", "")
                        if (relativeUrl.isNotBlank()) {
                            val fullUrl = if (relativeUrl.startsWith("http")) {
                                relativeUrl
                            } else {
                                "$server$relativeUrl"
                            }

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

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP download ${response.code}")
            }

            val body = response.body ?: throw Exception("Body vacío")
            val dir = filesDir
            val fileName = Uri.parse(url).lastPathSegment ?: "media.bin"
            val file = File(dir, fileName)

            body.byteStream().use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }

            return file
        }
    }

    private fun playLocalFile(file: File) {
        runOnUiThread {
            infoPanel.visibility = View.GONE
            videoView.visibility = View.VISIBLE

            videoView.setVideoPath(file.absolutePath)

            videoView.setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.seekTo(0)
                videoView.start()
            }

            videoView.setOnErrorListener { _, what, extra ->
                txtDetail.text = "Video error what=$what extra=$extra"
                infoPanel.visibility = View.VISIBLE
                videoView.visibility = View.GONE
                true
            }
        }
    }
}