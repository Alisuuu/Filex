package com.alisu.filex.core

import okio.Source
import okio.source
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File

class ZipFileProvider(private val zipPath: String) : FileProvider {

    override suspend fun listChildren(parentPath: String): List<FileNode> {
        val zipFile = ZipFile(zipPath)
        val entries = zipFile.entries
        val result = mutableListOf<FileNode>()
        val internalPath = if (parentPath.contains("::")) parentPath.substringAfter("::") else ""
        val normalizedInternalPath = if (internalPath.isEmpty() || internalPath.endsWith("/")) internalPath else "$internalPath/"
        val foldersSeen = mutableSetOf<String>()

        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val entryName = entry.name
            if (entryName.startsWith(normalizedInternalPath) && entryName != normalizedInternalPath) {
                val relativeName = entryName.removePrefix(normalizedInternalPath)
                val parts = relativeName.split("/")
                if (parts.size > 1 || entry.isDirectory) {
                    val folderName = parts[0]
                    if (folderName !in foldersSeen) {
                        foldersSeen.add(folderName)
                        result.add(VfsFileNode(folderName, "$zipPath::$normalizedInternalPath$folderName/", 0, entry.time, true, FileType.DIRECTORY))
                    }
                } else {
                    result.add(VfsFileNode(parts[0], "$zipPath::$normalizedInternalPath${parts[0]}", entry.size, entry.time, false, FileType.FILE))
                }
            }
        }
        zipFile.close()
        return result.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override suspend fun open(path: String): Source {
        val zipFile = ZipFile(zipPath)
        val entry = zipFile.getEntry(path.substringAfter("::"))
        return zipFile.getInputStream(entry).source()
    }

    override suspend fun exists(path: String): Boolean {
        val zipFile = ZipFile(zipPath)
        val entry = zipFile.getEntry(path.substringAfter("::"))
        zipFile.close()
        return entry != null
    }

    override suspend fun delete(path: String): Boolean {
        val entryName = path.substringAfter("::")
        return ArchiveManager.deleteFromZip(zipPath, entryName)
    }

    override suspend fun copy(source: String, destinationDir: String) {
        val entryName = source.substringAfter("::")
        val destFile = File(destinationDir, entryName.removeSuffix("/").substringAfterLast("/"))
        ArchiveManager.extractEntry(zipPath, entryName, destFile)
    }

    override suspend fun move(source: String, destinationDir: String) {
        copy(source, destinationDir)
        delete(source)
    }

    override suspend fun rename(path: String, newName: String): Boolean {
        // Renomear em ZIP é complexo, extraímos e adicionamos com novo nome
        val entryName = path.substringAfter("::")
        val tempFile = File.createTempFile("zip_rename", null)
        if (ArchiveManager.extractEntry(zipPath, entryName, tempFile)) {
            val newEntryName = if (entryName.contains("/")) entryName.substringBeforeLast("/") + "/" + newName else newName
            if (ArchiveManager.addFileToZip(zipPath, tempFile, newEntryName)) {
                ArchiveManager.deleteFromZip(zipPath, entryName)
                tempFile.delete()
                return true
            }
        }
        tempFile.delete()
        return false
    }

    override suspend fun createDirectory(parentPath: String, name: String): Boolean {
        // Em ZIP diretórios são entradas que terminam em /
        val entryName = (if (parentPath.contains("::")) parentPath.substringAfter("::") else "") + name + "/"
        val tempFile = File.createTempFile("zip_dir", null)
        val success = ArchiveManager.addFileToZip(zipPath, tempFile, entryName)
        tempFile.delete()
        return success
    }

    override suspend fun createFile(parentPath: String, name: String): Boolean {
        val entryName = (if (parentPath.contains("::")) parentPath.substringAfter("::") else "") + name
        val tempFile = File.createTempFile("zip_file", null)
        val success = ArchiveManager.addFileToZip(zipPath, tempFile, entryName)
        tempFile.delete()
        return success
    }

    override suspend fun search(rootPath: String, query: String): List<FileNode> {
        val zipFile = ZipFile(zipPath)
        val entries = zipFile.entries
        val result = mutableListOf<FileNode>()
        
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.name.contains(query, ignoreCase = true)) {
                result.add(VfsFileNode(
                    entry.name.removeSuffix("/").substringAfterLast("/"),
                    "$zipPath::${entry.name}",
                    entry.size,
                    entry.time,
                    entry.isDirectory,
                    if (entry.isDirectory) FileType.DIRECTORY else FileType.FILE
                ))
            }
        }
        zipFile.close()
        return result
    }
}
