package com.alisu.filex.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Xml
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

object VectorRenderer {

    fun renderXmlToBitmap(context: Context, file: File, size: Int): Bitmap? {
        return try {
            var xmlContent = try {
                file.readText()
            } catch (e: Exception) {
                // Se falhar a leitura direta (ex: Android/data), tenta via shell se possível
                val output = RootUtil.execute("cat \"${file.absolutePath}\"")
                if (output.isNotEmpty()) output.joinToString("\n") else return null
            }
            
            if (xmlContent.isBlank()) return null

            // SANITIZAÇÃO AVANÇADA:
            val sanitizedXml = xmlContent
                .replace(Regex("=\"\\?[^\"]+\""), "=\"#FF666666\"")
                .replace(Regex("=\"@[^\"]+\""), "=\"#FF666666\"")
                // Se não tiver cor definida, coloca uma cor padrão
                .let { if (!it.contains("android:fillColor")) it.replace("<path", "<path android:fillColor=\"#FF666666\"") else it }
                .replace("dip\"", "dp\"")

            val inputStream = sanitizedXml.byteInputStream()
            inputStream.use { stream ->
                val parser = Xml.newPullParser()
                parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", true)
                parser.setInput(stream, "UTF-8")

                var type = parser.next()
                while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) {
                    type = parser.next()
                }

                if (type != XmlPullParser.START_TAG) return null

                val drawable: Drawable? = if (parser.name == "vector") {
                    VectorDrawableCompat.createFromXml(context.resources, parser, context.theme)
                } else {
                    Drawable.createFromXml(context.resources, parser, context.theme)
                }
                
                drawable?.let {
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    it.setBounds(0, 0, size, size)
                    it.draw(canvas)
                    bitmap
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun isAndroidVector(file: File): Boolean {
        val name = file.name.lowercase()
        if (!name.endsWith(".xml")) return false
        return try {
            val content = try {
                file.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                val output = RootUtil.execute("head -n 20 \"${file.absolutePath}\"")
                if (output.isNotEmpty()) output.joinToString("\n") else ""
            }
            
            val cleanContent = content.lowercase()
            cleanContent.contains("<vector") || cleanContent.contains("<shape") || 
            cleanContent.contains("<selector") || cleanContent.contains("<layer-list") ||
            cleanContent.contains("<animated-vector")
        } catch (e: Exception) {
            false
        }
    }
}
