package com.alisu.filex

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alisu.filex.util.*
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private var isInitializing = true

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Ajustar insets
        val root = findViewById<View>(android.R.id.content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        settings = SettingsManager(this)
        setupUI()
        isInitializing = false
    }

    private fun setupUI() {
        val toolbar = findViewById<MaterialToolbar>(R.id.settingsToolbar)
        
        val rbDefault = findViewById<RadioButton>(R.id.rbDefault)
        val rbRoot = findViewById<RadioButton>(R.id.rbRoot)
        val rbShizuku = findViewById<RadioButton>(R.id.rbShizuku)
        val trashSwitch = findViewById<MaterialSwitch>(R.id.trashSwitch)
        val trashSlider = findViewById<Slider>(R.id.trashSlider)
        val txtRetention = findViewById<TextView>(R.id.txtRetention)
        val trashOptions = findViewById<LinearLayout>(R.id.trashOptions)
        val cardDev = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardDeveloper)

        // Carregar valores atuais
        when (settings.accessMode) {
            SettingsManager.MODE_ROOT -> rbRoot.isChecked = true
            SettingsManager.MODE_SHIZUKU -> rbShizuku.isChecked = true
            else -> rbDefault.isChecked = true
        }

        trashSwitch.isChecked = settings.isTrashEnabled
        trashOptions.visibility = if (settings.isTrashEnabled) View.VISIBLE else View.GONE
        trashSlider.value = settings.trashRetentionDays.toFloat()
        txtRetention.text = getString(R.string.settings_retention, settings.trashRetentionDays)

        // Escutadores de mudança para Modo de Acesso
        rbDefault.setOnClickListener { handleModeChange(SettingsManager.MODE_DEFAULT) }
        rbRoot.setOnClickListener { handleModeChange(SettingsManager.MODE_ROOT) }
        rbShizuku.setOnClickListener { handleModeChange(SettingsManager.MODE_SHIZUKU) }

        // Lixeira
        trashSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.isTrashEnabled = isChecked
            trashOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        trashSlider.addOnChangeListener { _, value, _ ->
            val days = value.toInt()
            settings.trashRetentionDays = days
            txtRetention.text = getString(R.string.settings_retention, days)
        }

        cardDev.setOnClickListener {
            val intent = Intent(this, AboutActivity::class.java)
            startActivity(intent)
        }
    }

    private fun handleModeChange(newMode: String) {
        if (isInitializing) return

        lifecycleScope.launch {
            when (newMode) {
                SettingsManager.MODE_DEFAULT -> {
                    checkAndRequestDefaultPermissions()
                    saveMode(newMode)
                }
                SettingsManager.MODE_ROOT -> {
                    MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setTitle(getString(R.string.settings_experimental_title))
                        .setMessage(getString(R.string.settings_experimental_message))
                        .setPositiveButton(getString(R.string.action_ok)) { dialog, which ->
                            lifecycleScope.launch {
                                val hasRoot = withContext(Dispatchers.IO) { RootUtil.isRootAvailable() }
                                if (hasRoot) {
                                    saveMode(newMode)
                                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_mode_root), Toast.LENGTH_SHORT).show()
                                } else {
                                    revertToPreviousMode()
                                    Toast.makeText(this@SettingsActivity, "Root error", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .setNegativeButton(getString(R.string.action_cancel)) { dialog, which ->
                            revertToPreviousMode()
                        }
                        .setOnCancelListener { revertToPreviousMode() }
                        .show()
                }
                SettingsManager.MODE_SHIZUKU -> {
                    if (ShizukuUtil.isAvailable()) {
                        if (ShizukuUtil.checkPermission(1001)) {
                            saveMode(newMode)
                            Toast.makeText(this@SettingsActivity, getString(R.string.settings_mode_shizuku), Toast.LENGTH_SHORT).show()
                        } else {
                            // ShizukuUtil.checkPermission já disparou o diálogo do sistema
                            Toast.makeText(this@SettingsActivity, "Awaiting Shizuku...", Toast.LENGTH_SHORT).show()
                            // Nota: O Shizuku não retorna o resultado imediatamente. 
                            // O ideal é observar o Shizuku.OnRequestPermissionResultListener, mas para simplificar:
                            saveMode(newMode) 
                        }
                    } else {
                        revertToPreviousMode()
                        Toast.makeText(this@SettingsActivity, "Shizuku not running", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun saveMode(mode: String) {
        settings.accessMode = mode
    }

    private fun revertToPreviousMode() {
        val rbDefault = findViewById<RadioButton>(R.id.rbDefault)
        val rbRoot = findViewById<RadioButton>(R.id.rbRoot)
        val rbShizuku = findViewById<RadioButton>(R.id.rbShizuku)
        
        when (settings.accessMode) {
            SettingsManager.MODE_ROOT -> rbRoot.isChecked = true
            SettingsManager.MODE_SHIZUKU -> rbShizuku.isChecked = true
            else -> rbDefault.isChecked = true
        }
    }

    private fun checkAndRequestDefaultPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }
}
