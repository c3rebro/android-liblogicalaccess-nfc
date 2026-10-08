package de.shansen.liblogicalaccessnfc

import android.content.Context
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

object AppLogger {

    private const val MAX_FILE_BYTES = 512 * 1024L  // 512 KB per segment
    private const val MAX_FILES = 3                  // app.log, app.log.1, app.log.2
    private const val LOG_NAME = "app.log"

    private val executor = Executors.newSingleThreadExecutor()
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private var logDir: File? = null

    fun init(context: Context) {
        logDir = File(context.filesDir, "logs").also { it.mkdirs() }
        log("APP", "Logger init")
    }

    fun log(tag: String, message: String) {
        val line = "${OffsetDateTime.now().format(fmt)}  [$tag]  $message\n"
        executor.execute {
            val dir = logDir ?: return@execute
            try {
                rotate(dir)
                File(dir, LOG_NAME).appendText(line, Charsets.UTF_8)
            } catch (_: Exception) {}
        }
    }

    private fun rotate(dir: File) {
        val current = File(dir, LOG_NAME)
        if (!current.exists() || current.length() < MAX_FILE_BYTES) return
        for (i in MAX_FILES - 1 downTo 1) {
            val older = File(dir, "$LOG_NAME.$i")
            val newer = if (i == 1) current else File(dir, "$LOG_NAME.${i - 1}")
            if (newer.exists()) { older.delete(); newer.renameTo(older) }
        }
    }

    /** All log files, newest segment first. */
    fun logFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return buildList {
            val current = File(dir, LOG_NAME)
            if (current.exists()) add(current)
            for (i in 1 until MAX_FILES) {
                val f = File(dir, "$LOG_NAME.$i")
                if (f.exists()) add(f)
            }
        }
    }
}
