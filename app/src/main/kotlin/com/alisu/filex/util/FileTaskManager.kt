package com.alisu.filex.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicInteger

sealed class FileTaskAction {
    data class Delete(val targets: List<String>, val useTrash: Boolean, val trashRetentionDays: Int) : FileTaskAction()
    data class Paste(val sourcePaths: List<String>, val targetPath: String, val isMove: Boolean) : FileTaskAction()
    data class Rename(val path: String, val newName: String) : FileTaskAction()
    object ClearTrash : FileTaskAction()
}

data class FileTaskState(
    val title: String = "",
    val content: String = "",
    val progress: Int = 0,
    val total: Int = 0,
    val isActive: Boolean = false,
    val pendingCount: Int = 0
)

object FileTaskManager {
    private val _taskState = MutableStateFlow(FileTaskState())
    val taskState = _taskState.asStateFlow()

    private val _actionChannel = Channel<FileTaskAction>(Channel.UNLIMITED)
    val actionFlow = _actionChannel.receiveAsFlow()

    private val activeTasks = AtomicInteger(0)
    private var isCanceled = false

    fun enqueue(action: FileTaskAction) {
        isCanceled = false
        activeTasks.incrementAndGet()
        _actionChannel.trySend(action)
        updateState { it.copy(isActive = true, pendingCount = activeTasks.get()) }
    }

    fun taskStarted(title: String) {
        updateState { it.copy(title = title, content = "", progress = 0, total = 0, isActive = true) }
    }

    fun taskFinished() {
        val remaining = activeTasks.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0) {
            stopTask()
        } else {
            updateState { it.copy(pendingCount = remaining) }
        }
    }

    fun cancelTask() {
        isCanceled = true
        activeTasks.set(0)
        stopTask()
    }

    fun isCanceled() = isCanceled

    fun updateProgress(content: String, progress: Int, total: Int) {
        updateState { it.copy(
            content = content,
            progress = progress,
            total = total,
            isActive = true
        ) }
    }

    fun stopTask() {
        activeTasks.set(0)
        _taskState.value = FileTaskState(isActive = false, pendingCount = 0)
    }

    private inline fun updateState(crossinline block: (FileTaskState) -> FileTaskState) {
        _taskState.value = block(_taskState.value)
    }
}