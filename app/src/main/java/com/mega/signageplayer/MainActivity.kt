package com.mega.signageplayer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var mediaContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var imageView: ImageView

    private lateinit var txtStatus: TextView
    private lateinit var txtCode: TextView
    private lateinit var txtScreen: TextView
    private lateinit var txtDetail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        mediaContainer = findViewById(R.id.mediaContainer)

        playerView = findViewById(R.id.playerView)
        imageView = findViewById(R.id.imageView)

        txtStatus = findViewById(R.id.txtStatus)
        txtCode = findViewById(R.id.txtCode)
        txtScreen = findViewById(R.id.txtScreen)
        txtDetail = findViewById(R.id.txtDetail)

        txtStatus.text = "Mega Signage Player iniciado"
        txtCode.text = "000000"
        txtScreen.text = "Pantalla: -"
        txtDetail.text = "Esperando configuración..."
    }
}