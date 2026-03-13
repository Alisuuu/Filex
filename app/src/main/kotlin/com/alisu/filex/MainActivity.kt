package com.alisu.filex

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Parcelable
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import com.alisu.filex.core.*
import com.alisu.filex.core.FileProvider as VfsProvider
import com.alisu.filex.util.RootUtil
import com.alisu.filex.util.ShizukuUtil
import com.alisu.filex.util.SettingsManager
import com.alisu.filex.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import com.alisu.filex.util.FileTaskManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.io.File
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FileAdapter
    
    private data class FolderState(val scrollState: Parcelable? = null, val selection: Set<String> = emptySet())
    private data class Tab(val title: String, var currentPath: String, var currentProvider: VfsProvider, val history: Stack<Pair<String, VfsProvider>> = Stack(), val states: MutableMap<String, FolderState> = mutableMapOf())
    
    private val tabs = mutableListOf<Tab>()
    private var activeTabIndex = 0
    private var clipboardPaths = mutableListOf<String>()
    private var isMoveOperation = false
    private val DASHBOARD_PATH = "root://dashboard"
    private val shortcuts = mutableListOf<Shortcut>()
    private lateinit var shortcutAdapter: ShortcutAdapter
    private lateinit var recentAdapter: RecentFileAdapter
    private var isInitializing = true
    private var searchJob: kotlinx.coroutines.Job? = null
    
    private var fileService: com.alisu.filex.util.FileTaskService? = null
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            fileService = (service as com.alisu.filex.util.FileTaskService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            fileService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        
        // Pedir permissão de notificação no Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // AppBar totalmente transparente e sem elevação
        binding.appBarLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.appBarLayout.outlineProvider = null 

        // Ajustar insets para que o conteúdo flua por baixo das barras flutuantes
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            
            // Espaçamento do topo para que o conteúdo não seja cortado pela barra de status
            val topOffset = systemBars.top + 172.toPx()
            binding.recyclerView.setPadding(binding.recyclerView.paddingLeft, topOffset, binding.recyclerView.paddingRight, systemBars.bottom + 80.toPx())
            binding.dashboardContent.setPadding(0, topOffset, 0, systemBars.bottom)
            
            // Dar espaço para a barra de status no Toolbar sem criar uma barra preta
            binding.toolbar.setPadding(0, systemBars.top, 0, 0)
            binding.toolbar.layoutParams.height = 48.toPx() + systemBars.top
            
            // Posicionar o botão FAB
            val fabParams = binding.fabActions.parent as android.view.View
            (fabParams.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin = systemBars.bottom + 16.toPx()
            
            // Ajustar o tamanho da sombra inferior para acompanhar a barra de navegação
            binding.bottomShadow.layoutParams.height = systemBars.bottom + 120.toPx()
            binding.bottomShadow.requestLayout()
            
            insets
        }

        setSupportActionBar(binding.toolbar)
        setupRecyclerView()
        setupTabs()
        setupFabActions()
        setupSwipeRefresh()
        setupBackNavigation()
        loadShortcuts()
        binding.cardStorage.setOnClickListener { refreshList(Environment.getExternalStorageDirectory().absolutePath, getLocalProvider()) }
        binding.btnCopyPath.setOnClickListener { copyCurrentPathToClipboard() }
        binding.btnNewTab.setOnClickListener { addNewTab(getString(R.string.action_new_tab), DASHBOARD_PATH, getLocalProvider()) }
        addNewTab(getString(R.string.home), DASHBOARD_PATH, getLocalProvider())
        isInitializing = false

        // Observar estado das tarefas globais
        observeTaskState()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.root.requestApplyInsets()
        if (tabs.getOrNull(activeTabIndex)?.currentPath == DASHBOARD_PATH) {
            updateStorageDashboard()
        }
    }

    private fun observeTaskState() {
        lifecycleScope.launch {
            var wasActive = false
            FileTaskManager.taskState.collectLatest { state ->
                if (state.isActive) {
                    binding.taskArea.visibility = View.VISIBLE
                    binding.taskTitle.text = state.content.ifEmpty { state.title }
                    wasActive = true
                } else {
                    binding.taskArea.visibility = View.GONE
                    // Se a tarefa acabou de terminar, atualiza a lista
                    if (wasActive) {
                        val activeTab = tabs.getOrNull(activeTabIndex)
                        if (activeTab != null && activeTab.currentPath != DASHBOARD_PATH) {
                            refreshList(activeTab.currentPath, activeTab.currentProvider, false)
                        }
                        // Sempre atualizar os recentes se o dashboard estiver visível ou por garantia
                        loadRecentFiles()
                        wasActive = false
                    }
                }
            }
        }
    }

    private fun copyCurrentPathToClipboard() {
        val activeTab = tabs.getOrNull(activeTabIndex) ?: return
        val currentPath = activeTab.currentPath
        if (currentPath == DASHBOARD_PATH) return
        
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(getString(R.string.action_copy_path), currentPath)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.toast_path_copied), Toast.LENGTH_SHORT).show()
    }

    private fun getLocalProvider(): LocalFileProvider {
        val settings = SettingsManager(this)
        return LocalFileProvider(
            useRoot = settings.accessMode == SettingsManager.MODE_ROOT,
            useShizuku = settings.accessMode == SettingsManager.MODE_SHIZUKU,
            cacheDir = cacheDir.absolutePath
        )
    }

    private fun showActionMenu(file: FileNode) {
        val selected = adapter.getSelectedPaths()
        val targets = if (file.path in selected) selected.toList() else { adapter.setSelection(setOf(file.path)); adapter.notifyDataSetChanged(); listOf(file.path) }
        val dialog = BottomSheetDialog(this, R.style.TransparentBottomSheetDialog)
        val view = layoutInflater.inflate(R.layout.layout_context_menu, null)
        dialog.setContentView(view)
        val rvActions = view.findViewById<RecyclerView>(R.id.rvMenuActions)
        rvActions.layoutManager = GridLayoutManager(this, 4)
        val actions = mutableListOf<MenuAction>()
        
        actions.add(MenuAction("info", getString(R.string.action_details), R.drawable.l_info))
        if (targets.size == 1) {
            actions.add(MenuAction("rename", getString(R.string.action_rename), R.drawable.l_create))
            actions.add(MenuAction("text", getString(R.string.action_open_as_text), R.drawable.fsn_text))
            if (file.type == FileType.APK || file.type == FileType.ZIP || file.type == FileType.RAR || file.type == FileType.SEVEN_ZIP || file.type == FileType.TAR) {
                actions.add(MenuAction("open_archive", getString(R.string.action_view_archive), R.drawable.fsn_archive))
            }
        }
        actions.add(MenuAction("copy", getString(R.string.action_copy), R.drawable.l_copy))
        actions.add(MenuAction("cut", getString(R.string.action_cut), R.drawable.l_cut))
        actions.add(MenuAction("delete", getString(R.string.action_delete), R.drawable.l_delete))
        actions.add(MenuAction("compress", getString(R.string.action_compress), R.drawable.l_archive))
        if (file.isDirectory && targets.size == 1) actions.add(MenuAction("pin", getString(R.string.action_pin), R.drawable.foldern_home))
        if (targets.size == 1 && (file.type == FileType.ZIP || file.type == FileType.APK || file.type == FileType.RAR || file.type == FileType.SEVEN_ZIP || file.type == FileType.TAR)) actions.add(MenuAction("extract", getString(R.string.action_extract), R.drawable.l_upload))
        if (targets.all { !it.contains("::") }) actions.add(MenuAction("share", getString(R.string.action_share), R.drawable.l_share))
        
        rvActions.adapter = MenuActionAdapter(actions) { actionId -> dialog.dismiss(); handleBatchAction(actionId, targets, file) }
        dialog.setContentView(view); dialog.show()
    }

    private fun handleBatchAction(actionId: String, targets: List<String>, focalFile: FileNode) {
        when (actionId) {
            "info" -> showDetailsDialog(focalFile)
            "text" -> { val intent = Intent(this, EditorActivity::class.java); intent.putExtra("file_path", focalFile.path); startActivity(intent) }
            "open_archive" -> {
                val provider = when (focalFile.type) {
                    FileType.ZIP, FileType.APK -> ZipFileProvider(focalFile.path)
                    FileType.SEVEN_ZIP -> SevenZipFileProvider(focalFile.path)
                    FileType.TAR -> TarFileProvider(focalFile.path)
                    else -> ZipFileProvider(focalFile.path)
                }
                refreshList(focalFile.path + "::", provider)
            }
            "copy" -> { clipboardPaths = targets.toMutableList(); setClipboard(false) }
            "cut" -> { clipboardPaths = targets.toMutableList(); setClipboard(true) }
            "delete" -> confirmBatchDelete(targets)
            "rename" -> showRenameDialog(focalFile)
            "share" -> executeShare(targets)
            "extract" -> executeExtraction(focalFile)
            "compress" -> showCompressDialog(targets)
            "pin" -> { if (shortcuts.none { it.path == focalFile.path }) { shortcuts.add(Shortcut(focalFile.name, focalFile.path, R.drawable.l_folder)); shortcutAdapter.notifyItemInserted(shortcuts.size - 1); saveShortcuts() } }
        }
        adapter.setSelection(emptySet())
        updateToolbarTitle()
    }

    private fun openFile(file: FileNode) {
        val extension = file.path.substringAfterLast(".").lowercase()
        
        // Suporte a instalação de APK e XAPK
        if (extension == "apk") {
            com.alisu.filex.util.AppInstaller.installApk(this, File(file.path))
            return
        }
        if (extension == "xapk") {
            com.alisu.filex.util.AppInstaller.installXapk(this, File(file.path))
            return
        }

        when (file.type) {
            FileType.IMAGE, FileType.VECTOR -> {
                val intent = Intent(this, ImageViewerActivity::class.java)
                intent.putExtra("file_path", file.path)
                startActivity(intent)
                return
            }
            FileType.VIDEO -> {
                val intent = Intent(this, VideoPlayerActivity::class.java)
                intent.putExtra("file_path", file.path)
                startActivity(intent)
                return
            }
            FileType.AUDIO -> {
                val intent = Intent(this, AudioPlayerActivity::class.java)
                intent.putExtra("file_path", file.path)
                startActivity(intent)
                return
            }
            else -> {}
        }

        val codeExtensions = listOf(
            "java", "kt", "js", "ts", "py", "xml", "txt", "cpp", "c", "h", "hpp", "cs", "go", "rb", "php",
            "html", "css", "json", "md", "sh", "gradle", "properties", "yml", "yaml", "toml", "conf", "ini", "log"
        )
        if (codeExtensions.contains(extension) || file.type == FileType.CODE || file.type == FileType.TEXT) {
            val intent = Intent(this, EditorActivity::class.java); intent.putExtra("file_path", file.path); startActivity(intent)
            return
        }
        try {
            val realFile = File(file.path)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", realFile)
            val mimeType = when (file.type) {
                FileType.PDF -> "application/pdf"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            }
            val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mimeType); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startActivity(Intent.createChooser(intent, "Abrir com"))
        } catch (e: Exception) { }
    }

    private fun saveCurrentFolderState() {
        if (tabs.isEmpty() || activeTabIndex >= tabs.size) return
        val activeTab = tabs[activeTabIndex]
        activeTab.states[activeTab.currentPath] = FolderState(binding.recyclerView.layoutManager?.onSaveInstanceState(), adapter.getSelectedPaths())
    }

    private fun refreshList(path: String, provider: VfsProvider, addToHistory: Boolean = true) {
        lifecycleScope.launch {
            try {
                saveCurrentFolderState()
                val isDashboard = path == DASHBOARD_PATH
                
                // Animação suave para troca entre Dashboard e Lista
                val transition = com.google.android.material.transition.MaterialSharedAxis(
                    com.google.android.material.transition.MaterialSharedAxis.Y, true)
                androidx.transition.TransitionManager.beginDelayedTransition(binding.root as android.view.ViewGroup, transition)
                
                binding.dashboardContent.visibility = if (isDashboard) View.VISIBLE else View.GONE
                binding.swipeRefresh.visibility = if (isDashboard) View.GONE else View.VISIBLE
                binding.fabActions.visibility = if (isDashboard) View.GONE else View.VISIBLE
                
                if (isDashboard) {
                    updateStorageDashboard()
                } else {
                    binding.folderLoadingProgress.visibility = View.VISIBLE
                    val files = withContext(Dispatchers.IO) { provider.listChildren(path) }
                    adapter.submitList(files)
                    binding.folderLoadingProgress.visibility = View.GONE
                }

                val activeTab = tabs[activeTabIndex]
                activeTab.currentPath = path
                activeTab.currentProvider = provider
                if (addToHistory) activeTab.history.push(path to provider)
                updateToolbarTitle()
                
                val displayTitle = when {
                    isDashboard -> getString(R.string.home)
                    path.contains("::") -> {
                        val internalPath = path.substringAfter("::").removeSuffix("/")
                        if (internalPath.isNotEmpty()) internalPath.substringAfterLast("/")
                        else path.substringBefore("::").substringAfterLast("/")
                    }
                    else -> path.substringAfterLast("/")
                }
                
                binding.tabLayout.getTabAt(activeTabIndex)?.customView?.findViewById<TextView>(R.id.tabTitle)?.text = displayTitle
                val savedState = activeTab.states[path]
                adapter.setSelection(savedState?.selection ?: emptySet())
                binding.recyclerView.post {
                    if (savedState?.scrollState != null) binding.recyclerView.layoutManager?.onRestoreInstanceState(savedState.scrollState)
                    else binding.recyclerView.scrollToPosition(0)
                }
            } catch (e: Exception) { 
                if (!FileTaskManager.taskState.value.isActive) {
                    binding.taskArea.visibility = View.GONE
                }
            }
        }
    }

    private fun loadShortcuts() {
        val prefs = getSharedPreferences("filex_prefs", Context.MODE_PRIVATE)
        val savedPaths = prefs.getStringSet("shortcuts", null)
        val settings = SettingsManager(this)
        shortcuts.clear()
        val root = Environment.getExternalStorageDirectory().absolutePath
        
        // Armazenamento Interno e externos agora usam l_folder para uniformidade
        shortcuts.add(Shortcut("Interno", root, R.drawable.l_folder))

        // Detecção de SD Card e USB
        try {
            File("/storage").listFiles()?.forEach { file ->
                if (file.isDirectory && !file.name.equals("self", true) && 
                    !file.name.equals("emulated", true) && !file.name.contains("knox", true)) {
                    shortcuts.add(Shortcut(file.name, file.absolutePath, R.drawable.l_folder))
                }
            }
        } catch (e: Exception) {}

        if (settings.accessMode == SettingsManager.MODE_ROOT) {
            shortcuts.add(Shortcut("Root", "/", R.drawable.l_folder))
        }
        if (settings.accessMode == SettingsManager.MODE_SHIZUKU) {
            shortcuts.add(Shortcut("Data", "$root/Android/data", R.drawable.l_folder))
            shortcuts.add(Shortcut("Obb", "$root/Android/obb", R.drawable.l_folder))
        }
        if (settings.isTrashEnabled) {
            shortcuts.add(Shortcut("Lixeira", "$root/.filex_trash", R.drawable.ic_trash))
        }

        shortcuts.add(Shortcut("Download", "$root/Download", R.drawable.l_folder))
        shortcuts.add(Shortcut("Fotos", "$root/DCIM", R.drawable.l_folder))
        shortcuts.add(Shortcut("Músicas", "$root/Music", R.drawable.l_folder))
        savedPaths?.forEach { path -> if (shortcuts.none { it.path == path }) shortcuts.add(Shortcut(File(path).name, path, R.drawable.l_folder)) }
        
        shortcutAdapter = ShortcutAdapter(
            shortcuts, 
            onClick = { shortcut -> refreshList(shortcut.path, getLocalProvider()) },
            onLongClick = { shortcut -> confirmUnpinShortcut(shortcut) }
        )
        binding.rvShortcuts.layoutManager = GridLayoutManager(this, 3); binding.rvShortcuts.adapter = shortcutAdapter
    }

    private fun confirmUnpinShortcut(shortcut: Shortcut) {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val systemPaths = listOf(root, "$root/Download", "$root/DCIM", "$root/Music", "/", "$root/Android/data", "$root/Android/obb", "$root/.filex_trash")
        
        if (shortcut.path in systemPaths) {
            Toast.makeText(this, getString(R.string.toast_cannot_remove_system_shortcut), Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.pin_title))
            .setMessage(getString(R.string.pin_message, shortcut.name))
            .setPositiveButton(getString(R.string.action_delete)) { _, _ ->
                val index = shortcuts.indexOf(shortcut)
                if (index != -1) {
                    shortcuts.removeAt(index)
                    shortcutAdapter.notifyItemRemoved(index)
                    saveShortcuts()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun saveShortcuts() {
        val prefs = getSharedPreferences("filex_prefs", Context.MODE_PRIVATE)
        val root = Environment.getExternalStorageDirectory().absolutePath
        val systemPaths = listOf(root, "$root/Download", "$root/DCIM", "$root/Music", "/", "$root/Android/data", "$root/Android/obb", "$root/.filex_trash")
        
        val pathsToSave = shortcuts.filter { it.path !in systemPaths }.map { it.path }.toSet()
        prefs.edit().putStringSet("shortcuts", pathsToSave).apply()
    }

    private fun setupRecyclerView() {
        adapter = FileAdapter(
            onFileClick = { file ->
                when {
                    file.isDirectory -> refreshList(file.path, tabs[activeTabIndex].currentProvider)
                    file.type == FileType.ZIP || file.type == FileType.APK -> refreshList(file.path + "::", ZipFileProvider(file.path))
                    file.type == FileType.TAR -> refreshList(file.path + "::", TarFileProvider(file.path))
                    file.type == FileType.SEVEN_ZIP -> refreshList(file.path + "::", SevenZipFileProvider(file.path))
                    else -> openFile(file)
                }
            },
            onIconClick = { file -> adapter.toggleSelection(file.path); updateToolbarTitle() },
            onLongClick = { file -> showActionMenu(file) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this); binding.recyclerView.adapter = adapter
    }

    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun updateToolbarTitle() {
        if (tabs.isEmpty()) return
        val activeTab = tabs[activeTabIndex]
        val selectedCount = adapter.getSelectedCount()
        
        if (selectedCount > 0) {
            binding.toolbar.title = getString(R.string.selected_count, selectedCount)
        } else {
            val path = activeTab.currentPath
            binding.toolbar.title = if (path == DASHBOARD_PATH) getString(R.string.home) 
                                   else path.removeSuffix("/").substringAfterLast("/")
        }
        
        updatePathBar(activeTab.currentPath)
        
        // CRÍTICO: invalidateOptionsMenu fecha a SearchView. 
        // Só invalidamos se não houver busca ativa ou se houver mudança no estado de seleção.
        val searchItem = binding.toolbar.menu.findItem(R.id.action_search)
        if (searchItem?.isActionViewExpanded != true) {
            invalidateOptionsMenu()
        }
    }

    private fun updatePathBar(path: String) {
        val isDashboard = path == DASHBOARD_PATH
        
        // Controla a visibilidade do card inteiro
        binding.pathBarCard.visibility = if (isDashboard) View.GONE else View.VISIBLE
        
        if (isDashboard) return

        // Limpa e reconstrói o caminho
        binding.pathContainer.removeAllViews()
        binding.pathScroll.visibility = View.VISIBLE
        binding.btnCopyPath.visibility = View.VISIBLE
        
        val parts = mutableListOf<String>()
        val paths = mutableListOf<String>()

        if (path.contains("::")) {
            val root = path.substringBefore("::")
            parts.add(root.substringAfterLast("/"))
            paths.add(root)
            val internal = path.substringAfter("::").split("/").filter { it.isNotEmpty() }
            var currentInternal = root + "::"
            internal.forEach { 
                currentInternal = if (currentInternal.endsWith("::")) currentInternal + it else "$currentInternal/$it"
                parts.add(it)
                paths.add(currentInternal)
            }
        } else {
            val localParts = path.split("/").filter { it.isNotEmpty() }
            var currentPath = ""
            localParts.forEach {
                currentPath += "/$it"
                parts.add(it)
                paths.add(currentPath)
            }
        }

        parts.forEachIndexed { index, part ->
            val tv = TextView(this).apply {
                val displayPart = if (part == "0" && !path.contains("::")) "Interno" else part
                text = displayPart
                setPadding(12, 8, 12, 8)
                textSize = 13f
                // Cor adaptável ao tema (claro/escuro)
                val colorAttr = if (index == parts.size - 1) com.google.android.material.R.attr.colorOnSurface 
                               else com.google.android.material.R.attr.colorOnSurfaceVariant
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(colorAttr, typedValue, true)
                setTextColor(typedValue.data)
                
                val outValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                isClickable = true
                isFocusable = true
            }
            
            val targetPath = paths[index]
            tv.setOnClickListener { 
                val provider = if (targetPath.contains("::")) tabs[activeTabIndex].currentProvider else getLocalProvider()
                refreshList(targetPath, provider) 
            }
            binding.pathContainer.addView(tv)
            
            if (index < parts.size - 1) {
                binding.pathContainer.addView(TextView(this).apply {
                    text = "›"
                    textSize = 14f
                    alpha = 0.5f
                    val typedValue = android.util.TypedValue()
                    theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
                    setTextColor(typedValue.data)
                })
            }
        }
        binding.pathScroll.post { binding.pathScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun executeShare(targets: List<String>) {
        try {
            val intent = if (targets.size == 1) {
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", File(targets[0]))
                Intent(Intent.ACTION_SEND).apply { type = "*/*"; putExtra(Intent.EXTRA_STREAM, uri) }
            } else {
                val uris = ArrayList<Uri>()
                targets.forEach { uris.add(androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", File(it))) }
                Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(intent, "Partilhar"))
        } catch (e: Exception) { }
    }

    private fun executeExtraction(file: FileNode) {
        val destDir = file.path.removeSuffix(".zip").removeSuffix(".apk").removeSuffix(".7z").removeSuffix(".rar").removeSuffix(".tar").removeSuffix(".tar.gz").removeSuffix(".gz") + "_extraido"
        lifecycleScope.launch {
            binding.taskArea.visibility = View.VISIBLE
            startFileService("Extraindo arquivo")
            val success = when (file.type) {
                FileType.SEVEN_ZIP -> ArchiveManager.extract7z(file.path, destDir) { current -> lifecycleScope.launch(Dispatchers.Main) { binding.taskTitle.text = "Extraindo: $current" } }
                FileType.RAR -> ArchiveManager.extractRar(file.path, destDir) { current -> lifecycleScope.launch(Dispatchers.Main) { binding.taskTitle.text = "Extraindo: $current" } }
                FileType.TAR -> ArchiveManager.extractTar(file.path, destDir) { current -> lifecycleScope.launch(Dispatchers.Main) { binding.taskTitle.text = "Extraindo: $current" } }
                else -> ArchiveManager.extractZip(file.path, destDir) { current -> lifecycleScope.launch(Dispatchers.Main) { binding.taskTitle.text = "Extraindo: $current" } }
            }
            binding.taskArea.visibility = View.GONE
            stopFileService()
            if (success) refreshList(tabs[activeTabIndex].currentPath, tabs[activeTabIndex].currentProvider, false)
        }
    }

    private fun showCompressDialog(targets: List<String>) {
        val activeTab = tabs[activeTabIndex]
        val currentPath = activeTab.currentPath
        val folderName = if (currentPath == DASHBOARD_PATH) "compactado" 
                         else if (currentPath.contains("::")) currentPath.substringAfter("::").removeSuffix("/").substringAfterLast("/") 
                         else currentPath.substringAfterLast("/")
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24) }
        val input = EditText(this).apply { setText(folderName); hint = "Nome" }
        val radioGroup = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val rbZip = RadioButton(context).apply { text = "ZIP"; id = View.generateViewId(); isChecked = true }
            val rb7z = RadioButton(context).apply { text = "7Z"; id = View.generateViewId() }
            val rbTar = RadioButton(context).apply { text = "TAR"; id = View.generateViewId() }
            addView(rbZip); addView(rb7z); addView(rbTar)
        }
        layout.addView(input)
        layout.addView(TextView(this).apply { text = "Formato:"; setPadding(0, 24, 0, 8) })
        layout.addView(radioGroup)
        layout.addView(TextView(this).apply { 
            text = "* O formato RAR é proprietário. Use 7Z para melhor compressão."
            textSize = 11f
            alpha = 0.6f
            setPadding(0, 16, 0, 0)
        })

        MaterialAlertDialogBuilder(this).setTitle("Compactar").setView(layout).setPositiveButton("Compactar") { _, _ ->
            val checkedId = radioGroup.checkedRadioButtonId
            val format = radioGroup.findViewById<RadioButton>(checkedId).text.toString().lowercase()
            executeCompression(targets, "${input.text}.$format", format)
        }.show()
    }

    private fun executeCompression(targets: List<String>, outName: String, format: String) {
        val activeTab = tabs[activeTabIndex]
        val outPath = File(activeTab.currentPath, outName).absolutePath
        lifecycleScope.launch {
            binding.taskArea.visibility = View.VISIBLE
            startFileService("Compactando arquivos")
            val success = ArchiveManager.compress(targets, outPath, format) { current -> lifecycleScope.launch(Dispatchers.Main) { binding.taskTitle.text = "Adicionando: $current" } }
            binding.taskArea.visibility = View.GONE
            stopFileService()
            if (success) refreshList(activeTab.currentPath, activeTab.currentProvider, false)
        }
    }

    private fun confirmBatchDelete(targets: List<String>) {
        val settings = SettingsManager(this)
        val title = if (settings.isTrashEnabled) "Mover para Lixeira" else "Excluir permanentemente"
        val message = "Deseja ${if (settings.isTrashEnabled) "mover" else "excluir"} ${targets.size} itens?"
        
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("Confirmar") { _, _ ->
            val action = com.alisu.filex.util.FileTaskAction.Delete(
                targets = targets,
                useTrash = settings.isTrashEnabled,
                trashRetentionDays = settings.trashRetentionDays
            )
            
            startFileService(title)
            FileTaskManager.enqueue(action)
            
            adapter.setSelection(emptySet())
            updateToolbarTitle()
            
            // Atualizar lista após um pequeno delay
            lifecycleScope.launch {
                delay(500)
                refreshList(tabs[activeTabIndex].currentPath, tabs[activeTabIndex].currentProvider, false)
            }
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun cleanOldTrashItems(days: Int) {
        val trashDir = File(Environment.getExternalStorageDirectory(), ".filex_trash")
        if (!trashDir.exists()) return
        
        val now = System.currentTimeMillis()
        val limit = days * 24 * 60 * 60 * 1000L
        
        trashDir.listFiles()?.forEach { file ->
            val timestamp = file.name.substringBefore("_").toLongOrNull()
            if (timestamp != null && (now - timestamp) > limit) {
                file.deleteRecursively()
            }
        }
    }

    private fun showRenameDialog(file: FileNode) {
        val input = EditText(this); input.setText(file.name)
        MaterialAlertDialogBuilder(this).setTitle("Renomear").setView(input).setPositiveButton("OK") { _, _ ->
            val newName = input.text.toString()
            if (newName.isNotEmpty() && newName != file.name) {
                startFileService("Renomeando")
                FileTaskManager.enqueue(com.alisu.filex.util.FileTaskAction.Rename(file.path, newName))
                
                // Atualiza a lista após um delay
                lifecycleScope.launch {
                    delay(500)
                    val activeTab = tabs[activeTabIndex]
                    refreshList(activeTab.currentPath, activeTab.currentProvider, false)
                }
            }
        }.show()
    }

    private fun showDetailsDialog(file: FileNode) {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Calculando detalhes...")
            .setMessage("Por favor, aguarde...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val totalSize = withContext(Dispatchers.IO) {
                if (file.isDirectory) {
                    calculateFolderSize(File(file.path))
                } else {
                    file.size
                }
            }
            
            val itemsCount = if (file.isDirectory) {
                withContext(Dispatchers.IO) { countItems(File(file.path)) }
            } else 0

            progressDialog.dismiss()

            val sizeStr = android.text.format.Formatter.formatFileSize(this@MainActivity, totalSize)
            val details = StringBuilder().apply {
                append("Nome: ${file.name}\n")
                append("Caminho: ${file.path}\n")
                append("Tamanho: $sizeStr ($totalSize bytes)\n")
                if (file.isDirectory) append("Conteúdo: $itemsCount itens\n")
                append("Tipo: ${if (file.isDirectory) "Pasta" else file.type}\n")
                append("Última modificação: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(java.util.Date(file.lastModified))}")
            }.toString()

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Detalhes")
                .setMessage(details)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun calculateFolderSize(directory: File): Long {
        var size: Long = 0
        val files = directory.listFiles() ?: return 0
        for (f in files) {
            size += if (f.isDirectory) calculateFolderSize(f) else f.length()
        }
        return size
    }

    private fun countItems(directory: File): Int {
        return directory.listFiles()?.size ?: 0
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unbindService(serviceConnection)
        } catch (e: Exception) {}
    }

    private fun startFileService(title: String) {
        val intent = Intent(this, com.alisu.filex.util.FileTaskService::class.java).apply {
            putExtra("title", title)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun stopFileService() {
        // Agora o serviço se auto-encerra ou o usuário cancela
        val intent = Intent(this, com.alisu.filex.util.FileTaskService::class.java).apply {
            action = "com.alisu.filex.CANCEL_TASK"
        }
        startService(intent)
    }

    private fun executePaste() {
        val targetPath = tabs[activeTabIndex].currentPath
        if (targetPath == DASHBOARD_PATH) return
        
        val action = com.alisu.filex.util.FileTaskAction.Paste(
            sourcePaths = clipboardPaths.toList(),
            targetPath = targetPath,
            isMove = isMoveOperation
        )
        
        startFileService(if (isMoveOperation) "Movendo arquivos" else "Copiando arquivos")
        FileTaskManager.enqueue(action)
        
        binding.fabPaste.visibility = View.GONE
        binding.fabClearClipboard.visibility = View.GONE
        clipboardPaths.clear()
        
        // Atualizar lista após um pequeno delay para o serviço começar
        lifecycleScope.launch {
            delay(500)
            refreshList(targetPath, tabs[activeTabIndex].currentProvider, false)
        }
    }

    private suspend fun generateUniqueName(provider: VfsProvider, dirPath: String, originalName: String): String {
        val baseName = originalName.substringBeforeLast(".")
        val extension = originalName.substringAfterLast(".", "")
        val suffix = if (extension.isNotEmpty() && extension != originalName) ".$extension" else ""
        var counter = 1
        var newName: String
        do {
            newName = "${baseName}_($counter)$suffix"
            val checkPath = if (dirPath.endsWith("/")) dirPath + newName else "$dirPath/$newName"
            counter++
        } while (provider.exists(checkPath))
        return newName
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            val activeTab = tabs[activeTabIndex]
            refreshList(activeTab.currentPath, activeTab.currentProvider, addToHistory = false)
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 0. Se houver seleção ativa, limpa primeiro
                if (adapter.getSelectedCount() > 0) {
                    adapter.setSelection(emptySet())
                    updateToolbarTitle()
                    return
                }

                val activeTab = tabs.getOrNull(activeTabIndex) ?: return
                
                // 1. Se houver histórico de pastas na aba atual, volta uma pasta
                if (activeTab.history.size > 1) {
                    activeTab.history.pop()
                    val last = activeTab.history.peek()
                    refreshList(last.first, last.second, addToHistory = false)
                    return
                } 
                
                // 2. Se estiver em uma pasta mas não no Dashboard, vai para o Dashboard
                if (activeTab.currentPath != DASHBOARD_PATH) {
                    refreshList(DASHBOARD_PATH, getLocalProvider())
                    return
                }

                // 3. Se estiver no Dashboard da primeira aba, pergunta se quer sair
                if (activeTabIndex == 0) {
                    showExitConfirmation()
                } else {
                    // Se estiver no Dashboard de uma aba secundária, volta para a aba anterior
                    binding.tabLayout.getTabAt(activeTabIndex - 1)?.select()
                }
            }
        })
    }

    private fun showExitConfirmation() {
        MaterialAlertDialogBuilder(this).setTitle("Sair do Filex").setMessage("Deseja sair?").setPositiveButton("Sair") { _, _ -> finish() }.setNegativeButton("Cancelar", null).show()
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val index = tab.position
                if (index < 0 || index >= tabs.size) return
                
                saveCurrentFolderState()
                activeTabIndex = index
                val activeTab = tabs[activeTabIndex]
                refreshList(activeTab.currentPath, activeTab.currentProvider, addToHistory = false)
                
                for (i in 0 until binding.tabLayout.tabCount) {
                    binding.tabLayout.getTabAt(i)?.let { updateTabVisuals(it, it.position == activeTabIndex) }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {
                updateTabVisuals(tab, false)
            }
            override fun onTabReselected(tab: TabLayout.Tab) {
                val index = tab.position
                if (index >= 0 && index < tabs.size) {
                    val activeTab = tabs[index]
                    refreshList(activeTab.currentPath, activeTab.currentProvider, addToHistory = false)
                }
            }
        })
    }

    private fun updateTabVisuals(tab: TabLayout.Tab, isSelected: Boolean) {
        val view = tab.customView ?: return
        val card = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.tabCard)
        val title = view.findViewById<TextView>(R.id.tabTitle)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseTab)
        
        val tabIndex = tab.position
        val isDashboardTab = tabIndex >= 0 && tabIndex < tabs.size && tabs[tabIndex].currentPath == DASHBOARD_PATH
        
        if (isSelected) {
            // Aba selecionada com a cor padrão #F35360
            card.setCardBackgroundColor(Color.parseColor("#F35360"))
            card.strokeWidth = 0
            
            title.setTextColor(Color.WHITE)
            btnClose.setColorFilter(Color.WHITE)
            
            // Garantir que o indicador de seleção do TabLayout seja branco na aba selecionada
            binding.tabLayout.setSelectedTabIndicatorColor(Color.WHITE)
            
            btnClose.visibility = View.VISIBLE
            title.alpha = 1.0f
        } else {
            // Aba inativa translúcida
            card.setCardBackgroundColor(Color.parseColor("#1AFFFFFF"))
            card.strokeWidth = 1.toPx()
            card.strokeColor = Color.parseColor("#33FFFFFF")
            
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
            title.setTextColor(typedValue.data)
            btnClose.setColorFilter(typedValue.data)
            
            // Cor original do indicador para as outras (se necessário)
            // binding.tabLayout.setSelectedTabIndicatorColor(Color.parseColor("#F35360"))
            
            btnClose.visibility = View.VISIBLE
            title.alpha = 0.85f
        }
    }

    private fun addNewTab(title: String, path: String, provider: VfsProvider) {
        val newTab = Tab(title, path, provider)
        tabs.add(newTab)
        val tab = binding.tabLayout.newTab()
        
        val customView = layoutInflater.inflate(R.layout.layout_tab, null)
        customView.findViewById<TextView>(R.id.tabTitle).text = title
        
        // Clique no X fecha a aba
        customView.findViewById<ImageButton>(R.id.btnCloseTab).setOnClickListener { 
            closeTab(tab) 
        }
        
        // Menu ao segurar
        customView.setOnLongClickListener {
            showTabMenu(tab)
            true
        }

        // UX CRÍTICO: Garantir que o clique na view customizada mude a aba
        customView.setOnClickListener {
            tab.select()
        }
        
        tab.customView = customView
        binding.tabLayout.addTab(tab)
        tab.select()
    }

    private fun showTabMenu(tab: TabLayout.Tab) {
        val options = arrayOf(
            getString(R.string.tab_close),
            getString(R.string.tab_close_others),
            getString(R.string.tab_close_all),
            getString(R.string.action_new_tab)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.tab_options))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> closeTab(tab)
                    1 -> closeOtherTabs(tab)
                    2 -> closeAllTabs()
                    3 -> addNewTab(getString(R.string.action_new_tab), DASHBOARD_PATH, getLocalProvider())
                }
            }.show()
    }

    private fun closeOtherTabs(selectedTab: TabLayout.Tab) {
        val selectedIndex = selectedTab.position
        if (selectedIndex == -1) return
        
        val keepingTab = tabs[selectedIndex]
        tabs.clear()
        binding.tabLayout.removeAllTabs()
        addNewTab(keepingTab.title, keepingTab.currentPath, keepingTab.currentProvider)
    }

    private fun closeAllTabs() {
        tabs.clear()
        binding.tabLayout.removeAllTabs()
        addNewTab(getString(R.string.home), DASHBOARD_PATH, getLocalProvider())
    }

    private fun closeTab(tab: TabLayout.Tab) {
        val index = tab.position
        if (index == -1) return
        
        if (tabs.size <= 1) {
            // Se for a última aba, em vez de fechar o app, apenas volta para o Início
            refreshList(DASHBOARD_PATH, getLocalProvider())
            return
        }
        
        tabs.removeAt(index)
        binding.tabLayout.removeTabAt(index)
        binding.tabLayout.getTabAt(if (index >= tabs.size) tabs.size - 1 else index)?.select()
    }

    private fun updateStorageDashboard() {
        try {
            val stat = android.os.StatFs(Environment.getExternalStorageDirectory().path)
            val totalBytes = stat.totalBytes
            val availableBytes = stat.availableBytes
            val usedBytes = totalBytes - availableBytes
            val percent = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0

            binding.storageProgress.progress = percent
            binding.txtStoragePercent.text = "$percent%"
            binding.txtStorageDetails.text = "${android.text.format.Formatter.formatFileSize(this, availableBytes)} livres de ${android.text.format.Formatter.formatFileSize(this, totalBytes)}"

            loadRecentFiles()
        } catch (e: Exception) { }
    }

    private fun loadRecentFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val recentFiles = mutableListOf<FileNode>()
            val searchDir = Environment.getExternalStorageDirectory()
            val now = System.currentTimeMillis()
            val dayMillis = 24 * 60 * 60 * 1000L

            searchDir.walkTopDown()
                .maxDepth(3)
                .filter { it.isFile && !it.name.startsWith(".") && (now - it.lastModified()) < dayMillis }
                .sortedByDescending { it.lastModified() }
                .take(15)
                .forEach { file ->
                    val type = when {
                        file.name.endsWith(".jpg", true) || file.name.endsWith(".png", true) || file.name.endsWith(".webp", true) -> FileType.IMAGE
                        file.name.endsWith(".mp4", true) || file.name.endsWith(".mkv", true) -> FileType.VIDEO
                        file.name.endsWith(".apk", true) -> FileType.APK
                        file.name.endsWith(".pdf", true) -> FileType.PDF
                        file.name.endsWith(".mp3", true) || file.name.endsWith(".wav", true) -> FileType.AUDIO
                        else -> FileType.FILE
                    }
                    recentFiles.add(VfsFileNode(file.name, file.absolutePath, file.length(), file.lastModified(), false, type))
                }

            withContext(Dispatchers.Main) {
                if (!::recentAdapter.isInitialized) {
                    recentAdapter = RecentFileAdapter(recentFiles) { file -> openFile(file) }
                    binding.rvRecentFiles.layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
                    binding.rvRecentFiles.adapter = recentAdapter
                } else {
                    recentAdapter.submitList(recentFiles)
                }

                val hasRecents = recentFiles.isNotEmpty()
                binding.rvRecentFiles.visibility = if (hasRecents) View.VISIBLE else View.GONE
                (binding.rvRecentFiles.parent as? android.view.ViewGroup)?.getChildAt(0)?.visibility = if (hasRecents) View.VISIBLE else View.GONE
            }
        }
    }
    private fun setupFabActions() {
        binding.fabActions.setOnClickListener {
            val activeTab = tabs.getOrNull(activeTabIndex)
            val isDashboard = activeTab?.currentPath == DASHBOARD_PATH

            if (isDashboard) {
                // Na tela inicial, apenas Nova Aba
                addNewTab(getString(R.string.action_new_tab), DASHBOARD_PATH, getLocalProvider())
            } else {
                // Em pastas normais, mantém as opções anteriores
                val options = arrayOf(getString(R.string.create_folder), getString(R.string.create_file), getString(R.string.action_new_tab))
                MaterialAlertDialogBuilder(this).setItems(options) { _, which ->
                    when (which) {
                        0 -> showCreateDialog(getString(R.string.create_folder))
                        1 -> showCreateDialog(getString(R.string.create_file))
                        2 -> addNewTab(getString(R.string.action_new_tab), DASHBOARD_PATH, getLocalProvider())
                    }
                }.show()
            }
        }
        binding.fabPaste.setOnClickListener { executePaste() }
        binding.fabClearClipboard.setOnClickListener {
            clipboardPaths.clear()
            binding.fabPaste.visibility = View.GONE
            binding.fabClearClipboard.visibility = View.GONE
            Toast.makeText(this, getString(R.string.toast_clipboard_cleared), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCreateDialog(type: String) {
        val input = EditText(this); input.hint = getString(R.string.dialog_compress_name_hint)
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.create_new, type)).setView(input).setPositiveButton(getString(R.string.action_ok)) { _, _ ->
            val name = input.text.toString()
            if (name.isNotEmpty()) {
                lifecycleScope.launch {
                    val activeTab = tabs[activeTabIndex]
                    if (activeTab.currentPath != DASHBOARD_PATH) {
                        if (activeTab.currentProvider.createDirectory(activeTab.currentPath, name) || activeTab.currentProvider.createFile(activeTab.currentPath, name)) {
                            refreshList(activeTab.currentPath, activeTab.currentProvider, false)
                        }
                    }
                }
            }
        }.show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? androidx.appcompat.widget.SearchView
        
        searchView?.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (!query.isNullOrBlank()) executeSearch(query)
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrBlank()) cancelSearch()
                return false
            }
        })

        searchItem?.setOnActionExpandListener(object : android.view.MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: android.view.MenuItem): Boolean = true
            override fun onMenuItemActionCollapse(item: android.view.MenuItem): Boolean {
                cancelSearch()
                return true
            }
        })
        
        return true 
    }

    private fun cancelSearch() {
        searchJob?.cancel()
        val activeTab = tabs.getOrNull(activeTabIndex) ?: return
        refreshList(activeTab.currentPath, activeTab.currentProvider, false)
    }

    private fun executeSearch(query: String) {
        val activeTab = tabs.getOrNull(activeTabIndex) ?: return
        val currentPath = activeTab.currentPath
        if (currentPath == DASHBOARD_PATH) return

        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            binding.taskArea.visibility = View.VISIBLE
            binding.taskTitle.text = getString(R.string.search_searching, query)
            
            try {
                val results = withContext(Dispatchers.IO) {
                    activeTab.currentProvider.search(currentPath, query)
                }
                adapter.submitList(results)
                binding.toolbar.title = getString(R.string.search_results, results.size)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_search_error), Toast.LENGTH_SHORT).show()
                }
            } finally {
                binding.taskArea.visibility = View.GONE
            }
        }
    }
    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        val selectedCount = adapter.getSelectedCount()
        val activeTab = tabs.getOrNull(activeTabIndex)
        val isDashboard = activeTab?.currentPath == DASHBOARD_PATH
        val trashPath = File(Environment.getExternalStorageDirectory(), ".filex_trash").absolutePath
        val isTrash = activeTab?.currentPath == trashPath

        menu.findItem(R.id.action_search)?.let {
            it.isVisible = selectedCount == 0 && !isDashboard
        }
        menu.findItem(R.id.action_settings)?.isVisible = selectedCount == 0
        menu.findItem(R.id.action_clear_trash)?.isVisible = selectedCount == 0 && isTrash
        menu.findItem(R.id.action_select_all)?.isVisible = selectedCount > 0
        menu.findItem(R.id.action_clear_selection)?.isVisible = selectedCount > 0
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onResume() {
        super.onResume()
        loadShortcuts()
        if (tabs.isNotEmpty()) {
            activeTabIndex = activeTabIndex.coerceIn(0, tabs.size - 1)
            val activeTab = tabs[activeTabIndex]
            updatePathBar(activeTab.currentPath) // Garante que a barra apareça
            refreshList(activeTab.currentPath, activeTab.currentProvider, false)
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_select_all -> { adapter.setSelection(adapter.getCurrentPaths().toSet()); updateToolbarTitle() }
            R.id.action_clear_selection -> { adapter.setSelection(emptySet()); updateToolbarTitle() }
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_clear_trash -> confirmClearTrash()
        }
        return true
    }

    private fun confirmClearTrash() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.action_clear_trash))
            .setMessage(getString(R.string.dialog_delete_message, getString(R.string.dialog_delete_delete), shortcuts.size)) // Reuse for now
            .setPositiveButton(getString(R.string.action_ok)) { _, _ ->
                startFileService(getString(R.string.task_cleaning_trash))
                FileTaskManager.enqueue(com.alisu.filex.util.FileTaskAction.ClearTrash)
                
                // Atualizar lista após um pequeno delay
                lifecycleScope.launch {
                    delay(500)
                    val activeTab = tabs[activeTabIndex]
                    refreshList(activeTab.currentPath, activeTab.currentProvider, false)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setClipboard(move: Boolean) {
        isMoveOperation = move
        binding.fabPaste.visibility = View.VISIBLE
        binding.fabClearClipboard.visibility = View.VISIBLE
        Toast.makeText(this, if(move) getString(R.string.toast_cut) else getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
    }
}
