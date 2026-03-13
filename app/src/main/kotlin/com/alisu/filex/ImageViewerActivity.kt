package com.alisu.filex

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.alisu.filex.databinding.ActivityImageViewerBinding
import com.alisu.filex.util.VectorRenderer
import com.bumptech.glide.Glide
import com.caverock.androidsvg.SVG
import java.io.File
import java.io.FileInputStream

class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // Posiciona o botão de fechar abaixo da barra de status (notch)
            val params = binding.btnClose.layoutParams as android.view.ViewGroup.MarginLayoutParams
            params.topMargin = systemBars.top + 16
            binding.btnClose.layoutParams = params
            
            // Ajusta o card de info para ficar acima da barra de navegação
            val infoParams = binding.infoCard.layoutParams as android.view.ViewGroup.MarginLayoutParams
            infoParams.bottomMargin = systemBars.bottom + 32
            binding.infoCard.layoutParams = infoParams
            
            insets
        }

        val filePath = intent.getStringExtra("file_path") ?: return finish()
        val file = File(filePath)
        
        binding.btnClose.setOnClickListener { finish() }
        
        loadImage(file)
    }

    private fun loadImage(file: File) {
        val path = file.absolutePath
        val extension = file.extension.lowercase()

        try {
            when {
                extension == "svg" -> {
                    val svg = SVG.getFromInputStream(FileInputStream(file))
                    val pictureDrawable = com.caverock.androidsvg.SVGExternalFileResolver().let {
                        android.graphics.drawable.PictureDrawable(svg.renderToPicture())
                    }
                    binding.imgFull.setImageDrawable(pictureDrawable)
                }
                extension == "xml" && VectorRenderer.isAndroidVector(file) -> {
                    val bitmap = VectorRenderer.renderXmlToBitmap(this, file, 2048)
                    binding.imgFull.setImageBitmap(bitmap)
                }
                else -> {
                    Glide.with(this)
                        .load(file)
                        .into(binding.imgFull)
                }
            }
            
            val size = android.text.format.Formatter.formatFileSize(this, file.length())
            binding.txtImageInfo.text = "$size • $extension"
            
        } catch (e: Exception) {
            finish()
        }
    }
}