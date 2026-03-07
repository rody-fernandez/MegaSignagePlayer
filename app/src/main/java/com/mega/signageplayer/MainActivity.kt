package com.example.megasignageplayer

import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView

    private val client = OkHttpClient()

    private val server = "http://192.168.134.1:3000"
    private var token: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        videoView = findViewById(R.id.videoView)

        registerPlayer()
    }

    // ==========================
    // REGISTER PLAYER
    // ==========================

    private fun registerPlayer() {

        val json = JSONObject()
        json.put("name", "ANDROID-BOX")

        val body = RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            json.toString()
        )

        val request = Request.Builder()
            .url("$server/api/player/register")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {

                val data = JSONObject(response.body!!.string())

                token = data.getString("token")

                runOnUiThread {
                    checkConfig()
                }
            }
        })
    }

    // ==========================
    // CHECK CONFIG
    // ==========================

    private fun checkConfig() {

        val request = Request.Builder()
            .url("$server/api/player/config?token=$token")
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {

                val data = JSONObject(response.body!!.string())

                val paired = data.getBoolean("paired")

                if (!paired) return

                val items = data.getJSONArray("items")

                if (items.length() == 0) return

                val first = items.getJSONObject(0)

                val url = first.getString("url")

                downloadVideo(url)
            }
        })
    }

    // ==========================
    // DOWNLOAD VIDEO
    // ==========================

    private fun downloadVideo(url: String) {

        val request = Request.Builder()
            .url(server + url)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {

                val file = File(filesDir, "video.mp4")

                val input = response.body!!.byteStream()

                val output = FileOutputStream(file)

                input.copyTo(output)

                input.close()
                output.close()

                runOnUiThread {
                    playVideo(file)
                }
            }
        })
    }

    // ==========================
    // PLAY VIDEO
    // ==========================

    private fun playVideo(file: File) {

        val uri = Uri.fromFile(file)

        videoView.setVideoURI(uri)

        videoView.setOnPreparedListener { mp ->

            mp.isLooping = true

            mp.seekTo(0)

            videoView.start()
        }
    }
}