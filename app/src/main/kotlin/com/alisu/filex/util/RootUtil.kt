package com.alisu.filex.util

import com.topjohnwu.superuser.Shell

object RootUtil {

    // A inicialização agora é feita de forma preguiçosa no primeiro acesso
    private val shell: Shell by lazy {
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
        )
        Shell.getShell()
    }

    fun isRootAvailable(): Boolean {
        return try {
            shell.isRoot
        } catch (e: Exception) {
            false
        }
    }

    fun execute(command: String): List<String> {
        return Shell.cmd(command).exec().out
    }
    
    fun runCommand(command: String): Boolean {
        return Shell.cmd(command).exec().isSuccess
    }
}
