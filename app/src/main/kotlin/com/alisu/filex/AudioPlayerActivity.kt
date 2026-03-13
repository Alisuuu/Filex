package com.alisu.filex

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.alisu.filex.databinding.ActivityAudioPlayerBinding
import java.io.File

class AudioPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAudioPlayerBinding
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAudioPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filePath = intent.getStringExtra("file_path") ?: return finish()
        val file = File(filePath)
        currentFile = file

        binding.txtAudioTitle.text = file.name
        binding.btnClose.setOnClickListener { finish() }
        
        // Carregar Capa do Álbum
        com.bumptech.glide.Glide.with(this)
            .load(file.path)
            .centerCrop()
            .placeholder(R.drawable.fsm_audio)
            .error(R.drawable.fsm_audio)
            .into(binding.imgAlbumArt)
        
        initializePlayer(file)
        setupControls()
    }

    private fun initializePlayer(file: File) {
        player = ExoPlayer.Builder(this).build().apply {
            val mediaItem = MediaItem.Builder()
                .setUri(android.net.Uri.fromFile(file))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(file.name).build())
                .build()
            setMediaItem(mediaItem)
            prepare()
            play()
            
            addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                    val title = if (!metadata.title.isNullOrEmpty()) metadata.title.toString() else currentFile?.name ?: "Áudio"
                    val artist = if (!metadata.artist.isNullOrEmpty()) metadata.artist.toString() else "Artista desconhecido"
                    binding.txtAudioTitle.text = "$title\n$artist"
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    binding.btnPlayPause.setImageResource(
                        if (isPlaying) android.R.drawable.ic_media_pause 
                        else android.R.drawable.ic_media_play
                    )
                    if (isPlaying) startProgressUpdate()
                }
                
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        binding.txtTotalTime.text = formatTime(duration)
                        binding.audioProgress.valueTo = duration.toFloat().coerceAtLeast(1f)
                    }
                }
            })
        }
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener {
            player?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        }

        binding.audioProgress.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                player?.seekTo(value.toLong())
            }
        }
    }

    private fun startProgressUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                player?.let {
                    if (it.isPlaying) {
                        binding.audioProgress.value = it.currentPosition.toFloat().coerceIn(0f, it.duration.toFloat().coerceAtLeast(1f))
                        binding.txtCurrentTime.text = formatTime(it.currentPosition)
                        handler.postDelayed(this, 1000)
                    }
                }
            }
        })
    }

    private fun formatTime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
}