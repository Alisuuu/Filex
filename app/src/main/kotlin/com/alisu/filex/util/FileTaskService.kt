package com.alisu.filex.util

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.alisu.filex.MainActivity
import com.alisu.filex.R
import com.alisu.filex.core.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class FileTaskService : Service() {
    private val CHANNEL_ID = "file_tasks"
    private val NOTIFICATION_ID = 1001
    private val ACTION_CANCEL = "com.alisu.filex.CANCEL_TASK"
    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observationJob: Job? = null
    private var workerJob: Job? = null
    private lateinit var settings: SettingsManager

    inner class LocalBinder : Binder() {
        fun getService(): FileTaskService = this@FileTaskService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. GARANTIA ABSOLUTA: Chamar startForeground imediatamente em cada entrada
        val title = intent?.getStringExtra("title") ?: getString(R.string.app_name)
        val notification = createNotification(title, getString(R.string.task_reading_folder), 0, 100)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Tratar cancelamento APÓS garantir o foreground
        if (intent?.action == ACTION_CANCEL) {
            FileTaskManager.cancelTask()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 3. Iniciar processamento se ainda não estiver rodando
        if (observationJob == null) startObservation()
        if (workerJob == null) startWorker()
        
        return START_STICKY
    }

    private fun startObservation() {
        observationJob = scope.launch {
            FileTaskManager.taskState.collect { state ->
                val manager = getSystemService(NotificationManager::class.java)
                if (state.isActive) {
                    val notification = createNotification(state.title, state.content, state.progress, state.total)
                    manager.notify(NOTIFICATION_ID, notification)
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    manager.cancel(NOTIFICATION_ID)
                    
                    delay(2000)
                    if (!FileTaskManager.taskState.value.isActive) {
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun startWorker() {
        workerJob = scope.launch(Dispatchers.IO) {
            FileTaskManager.actionFlow.collect { action ->
                try {
                    when (action) {
                        is FileTaskAction.Delete -> processDelete(action)
                        is FileTaskAction.Paste -> processPaste(action)
                        is FileTaskAction.Rename -> processRename(action)
                        is FileTaskAction.ClearTrash -> processClearTrash()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                FileTaskManager.taskFinished()
            }
        }
    }

    private suspend fun processClearTrash() {
        try {
            val trashDir = File(android.os.Environment.getExternalStorageDirectory(), ".filex_trash")
            if (!trashDir.exists()) return
            
            val files = trashDir.listFiles() ?: return
            val total = files.size
            files.forEachIndexed { index, file ->
                if (FileTaskManager.isCanceled()) return@processClearTrash
                FileTaskManager.updateProgress("${getString(R.string.task_cleaning_trash)}: ${file.name}", index + 1, total)
                
                if (file.exists()) {
                    if (file.isDirectory) {
                        file.deleteRecursively()
                    } else {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private suspend fun processRename(action: FileTaskAction.Rename) {
        val sourceFile = File(action.path)
        FileTaskManager.updateProgress("${getString(R.string.task_renaming)}: ${sourceFile.name}", 0, 1)
        
        try {
            if (action.path.contains("::")) {
                val zipPath = action.path.substringBefore("::")
                val entryName = action.path.substringAfter("::")
                
                FileTaskManager.updateProgress(getString(R.string.action_compress) + "...", 0, 1)
                val newEntryName = if (entryName.removeSuffix("/").contains("/")) {
                    val parent = entryName.removeSuffix("/").substringBeforeLast("/")
                    if (entryName.endsWith("/")) "$parent/${action.newName}/" else "$parent/${action.newName}"
                } else {
                    if (entryName.endsWith("/")) "${action.newName}/" else action.newName
                }
                
                ArchiveManager.updateZip(zipPath, toRename = listOf(entryName to newEntryName))
            } else {
                val dest = File(sourceFile.parentFile, action.newName)
                if (!sourceFile.renameTo(dest)) {
                    runUniversalCommand("mv \"${action.path}\" \"${dest.absolutePath}\"")
                }
            }
        } catch (e: Exception) {}
    }

    private suspend fun processDelete(action: FileTaskAction.Delete) {
        val targets = action.targets
        val total = targets.size
        
        val zipGroups = targets.filter { it.contains("::") }.groupBy { it.substringBefore("::") }
        val localTargets = targets.filter { !it.contains("::") }

        zipGroups.forEach { (zipPath, entries) ->
            if (FileTaskManager.isCanceled()) return@processDelete
            FileTaskManager.updateProgress(getString(R.string.action_compress) + "...", 0, total)
            ArchiveManager.deleteEntriesFromZip(zipPath, entries.map { it.substringAfter("::") })
        }

        localTargets.forEachIndexed { index, path ->
            if (FileTaskManager.isCanceled()) return@processDelete
            val current = index + 1
            val displayName = if (path.contains("::")) path.substringAfter("::").removeSuffix("/").substringAfterLast("/") else path.substringAfterLast("/")
            FileTaskManager.updateProgress("${getString(R.string.task_deleting)}: $displayName", current, total)
            try {
                val sourceFile = File(path)
                if (action.useTrash) {
                    val trashDir = File(android.os.Environment.getExternalStorageDirectory(), ".filex_trash").apply { if (!exists()) mkdirs() }
                    val trashName = "${System.currentTimeMillis()}_${sourceFile.name}"
                    val destFile = File(trashDir, trashName)
                    
                    if (!sourceFile.renameTo(destFile)) {
                        // Tenta via shell (muito mais rápido e poderoso)
                        if (!runUniversalCommand("mv \"${sourceFile.absolutePath}\" \"${destFile.absolutePath}\"")) {
                            // Se falhar (ex: cross-volume), copia e depois deleta
                            copyRecursivelyWithProgress(sourceFile, destFile)
                            if (!sourceFile.deleteRecursively()) {
                                runUniversalCommand("rm -rf \"${sourceFile.absolutePath}\"")
                            }
                        }
                    }
                } else {
                    if (!sourceFile.deleteRecursively()) {
                        runUniversalCommand("rm -rf \"${sourceFile.absolutePath}\"")
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private suspend fun processPaste(action: FileTaskAction.Paste) {
        val total = action.sourcePaths.size
        val targetPath = action.targetPath
        
        val isTargetZip = targetPath.contains("::") || targetPath.endsWith(".zip") || targetPath.endsWith(".jar") || targetPath.endsWith(".aar")

        if (isTargetZip) {
            val zipPath = if (targetPath.contains("::")) targetPath.substringBefore("::") else targetPath
            val internalBase = if (targetPath.contains("::")) targetPath.substringAfter("::") else ""
            val normalizedBase = if (internalBase.isEmpty() || internalBase.endsWith("/")) internalBase else "$internalBase/"
            val filesToAdd = mutableListOf<Pair<File, String>>()
            
            action.sourcePaths.forEachIndexed { index, sourcePath ->
                if (FileTaskManager.isCanceled()) return@processPaste
                val sourceFile = File(sourcePath)
                val isDir = sourcePath.endsWith("/") || (sourcePath.contains("::") && sourcePath.endsWith("/")) || (!sourcePath.contains("::") && sourceFile.isDirectory)
                val sourceName = if (sourcePath.contains("::")) {
                    sourcePath.substringAfter("::").removeSuffix("/").substringAfterLast("/")
                } else {
                    sourcePath.removeSuffix("/").substringAfterLast("/")
                }
                val finalName = if (isDir) "$sourceName/" else sourceName
                FileTaskManager.updateProgress("${getString(R.string.task_moving)}: $sourceName", index + 1, total)
                
                if (sourcePath.contains("::")) {
                    val tempFile = File(cacheDir, "paste_tmp_${System.currentTimeMillis()}_$index")
                    ArchiveManager.extractEntry(sourcePath.substringBefore("::"), sourcePath.substringAfter("::"), tempFile)
                    filesToAdd.add(tempFile to (normalizedBase + finalName))
                } else {
                    filesToAdd.add(sourceFile to (normalizedBase + finalName))
                }
            }
            
            FileTaskManager.updateProgress(getString(R.string.action_compress) + "...", total, total)
            ArchiveManager.addFilesToZip(zipPath, filesToAdd)
            filesToAdd.forEach { if (it.first.absolutePath.startsWith(cacheDir.absolutePath)) it.first.delete() }
            
            if (action.isMove) {
                action.sourcePaths.forEach { path ->
                    if (path.contains("::")) ArchiveManager.deleteFromZip(path.substringBefore("::"), path.substringAfter("::"))
                    else File(path).deleteRecursively()
                }
            }
        } else {
            action.sourcePaths.forEachIndexed { index, sourcePath ->
                if (FileTaskManager.isCanceled()) return@processPaste
                val sourceName = if (sourcePath.contains("::")) {
                    sourcePath.substringAfter("::").removeSuffix("/").substringAfterLast("/")
                } else {
                    sourcePath.removeSuffix("/").substringAfterLast("/")
                }
                val destFile = File(targetPath, sourceName)
                val taskVerb = if (action.isMove) getString(R.string.task_moving) else getString(R.string.task_copying)
                FileTaskManager.updateProgress("$taskVerb: $sourceName", index + 1, total)

                try {
                    if (sourcePath.contains("::")) {
                        ArchiveManager.extractEntry(sourcePath.substringBefore("::"), sourcePath.substringAfter("::"), destFile)
                        if (action.isMove) ArchiveManager.deleteFromZip(sourcePath.substringBefore("::"), sourcePath.substringAfter("::"))
                    } else {
                        val srcFile = File(sourcePath)
                        if (action.isMove && destFile.absolutePath.startsWith(srcFile.absolutePath + File.separator)) {
                            return@forEachIndexed
                        }

                        if (action.isMove && srcFile.renameTo(destFile)) { /* Sucesso */ }
                        else if (action.isMove && runUniversalCommand("mv \"${srcFile.absolutePath}\" \"${destFile.absolutePath}\"")) { /* Sucesso Shell */ }
                        else {
                            copyRecursivelyWithProgress(srcFile, destFile)
                            if (action.isMove) {
                                FileTaskManager.updateProgress("${getString(R.string.action_delete)}: $sourceName", index + 1, total)
                                if (!srcFile.deleteRecursively()) {
                                    runUniversalCommand("rm -rf \"${srcFile.absolutePath}\"")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private suspend fun copyRecursivelyWithProgress(source: File, dest: File) {
        if (FileTaskManager.isCanceled()) return
        if (source.isDirectory) {
            if (!dest.exists()) dest.mkdirs()
            val files = source.listFiles()
            if (files != null) {
                files.forEach { copyRecursivelyWithProgress(it, File(dest, it.name)) }
            } else {
                val output = executeUniversalList(source.absolutePath)
                if (output.isEmpty()) {
                    runUniversalCommand("cp -rf \"${source.absolutePath}/.\" \"${dest.absolutePath}/\"")
                } else {
                    output.forEach { name ->
                        val childName = name.removeSuffix("/").removeSuffix("*").removeSuffix("@")
                        copyRecursivelyWithProgress(File(source, childName), File(dest, childName))
                    }
                }
            }
        } else {
            FileTaskManager.updateProgress("${getString(R.string.task_copying)}: ${source.name}", 0, 0)
            try {
                source.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            } catch (e: Exception) {
                runUniversalCommand("cp \"${source.absolutePath}\" \"${dest.absolutePath}\"")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        observationJob?.cancel()
        workerJob?.cancel()
        scope.cancel()
    }

    private fun runUniversalCommand(command: String): Boolean {
        return when (settings.accessMode) {
            SettingsManager.MODE_ROOT -> RootUtil.runCommand(command)
            SettingsManager.MODE_SHIZUKU -> ShizukuUtil.runCommand(command)
            else -> false
        }
    }

    private fun executeUniversalList(path: String): List<String> {
        return when (settings.accessMode) {
            SettingsManager.MODE_ROOT -> RootUtil.execute("ls -1 \"$path\"")
            SettingsManager.MODE_SHIZUKU -> ShizukuUtil.execute("ls -1 \"$path\"")
            else -> emptyList()
        }
    }

    private fun createNotification(title: String, content: String, progress: Int, total: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val cancelIntent = Intent(this, FileTaskService::class.java).apply { action = ACTION_CANCEL }
        val cancelPI = PendingIntent.getService(this, 0, cancelIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(android.graphics.Color.parseColor("#F35360"))
            .setColorized(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(R.drawable.l_close, getString(R.string.action_cancel), cancelPI)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(total, progress, total <= 0)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
                manager.createNotificationChannel(channel)
            }
        }
    }
}