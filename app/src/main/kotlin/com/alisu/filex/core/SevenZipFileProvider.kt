package com.alisu.filex.core

import okio.Source
import okio.source
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.ByteArrayInputStream

class SevenZipFileProvider(private val archivePath: String) : FileProvider {

    override suspend fun listChildren(parentPath: String): List<FileNode> {
        val file = File(archivePath)
        val sevenZFile = SevenZFile(file)
        val result = mutableListOf<FileNode>()
        val internalPath = if (parentPath.contains("::")) parentPath.substringAfter("::") else ""
        val normalizedInternalPath = if (internalPath.isEmpty() || internalPath.endsWith("/")) internalPath else "$internalPath/"
        val foldersSeen = mutableSetOf<String>()

        var entry = sevenZFile.getNextEntry()
        while (entry != null) {
            val entryName = entry.name
            if (entryName.startsWith(normalizedInternalPath) && entryName != normalizedInternalPath) {
                val relativeName = entryName.removePrefix(normalizedInternalPath)
                val parts = relativeName.split("/")
                if (parts.size > 1 || entry.isDirectory) {
                    val folderName = parts[0]
                    if (folderName !in foldersSeen) {
                        foldersSeen.add(folderName)
                        result.add(VfsFileNode(folderName, "$archivePath::$normalizedInternalPath$folderName/", 0, entry.lastModifiedDate?.time ?: 0L, true, FileType.DIRECTORY))
                    }
                } else {
                    result.add(VfsFileNode(parts[0], "$archivePath::$normalizedInternalPath${parts[0]}", entry.size, entry.lastModifiedDate?.time ?: 0L, false, FileType.FILE))
                }
            }
            entry = sevenZFile.getNextEntry()
        }
        sevenZFile.close()
        return result.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override suspend fun open(path: String): Source {
        val sevenZFile = SevenZFile(File(archivePath))
        val internalPath = path.substringAfter("::")
        var entry = sevenZFile.getNextEntry()
        while (entry != null) {
            if (entry.name == internalPath) {
                val content = ByteArray(entry.size.toInt())
                sevenZFile.read(content)
                sevenZFile.close()
                return ByteArrayInputStream(content).source()
            }
            entry = sevenZFile.getNextEntry()
        }
        sevenZFile.close()
        throw Exception("Arquivo não encontrado no 7z")
    }

    override suspend fun exists(path: String): Boolean = true
    override suspend fun delete(path: String): Boolean = false
    override suspend fun copy(source: String, destination: String) {}
    override suspend fun move(source: String, destination: String) {}
    override suspend fun rename(path: String, newName: String): Boolean = false
    override suspend fun createDirectory(parentPath: String, name: String): Boolean = false
    override suspend fun createFile(parentPath: String, name: String): Boolean = false

    override suspend fun search(rootPath: String, query: String): List<FileNode> {
        val file = File(archivePath)
        val sevenZFile = SevenZFile(file)
        val result = mutableListOf<FileNode>()
        
        var entry = sevenZFile.getNextEntry()
        while (entry != null) {
            if (entry.name.contains(query, ignoreCase = true)) {
                result.add(VfsFileNode(
                    entry.name.removeSuffix("/").substringAfterLast("/"),
                    "$archivePath::${entry.name}",
                    entry.size,
                    entry.lastModifiedDate?.time ?: 0L,
                    entry.isDirectory,
                    if (entry.isDirectory) FileType.DIRECTORY else FileType.FILE
                ))
            }
            entry = sevenZFile.getNextEntry()
        }
        sevenZFile.close()
        return result
    }
}
