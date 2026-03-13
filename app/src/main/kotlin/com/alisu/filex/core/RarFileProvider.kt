package com.alisu.filex.core

import com.github.junrar.Archive
import okio.Source
import okio.source
import java.io.File
import java.io.ByteArrayOutputStream

class RarFileProvider(private val archivePath: String) : FileProvider {

    override suspend fun listChildren(parentPath: String): List<FileNode> {
        val result = mutableListOf<FileNode>()
        val internalPath = if (parentPath.contains("::")) parentPath.substringAfter("::") else ""
        val normalizedInternalPath = if (internalPath.isEmpty() || internalPath.endsWith("/")) internalPath else "$internalPath/"
        val foldersSeen = mutableSetOf<String>()

        Archive(File(archivePath)).use { archive ->
            var header = archive.nextFileHeader()
            while (header != null) {
                val entryName = if (header.isUnicode) header.fileNameW else header.fileNameString
                if (entryName.startsWith(normalizedInternalPath) && entryName != normalizedInternalPath) {
                    val relativeName = entryName.removePrefix(normalizedInternalPath)
                    val parts = relativeName.split("/")
                    if (parts.size > 1 || header.isDirectory) {
                        val folderName = parts[0]
                        if (folderName !in foldersSeen) {
                            foldersSeen.add(folderName)
                            result.add(VfsFileNode(folderName, "$archivePath::$normalizedInternalPath$folderName/", 0, header.mTime?.time ?: 0L, true, FileType.DIRECTORY))
                        }
                    } else {
                        result.add(VfsFileNode(parts[0], "$archivePath::$normalizedInternalPath${parts[0]}", header.fullUnpackSize, header.mTime?.time ?: 0L, false, FileType.FILE))
                    }
                }
                header = archive.nextFileHeader()
            }
        }
        return result.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override suspend fun open(path: String): Source {
        val internalTarget = path.substringAfter("::")
        Archive(File(archivePath)).use { archive ->
            var header = archive.nextFileHeader()
            while (header != null) {
                val entryName = if (header.isUnicode) header.fileNameW else header.fileNameString
                if (entryName == internalTarget) {
                    val bos = ByteArrayOutputStream()
                    archive.extractFile(header, bos)
                    return bos.toByteArray().inputStream().source()
                }
                header = archive.nextFileHeader()
            }
        }
        throw Exception("Arquivo não encontrado no RAR")
    }

    override suspend fun exists(path: String): Boolean = true
    override suspend fun delete(path: String): Boolean = false
    override suspend fun copy(source: String, destination: String) {}
    override suspend fun move(source: String, destination: String) {}
    override suspend fun rename(path: String, newName: String): Boolean = false
    override suspend fun createDirectory(parentPath: String, name: String): Boolean = false
    override suspend fun createFile(parentPath: String, name: String): Boolean = false

    override suspend fun search(rootPath: String, query: String): List<FileNode> {
        val result = mutableListOf<FileNode>()
        Archive(File(archivePath)).use { archive ->
            var header = archive.nextFileHeader()
            while (header != null) {
                val entryName = if (header.isUnicode) header.fileNameW else header.fileNameString
                if (entryName.contains(query, ignoreCase = true)) {
                    result.add(VfsFileNode(
                        entryName.removeSuffix("/").substringAfterLast("/"),
                        "$archivePath::$entryName",
                        header.fullPackSize,
                        header.mTime?.time ?: 0L,
                        header.isDirectory,
                        if (header.isDirectory) FileType.DIRECTORY else FileType.FILE
                    ))
                }
                header = archive.nextFileHeader()
            }
        }
        return result
    }
}
