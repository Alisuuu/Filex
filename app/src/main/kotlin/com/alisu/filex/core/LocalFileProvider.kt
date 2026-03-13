package com.alisu.filex.core

import com.alisu.filex.util.RootUtil
import com.alisu.filex.util.ShizukuUtil
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.Source
import java.io.File

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalFileNode(
    override val name: String,
    override val path: String,
    override val size: Long,
    override val lastModified: Long,
    override val isDirectory: Boolean,
    override val type: FileType,
    override val mimeType: String? = null
) : FileNode

class LocalFileProvider(
    private val useRoot: Boolean = false, 
    private val useShizuku: Boolean = false,
    private val cacheDir: String? = null
) : FileProvider {

    override suspend fun listChildren(parentPath: String): List<FileNode> = withContext(Dispatchers.IO) {
        val directory = File(parentPath)
        val files = directory.listFiles()
        
        val isRestricted = parentPath.contains("/Android/data") || parentPath.contains("/Android/obb") || parentPath == "/" || parentPath.startsWith("/data")
        
        if (files == null || (files.isEmpty() && isRestricted)) {
            if (useShizuku && ShizukuUtil.isAvailable()) {
                return@withContext listChildrenWithShell(parentPath, isShizuku = true)
            }
            if (useRoot) {
                return@withContext listChildrenWithShell(parentPath, isShizuku = false)
            }
        }
        
        (files ?: emptyArray()).map { file ->
            val type = getFileType(file)
            LocalFileNode(file.name, file.absolutePath, if (file.isDirectory) 0 else file.length(), file.lastModified(), file.isDirectory, type)
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun listChildrenWithShell(parentPath: String, isShizuku: Boolean): List<FileNode> {
        val command = "ls -1 -F \"$parentPath\""
        val output = if (isShizuku) ShizukuUtil.execute(command) else RootUtil.execute(command)
        
        if (output.isEmpty()) return emptyList()

        return output.asSequence() // Usamos Sequence para processar grandes listas sem criar listas intermediárias
            .filter { it.isNotEmpty() && !it.startsWith("total ") }
            .map { line ->
                val isDir = line.endsWith("/") || line.endsWith("@")
                val name = line.removeSuffix("/").removeSuffix("*").removeSuffix("@")
                val path = if (parentPath.endsWith("/")) parentPath + name else "$parentPath/$name"
                
                LocalFileNode(
                    name = name,
                    path = path,
                    size = 0,
                    lastModified = 0,
                    isDirectory = isDir,
                    type = if (isDir) FileType.DIRECTORY else FileType.FILE
                )
            }
            .distinctBy { it.path }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .toList()
    }

    override suspend fun open(path: String): Source {
        val file = File(path)
        if (file.canRead()) return FileSystem.SYSTEM.source(path.toPath())
        
        if (cacheDir != null) {
            val tempFile = File(cacheDir, file.name)
            val command = "cp \"$path\" \"${tempFile.absolutePath}\""
            val success = if (useShizuku && ShizukuUtil.isAvailable()) {
                ShizukuUtil.runCommand(command)
            } else if (useRoot) {
                RootUtil.runCommand(command)
            } else false

            if (success) {
                if (useShizuku && ShizukuUtil.isAvailable()) {
                    ShizukuUtil.runCommand("chmod 666 \"${tempFile.absolutePath}\"")
                } else if (useRoot) {
                    RootUtil.runCommand("chmod 666 \"${tempFile.absolutePath}\"")
                }
                return FileSystem.SYSTEM.source(tempFile.absolutePath.toPath())
            }
        }
        
        throw java.io.IOException("Cannot read file: $path")
    }

    override suspend fun exists(path: String): Boolean {
        val file = File(path)
        if (file.exists()) return true
        val command = "[ -e \"$path\" ]"
        if (useShizuku && ShizukuUtil.isAvailable()) return ShizukuUtil.runCommand(command)
        if (useRoot) return RootUtil.runCommand(command)
        return false
    }

    override suspend fun delete(path: String): Boolean {
        val file = File(path)
        if (file.deleteRecursively()) return true
        val command = "rm -rf \"$path\""
        if (useShizuku && ShizukuUtil.isAvailable()) return ShizukuUtil.runCommand(command)
        if (useRoot) return RootUtil.runCommand(command)
        return false
    }

    override suspend fun copy(source: String, destinationDir: String) {
        val srcFile = File(source)
        val destFile = File(destinationDir, srcFile.name)
        if (srcFile.exists()) {
            if (srcFile.isDirectory) srcFile.copyRecursively(destFile, overwrite = true)
            else srcFile.copyTo(destFile, overwrite = true)
        } else {
            val command = "cp -rf \"$source\" \"$destinationDir/\""
            if (useShizuku && ShizukuUtil.isAvailable()) ShizukuUtil.runCommand(command)
            else if (useRoot) RootUtil.runCommand(command)
        }
    }

    override suspend fun move(source: String, destinationDir: String) {
        val srcFile = File(source)
        val destFile = File(destinationDir, srcFile.name)
        if (srcFile.renameTo(destFile)) return
        
        val command = "mv \"$source\" \"$destinationDir/\""
        if (useShizuku && ShizukuUtil.isAvailable()) {
            if (ShizukuUtil.runCommand(command)) return
        } else if (useRoot) {
            if (RootUtil.runCommand(command)) return
        }
        
        copy(source, destinationDir)
        delete(source)
    }

    override suspend fun rename(path: String, newName: String): Boolean {
        val file = File(path)
        val newFile = File(file.parentFile, newName)
        if (file.renameTo(newFile)) return true
        
        val command = "mv \"$path\" \"${file.parent}/$newName\""
        if (useShizuku && ShizukuUtil.isAvailable()) return ShizukuUtil.runCommand(command)
        if (useRoot) return RootUtil.runCommand(command)
        return false
    }

    override suspend fun createDirectory(parentPath: String, name: String): Boolean {
        val file = File(parentPath, name)
        if (file.mkdirs()) return true
        val command = "mkdir -p \"$parentPath/$name\""
        if (useShizuku && ShizukuUtil.isAvailable()) return ShizukuUtil.runCommand(command)
        if (useRoot) return RootUtil.runCommand(command)
        return false
    }

    override suspend fun createFile(parentPath: String, name: String): Boolean {
        val file = File(parentPath, name)
        if (file.createNewFile()) return true
        val command = "touch \"$parentPath/$name\""
        if (useShizuku && ShizukuUtil.isAvailable()) return ShizukuUtil.runCommand(command)
        if (useRoot) return RootUtil.runCommand(command)
        return false
    }

    override suspend fun search(rootPath: String, query: String): List<FileNode> = withContext(Dispatchers.IO) {
        if (useShizuku && ShizukuUtil.isAvailable()) {
            return@withContext searchWithShell(rootPath, query, isShizuku = true)
        }
        if (useRoot) {
            return@withContext searchWithShell(rootPath, query, isShizuku = false)
        }

        // Modo Padrão: Busca recursiva manual
        val results = mutableListOf<FileNode>()
        val root = File(rootPath)
        root.walkTopDown()
            .maxDepth(10) // Evita loops infinitos ou scans profundos demais no modo padrão
            .filter { it.name.contains(query, ignoreCase = true) }
            .take(500) // Limite de segurança para performance
            .forEach { file ->
                results.add(LocalFileNode(file.name, file.absolutePath, file.length(), file.lastModified(), file.isDirectory, getFileType(file)))
            }
        results
    }

    private fun searchWithShell(rootPath: String, query: String, isShizuku: Boolean): List<FileNode> {
        // O comando 'find' do Linux é otimizado e muito rápido
        val command = "find \"$rootPath\" -maxdepth 10 -iname \"*$query*\""
        val output = if (isShizuku) ShizukuUtil.execute(command) else RootUtil.execute(command)
        
        return output.asSequence()
            .filter { it.isNotEmpty() }
            .take(1000) // Mais resultados permitidos no modo privilegiado
            .map { path ->
                val file = File(path)
                LocalFileNode(
                    name = file.name,
                    path = path,
                    size = 0,
                    lastModified = 0,
                    isDirectory = path.endsWith("/") || file.isDirectory, // find pode não mostrar o / final
                    type = FileType.FILE // No modo find rápido, simplificamos o tipo
                )
            }
            .toList()
    }

    private fun getFileType(file: File): FileType {
        val name = file.name.lowercase()
        return when {
            file.isDirectory -> FileType.DIRECTORY
            name.endsWith(".apk") || name.endsWith(".xapk") || name.endsWith(".apks") || name.endsWith(".apkm") -> FileType.APK
            name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".aar") || name.endsWith(".obb") || name.endsWith(".epub") -> FileType.ZIP
            name.endsWith(".rar") -> FileType.RAR
            name.endsWith(".7z") -> FileType.SEVEN_ZIP
            name.endsWith(".tar") || name.endsWith(".gz") || name.endsWith(".tgz") || name.endsWith(".bz2") || name.endsWith(".xz") -> FileType.TAR
            name.endsWith(".pdf") -> FileType.PDF
            name.endsWith(".svg") -> FileType.VECTOR
            name.let { it.endsWith(".doc") || it.endsWith(".docx") } -> FileType.WORD
            name.let { it.endsWith(".xls") || it.endsWith(".xlsx") } -> FileType.EXCEL
            name.let { it.endsWith(".ppt") || it.endsWith(".pptx") } -> FileType.POWERPOINT
            name.let { it.endsWith(".html") || it.endsWith(".htm") || it.endsWith(".js") || it.endsWith(".css") } -> FileType.WEB
            name.let { it.endsWith(".so") || it.endsWith(".a") || it.endsWith(".lib") } -> FileType.LIB
            name.let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") || it.endsWith(".gif") || it.endsWith(".bmp") } -> FileType.IMAGE
            name.let { it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".avi") || it.endsWith(".mov") } -> FileType.VIDEO
            name.let { it.endsWith(".mp3") || it.endsWith(".wav") || it.endsWith(".ogg") || it.endsWith(".flac") || it.endsWith(".m4a") } -> FileType.AUDIO
            name.let { it.endsWith(".txt") || it.endsWith(".log") || it.endsWith(".md") || it.endsWith(".json") || it.endsWith(".yaml") || it.endsWith(".yml") || it.endsWith(".toml") || it.endsWith(".conf") || it.endsWith(".ini") } -> FileType.TEXT
            name.let { it.endsWith(".java") || it.endsWith(".kt") || it.endsWith(".py") || it.endsWith(".cpp") || it.endsWith(".c") || it.endsWith(".h") || it.endsWith(".hpp") || it.endsWith(".cs") || it.endsWith(".go") || it.endsWith(".rb") || it.endsWith(".php") || it.endsWith(".js") || it.endsWith(".ts") || it.endsWith(".xml") } -> FileType.CODE
            name.let { it.endsWith(".ttf") || it.endsWith(".otf") || it.endsWith(".woff") } -> FileType.FONT
            else -> FileType.FILE
        }
    }
}
