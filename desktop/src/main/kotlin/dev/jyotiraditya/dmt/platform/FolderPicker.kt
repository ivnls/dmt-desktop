package dev.jyotiraditya.dmt.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Directory chooser: the desktop's own dialog through zenity / kdialog on linux when
 * available (portal-friendly on gnome and kde), a swing chooser otherwise.
 */
object FolderPicker {

    suspend fun pick(title: String, start: File?): File? {
        if (Platform.isLinux) {
            nativeLinux(title, start)?.let { return it.getOrNull() }
        }
        return withContext(Dispatchers.Main) { swing(title, start) }
    }

    /** null = no native helper; Result(null) = user cancelled. */
    private suspend fun nativeLinux(title: String, start: File?): Result<File?>? =
        withContext(Dispatchers.IO) {
            val startPath = (start ?: File(System.getProperty("user.home"))).absolutePath
            val commands = listOf(
                listOf("zenity", "--file-selection", "--directory", "--title=$title", "--filename=$startPath/"),
                listOf("kdialog", "--getexistingdirectory", startPath, "--title", title),
                listOf("qarma", "--file-selection", "--directory", "--title=$title"),
            )
            for (command in commands) {
                val process = runCatching {
                    ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                }.getOrNull() ?: continue
                val out = process.inputStream.bufferedReader().readText().trim()
                process.waitFor(10, TimeUnit.MINUTES)
                val code = process.exitValue()
                return@withContext when {
                    code == 0 && out.isNotEmpty() -> Result.success(File(out).takeIf { it.isDirectory })
                    code == 1 -> Result.success(null)
                    else -> continue
                }
            }
            null
        }

    private fun swing(title: String, start: File?): File? {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val chooser = JFileChooser(start ?: File(System.getProperty("user.home"))).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.takeIf { it.isDirectory }
        } else {
            null
        }
    }
}
