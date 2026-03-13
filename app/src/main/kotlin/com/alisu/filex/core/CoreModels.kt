package com.alisu.filex.core

enum class FileType {
    FILE, DIRECTORY, ZIP, APK, TAR, GZIP, SEVEN_ZIP, RAR, IMAGE, VIDEO, VECTOR, PDF, AUDIO, TEXT, FONT, CODE, WORD, EXCEL, POWERPOINT, WEB, LIB
}

interface FileNode {
    val name: String
    val path: String
    val size: Long
    val lastModified: Long
    val isDirectory: Boolean
    val type: FileType
    val mimeType: String?
}

interface FileProvider {
    suspend fun listChildren(parentPath: String): List<FileNode>
    suspend fun open(path: String): okio.Source
    suspend fun exists(path: String): Boolean
    suspend fun delete(path: String): Boolean
    suspend fun copy(source: String, destination: String)
    suspend fun move(source: String, destination: String)
    suspend fun rename(path: String, newName: String): Boolean
    suspend fun createDirectory(parentPath: String, name: String): Boolean
    suspend fun createFile(parentPath: String, name: String): Boolean
    suspend fun search(rootPath: String, query: String): List<FileNode>
}

class VfsFileNode(
    override val name: String,
    override val path: String,
    override val size: Long,
    override val lastModified: Long,
    override val isDirectory: Boolean,
    override val type: FileType,
    override val mimeType: String? = null
) : FileNode
