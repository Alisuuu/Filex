package com.alisu.filex

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alisu.filex.util.*
import com.alisu.filex.core.ArchiveManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.*
import java.io.File
import java.util.regex.Pattern

class EditorActivity : AppCompatActivity() {

    private lateinit var filePath: String
    private lateinit var editText: EditText
    private lateinit var txtLineNumbers: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var settings: SettingsManager
    private var isFromArchive = false
    private var highlightJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        settings = SettingsManager(this)
        val toolbar = findViewById<MaterialToolbar>(R.id.editorToolbar)
        editText = findViewById(R.id.editContent)
        txtLineNumbers = findViewById(R.id.lineNumbers)
        progressBar = findViewById(R.id.editorProgress)

        filePath = intent.getStringExtra("file_path") ?: ""
        isFromArchive = filePath.contains("::")
        
        setupEditor()
        loadFileContent(toolbar)

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.menu_editor)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_save) {
                if (isFromArchive) {
                    Toast.makeText(this, getString(R.string.editor_no_save_archive), Toast.LENGTH_SHORT).show()
                } else {
                    saveFile()
                }
                true
            } else false
        }
    }

    private fun setupEditor() {
        // Sincronização de Scroll Vertical (O segredo da performance)
        editText.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            txtLineNumbers.scrollTo(0, scrollY)
        }

        // Suporte a TAB
        editText.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_TAB && event.action == android.view.KeyEvent.ACTION_DOWN) {
                val start = editText.selectionStart
                val end = editText.selectionEnd
                editText.editableText.replace(start, end, "    ")
                true
            } else false
        }

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateLineNumbers()
            }
            override fun afterTextChanged(s: Editable?) {
                // Debounce para realce de sintaxe (espera 500ms sem digitar)
                highlightJob?.cancel()
                highlightJob = lifecycleScope.launch {
                    delay(500)
                    applyHighlighting(s)
                }
            }
        })
    }

    private fun updateLineNumbers() {
        val lineCount = editText.lineCount
        val linesText = StringBuilder()
        for (i in 1..maxOf(1, lineCount)) {
            linesText.append(i).append("\n")
        }
        txtLineNumbers.text = linesText.toString()
    }

    private suspend fun applyHighlighting(editable: Editable?) {
        if (editable == null || editable.length > 20000) return 
        
        withContext(Dispatchers.Default) {
            val keywords = Pattern.compile("\\b(package|import|class|fun|function|var|val|let|const|if|else|for|while|return|public|private|protected|override|string|int|boolean|true|false|null|interface|enum|when|try|catch|finally)\\b")
            val strings = Pattern.compile("\".*?\"|'.*?'")
            val comments = Pattern.compile("//.*|/\\*.*?\\*/")

            val keywordMatcher = keywords.matcher(editable)
            val stringMatcher = strings.matcher(editable)
            val commentMatcher = comments.matcher(editable)

            withContext(Dispatchers.Main) {
                // Remove spans antigos de cor
                val spans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
                for (span in spans) editable.removeSpan(span)

                // Aplica novos spans
                while (keywordMatcher.find()) {
                    editable.setSpan(ForegroundColorSpan(Color.parseColor("#569CD6")), keywordMatcher.start(), keywordMatcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                while (stringMatcher.find()) {
                    editable.setSpan(ForegroundColorSpan(Color.parseColor("#CE9178")), stringMatcher.start(), stringMatcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                while (commentMatcher.find()) {
                    editable.setSpan(ForegroundColorSpan(Color.parseColor("#6A9955")), commentMatcher.start(), commentMatcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun loadFileContent(toolbar: MaterialToolbar) {
        val fileName = if (isFromArchive) filePath.substringAfter("::").removeSuffix("/").substringAfterLast("/") else File(filePath).name
        toolbar.title = fileName
        if (isFromArchive) toolbar.subtitle = getString(R.string.editor_readonly)

        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            val content = withContext(Dispatchers.IO) {
                try {
                    if (isFromArchive) {
                        val archivePath = filePath.substringBefore("::")
                        val entryName = filePath.substringAfter("::")
                        val tempFile = File(cacheDir, "editor_archive_tmp")
                        if (ArchiveManager.extractEntry(archivePath, entryName, tempFile)) tempFile.readText() else null
                    } else {
                        val file = File(filePath)
                        if (file.exists() && file.canRead()) file.readText() else {
                            val output = when (settings.accessMode) {
                                SettingsManager.MODE_ROOT -> RootUtil.execute("cat \"$filePath\"")
                                SettingsManager.MODE_SHIZUKU -> ShizukuUtil.execute("cat \"$filePath\"")
                                else -> emptyList()
                            }
                            if (output.isNotEmpty()) output.joinToString("\n") else null
                        }
                    }
                } catch (e: Exception) { null }
            }

            progressBar.visibility = View.GONE
            if (content != null) {
                editText.setText(content)
                editText.post { updateLineNumbers() }
            } else {
                Toast.makeText(this@EditorActivity, getString(R.string.editor_load_error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveFile() {
        val newContent = editText.text.toString()
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            val success = withContext(Dispatchers.IO) {
                try {
                    val file = File(filePath)
                    if (file.canWrite()) {
                        file.writeText(newContent)
                        true
                    } else {
                        val tempFile = File(cacheDir, "editor_temp").apply { writeText(newContent) }
                        val success = when (settings.accessMode) {
                            SettingsManager.MODE_ROOT -> RootUtil.runCommand("cp \"${tempFile.absolutePath}\" \"$filePath\"")
                            SettingsManager.MODE_SHIZUKU -> ShizukuUtil.runCommand("cp \"${tempFile.absolutePath}\" \"$filePath\"")
                            else -> false
                        }
                        tempFile.delete()
                        success
                    }
                } catch (e: Exception) { false }
            }
            progressBar.visibility = View.GONE
            Toast.makeText(this@EditorActivity, if (success) getString(R.string.editor_save_success) else getString(R.string.editor_save_error), Toast.LENGTH_SHORT).show()
        }
    }
}
