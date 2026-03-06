package com.mega.signageplayer

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var txtStatus: TextView
    private lateinit var txtDetail: TextView

    private var player: ExoPlayer? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient()

    // ========= CAMBIÁ SOLO ESTO SI QUERÉS PROBAR OTRO VIDEO =========
    private val testVideoUrl =
        "http://192.168.134.1:3000/uploads/1772806340299_075d08bcade95aaa.mp4"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        txtStatus = findViewById(R.id.txtStatus)
        txtDetail = findViewById(R.id.txtDetail)

        txtStatus.text = "Descargando video de prueba..."
        txtDetail.text = ""

        executor.execute {
            try {
                val localFile = downloadToLocal(testVideoUrl)
                runOnUiThread {
                    txtStatus.text = "Reproduciendo archivo local"
                    txtDetail.text = localFile.absolutePath
                    startPlayback(localFile)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    txtStatus.text = "Error"
                    txtDetail.text = e.toString()
                }
            }
        }
    }

    private fun downloadToLocal(url: String): File {
        val request = Request.Builder().url(url).build()
        val response = http.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}")
        }

        val body = response.body ?: throw Exception("Body vacío")
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw Exception("No se pudo abrir directorio local")

        val file = File(dir, "test_video.mp4")

        body.byteStream().use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }

        return file
    }

    private fun startPlayback(file: File) {
        releasePlayer()

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo

        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        exo.setMediaItem(mediaItem)
        exo.prepare()
        exo.playWhenReady = true

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        txtStatus.text = "Buffering..."
                    }
                    Player.STATE_READY -> {
                        txtStatus.text = "Reproduciendo"
                        txtDetail.text = file.absolutePath
                    }
                    Player.STATE_ENDED -> {
                        txtStatus.text = "Finalizó - reiniciando"
                        exo.seekTo(0)
                        exo.playWhenReady = true
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                txtStatus.text = "Error reproduciendo"
                txtDetail.text = error.message ?: error.toString()
            }
        })

        playerView.useController = false
        playerView.visibility = View.VISIBLE
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        executor.shutdownNow()
    }
}