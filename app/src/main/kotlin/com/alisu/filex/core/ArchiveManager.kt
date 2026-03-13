package com.alisu.filex.core

import com.alisu.filex.util.RootUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream

object ArchiveManager {

    suspend fun extractZip(zipPath: String, destDir: String, onProgress: ((String) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val destFile = File(destDir).apply { if (!exists()) mkdirs() }
            ZipFile(zipPath).use { zipFile ->
                val entries = zipFile.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val targetFile = File(destFile, entry.name)
                    onProgress?.invoke(entry.name)
                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        zipFile.getInputStream(entry).use { input -> FileOutputStream(targetFile).use { output -> input.copyTo(output) } }
                    }
                }
            }
            true
        } catch (e: Exception) { false }
    }

    suspend fun extract7z(path: String, destDir: String, onProgress: ((String) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val destFile = File(destDir).apply { if (!exists()) mkdirs() }
            SevenZFile(File(path)).use { sevenZFile ->
                var entry = sevenZFile.nextEntry
                while (entry != null) {
                    val targetFile = File(destFile, entry.name)
                    onProgress?.invoke(entry.name)
                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                    entry = sevenZFile.nextEntry
                }
            }
            true
        } catch (e: Exception) { false }
    }

    suspend fun extractTar(path: String, destDir: String, onProgress: ((String) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val destFile = File(destDir).apply { if (!exists()) mkdirs() }
            val fis = FileInputStream(path)
            val inputStream = if (path.endsWith(".gz") || path.endsWith(".tgz")) {
                org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(fis)
            } else fis
            TarArchiveInputStream(inputStream).use { tis ->
                var entry = tis.nextTarEntry
                while (entry != null) {
                    val targetFile = File(destFile, entry.name)
                    onProgress?.invoke(entry.name)
                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { output -> tis.copyTo(output) }
                    }
                    entry = tis.nextTarEntry
                }
            }
            true
        } catch (e: Exception) { false }
    }

    suspend fun extractRar(path: String, destDir: String, onProgress: ((String) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val destFile = File(destDir).apply { if (!exists()) mkdirs() }
            com.github.junrar.Archive(File(path)).use { archive ->
                var header = archive.nextFileHeader()
                while (header != null) {
                    val entryName = if (header.isUnicode) header.fileNameW else header.fileNameString
                    val targetFile = File(destFile, entryName)
                    onProgress?.invoke(entryName)
                    if (header.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { output -> archive.extractFile(header, output) }
                    }
                    header = archive.nextFileHeader()
                }
            }
            true
        } catch (e: Exception) { false }
    }

    suspend fun extractEntry(archivePath: String, entryName: String, destFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val ext = archivePath.lowercase()
            val normalizedEntryName = if (entryName.endsWith("/")) entryName else "$entryName/"
            
            when {
                ext.endsWith(".zip") || ext.endsWith(".apk") || ext.endsWith(".jar") || ext.endsWith(".aar") || ext.endsWith(".obb") || ext.endsWith(".xapk") || ext.endsWith(".apks") || ext.endsWith(".epub") -> {
                    ZipFile(archivePath).use { zipFile ->
                        var mainEntry = zipFile.getEntry(entryName)
                        if (mainEntry == null && entryName.endsWith("/")) mainEntry = zipFile.getEntry(entryName.removeSuffix("/"))
                        if (mainEntry == null && !entryName.endsWith("/")) mainEntry = zipFile.getEntry("$entryName/")
                        
                        val isDir = mainEntry?.isDirectory == true || entryName.endsWith("/") || zipFile.entries.asSequence().any { it.name.startsWith(normalizedEntryName) }
                        
                        if (isDir) {
                            if (!destFile.exists()) destFile.mkdirs()
                            val entries = zipFile.entries
                            while (entries.hasMoreElements()) {
                                val e = entries.nextElement()
                                if (e.name.startsWith(normalizedEntryName) && e.name != normalizedEntryName) {
                                    val relativePath = e.name.removePrefix(normalizedEntryName)
                                    val targetFile = File(destFile, relativePath)
                                    if (e.isDirectory) {
                                        targetFile.mkdirs()
                                    } else {
                                        targetFile.parentFile?.mkdirs()
                                        zipFile.getInputStream(e).use { input -> FileOutputStream(targetFile).use { output -> input.copyTo(output) } }
                                    }
                                }
                            }
                            return@withContext true
                        } else if (mainEntry != null) {
                            destFile.parentFile?.mkdirs()
                            zipFile.getInputStream(mainEntry).use { input -> FileOutputStream(destFile).use { output -> input.copyTo(output) } }
                            return@withContext true
                        }
                    }
                    false
                }
                ext.endsWith(".7z") -> {
                    var hasAny = false
                    SevenZFile(File(archivePath)).use { sevenZFile ->
                        var e = sevenZFile.nextEntry
                        while (e != null) {
                            val name = e.name
                            val normalizedName = if (name.endsWith("/")) name else "$name/"
                            if (name == entryName || normalizedName == normalizedEntryName || name.startsWith(normalizedEntryName)) {
                                hasAny = true
                                val relativePath = when {
                                    name == entryName || normalizedName == normalizedEntryName -> ""
                                    else -> name.removePrefix(normalizedEntryName)
                                }
                                val targetFile = if (relativePath.isEmpty()) destFile else File(destFile, relativePath)
                                
                                if (e.isDirectory) {
                                    targetFile.mkdirs()
                                } else {
                                    targetFile.parentFile?.mkdirs()
                                    FileOutputStream(targetFile).use { output ->
                                        val buffer = ByteArray(8192)
                                        var bytesRead: Int
                                        while (sevenZFile.read(buffer).also { bytesRead = it } != -1) { output.write(buffer, 0, bytesRead) }
                                    }
                                }
                            } else {
                                val buffer = ByteArray(8192)
                                while (sevenZFile.read(buffer) != -1) { /* skip */ }
                            }
                            e = sevenZFile.nextEntry
                        }
                    }
                    hasAny
                }
                ext.endsWith(".tar") || ext.endsWith(".tar.gz") || ext.endsWith(".tgz") || ext.endsWith(".gz") -> {
                    val fis = FileInputStream(archivePath)
                    val inputStream = if (ext.endsWith(".gz") || ext.endsWith(".tgz")) {
                        org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(fis)
                    } else fis
                    var hasAny = false
                    TarArchiveInputStream(inputStream).use { tis ->
                        var e = tis.nextTarEntry
                        while (e != null) {
                            val name = e.name
                            val normalizedName = if (name.endsWith("/")) name else "$name/"
                            if (name == entryName || normalizedName == normalizedEntryName || name.startsWith(normalizedEntryName)) {
                                hasAny = true
                                val relativePath = when {
                                    name == entryName || normalizedName == normalizedEntryName -> ""
                                    else -> name.removePrefix(normalizedEntryName)
                                }
                                val targetFile = if (relativePath.isEmpty()) destFile else File(destFile, relativePath)
                                
                                if (e.isDirectory) {
                                    targetFile.mkdirs()
                                } else {
                                    targetFile.parentFile?.mkdirs()
                                    FileOutputStream(targetFile).use { output -> tis.copyTo(output) }
                                }
                            }
                            e = tis.nextTarEntry
                        }
                    }
                    hasAny
                }
                ext.endsWith(".rar") -> {
                    var hasAny = false
                    com.github.junrar.Archive(File(archivePath)).use { archive ->
                        var header = archive.nextFileHeader()
                        while (header != null) {
                            val name = if (header.isUnicode) header.fileNameW else header.fileNameString
                            val normalizedName = if (name.endsWith("/")) name else "$name/"
                            if (name == entryName || normalizedName == normalizedEntryName || name.startsWith(normalizedEntryName)) {
                                hasAny = true
                                val relativePath = when {
                                    name == entryName || normalizedName == normalizedEntryName -> ""
                                    else -> name.removePrefix(normalizedEntryName)
                                }
                                val targetFile = if (relativePath.isEmpty()) destFile else File(destFile, relativePath)
                                
                                if (header.isDirectory) {
                                    targetFile.mkdirs()
                                } else {
                                    targetFile.parentFile?.mkdirs()
                                    FileOutputStream(targetFile).use { output -> archive.extractFile(header, output) }
                                }
                            }
                            header = archive.nextFileHeader()
                        }
                    }
                    hasAny
                }
                else -> false
            }
        } catch (e: Exception) { false }
    }

    suspend fun compress(sourcePaths: List<String>, outPath: String, format: String, onProgress: ((String) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val outFile = File(outPath).apply { parentFile?.mkdirs() }
            when (format.lowercase()) {
                "zip" -> {
                    ZipArchiveOutputStream(FileOutputStream(outFile)).use { zos ->
                        sourcePaths.forEach { path ->
                            val file = File(path)
                            addEntryRecursive(zos, file, file.name, onProgress)
                        }
                    }
                }
                "tar" -> {
                    TarArchiveOutputStream(FileOutputStream(outFile)).use { tos ->
                        sourcePaths.forEach { path ->
                            val file = File(path)
                            addTarEntryRecursive(tos, file, file.name, onProgress)
                        }
                    }
                }
                "7z" -> {
                    SevenZOutputFile(outFile).use { szos ->
                        sourcePaths.forEach { path ->
                            val file = File(path)
                            addSevenZEntryRecursive(szos, file, file.name, onProgress)
                        }
                    }
                }
                else -> return@withContext false
            }
            true
        } catch (e: Exception) { false }
    }

    private fun addEntryRecursive(zos: ZipArchiveOutputStream, file: File, path: String, onProgress: ((String) -> Unit)?) {
        val entryPath = if (file.isDirectory && !path.endsWith("/")) "$path/" else path
        onProgress?.invoke(file.name)
        
        val entry = if (entryPath.endsWith("/")) {
            ZipArchiveEntry(entryPath).apply {
                time = file.lastModified()
                method = ZipArchiveOutputStream.STORED
                size = 0; compressedSize = 0; crc = 0
            }
        } else {
            ZipArchiveEntry(file, entryPath)
        }
        
        zos.putArchiveEntry(entry)
        if (file.isFile) file.inputStream().use { it.copyTo(zos) }
        zos.closeArchiveEntry()
        
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                val childPath = if (entryPath.endsWith("/")) "$entryPath${child.name}" else "$entryPath/${child.name}"
                addEntryRecursive(zos, child, childPath, onProgress)
            }
        }
    }

    private fun addTarEntryRecursive(tos: TarArchiveOutputStream, file: File, path: String, onProgress: ((String) -> Unit)?) {
        val entry = TarArchiveEntry(file, path)
        tos.putArchiveEntry(entry)
        onProgress?.invoke(file.name)
        if (file.isFile) file.inputStream().use { it.copyTo(tos) }
        tos.closeArchiveEntry()
        if (file.isDirectory) file.listFiles()?.forEach { addTarEntryRecursive(tos, it, "$path/${it.name}", onProgress) }
    }

    private fun addSevenZEntryRecursive(szos: SevenZOutputFile, file: File, path: String, onProgress: ((String) -> Unit)?) {
        val entry = szos.createArchiveEntry(file, path)
        szos.putArchiveEntry(entry)
        onProgress?.invoke(file.name)
        if (file.isFile) {
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    szos.write(buffer, 0, len)
                }
            }
        }
        szos.closeArchiveEntry()
        if (file.isDirectory) file.listFiles()?.forEach { addSevenZEntryRecursive(szos, it, "$path/${it.name}", onProgress) }
    }

    suspend fun updateZip(
        zipPath: String,
        toAdd: List<Pair<File, String>> = emptyList(),
        toDelete: List<String> = emptyList(),
        toRename: List<Pair<String, String>> = emptyList(),
        onProgress: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val zipFile = File(zipPath)
        if (!zipFile.exists() && toAdd.isEmpty()) return@withContext false
        
        val tempZip = File(zipPath + ".tmp")
        try {
            val addedPaths = toAdd.map { it.second.removeSuffix("/") }.toSet()
            val deletedPaths = toDelete.map { it.removeSuffix("/") }.toSet()

            ZipArchiveOutputStream(FileOutputStream(tempZip)).use { zos ->
                if (zipFile.exists()) {
                    ZipFile(zipFile).use { oldZip ->
                        val entries = oldZip.entries
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val name = entry.name.removeSuffix("/")
                            
                            if (deletedPaths.any { name == it || name.startsWith("$it/") }) continue
                            if (addedPaths.any { name == it || name.startsWith("$it/") }) continue
                            
                            val renamePair = toRename.find { name == it.first.removeSuffix("/") || name.startsWith("${it.first.removeSuffix("/")}/") }
                            if (renamePair != null) {
                                val oldBase = renamePair.first.removeSuffix("/")
                                val newBase = renamePair.second.removeSuffix("/")
                                val relativeName = entry.name.substring(oldBase.length)
                                val newFullName = if (relativeName.startsWith("/")) newBase + relativeName else if (relativeName.isEmpty()) (if (entry.isDirectory) "$newBase/" else newBase) else "$newBase/$relativeName"
                                
                                val newEntry = ZipArchiveEntry(newFullName).apply {
                                    time = entry.time
                                    method = entry.method
                                    if (entry.size != -1L) size = entry.size
                                }
                                
                                if (entry.isDirectory) {
                                    zos.putArchiveEntry(newEntry)
                                    zos.closeArchiveEntry()
                                } else {
                                    zos.addRawArchiveEntry(newEntry, oldZip.getRawInputStream(entry))
                                }
                                continue
                            }

                            if (entry.isDirectory) {
                                zos.putArchiveEntry(entry)
                                zos.closeArchiveEntry()
                            } else {
                                zos.addRawArchiveEntry(entry, oldZip.getRawInputStream(entry))
                            }
                        }
                    }
                }
                
                toAdd.forEach { (file, internalPath) ->
                    addEntryRecursive(zos, file, internalPath, onProgress)
                }
            }

            if (tempZip.exists()) {
                if (zipFile.exists() && !zipFile.delete()) {
                    RootUtil.runCommand("rm \"$zipPath\"")
                }
                if (!tempZip.renameTo(zipFile)) {
                    RootUtil.runCommand("mv \"${tempZip.absolutePath}\" \"$zipPath\"")
                }
                true
            } else false
        } catch (e: Exception) {
            if (tempZip.exists()) tempZip.delete()
            false
        }
    }

    suspend fun addFileToZip(zipPath: String, fileToAdd: File, internalPath: String): Boolean {
        return updateZip(zipPath, toAdd = listOf(fileToAdd to internalPath))
    }

    suspend fun addFilesToZip(zipPath: String, filesToAdd: List<Pair<File, String>>): Boolean {
        return updateZip(zipPath, toAdd = filesToAdd)
    }

    suspend fun deleteFromZip(zipPath: String, entryName: String): Boolean {
        return updateZip(zipPath, toDelete = listOf(entryName))
    }

    suspend fun deleteEntriesFromZip(zipPath: String, entryNames: List<String>): Boolean {
        return updateZip(zipPath, toDelete = entryNames)
    }
    
    suspend fun renameEntryInZip(zipPath: String, oldName: String, newName: String): Boolean {
        return updateZip(zipPath, toRename = listOf(oldName to newName))
    }
}