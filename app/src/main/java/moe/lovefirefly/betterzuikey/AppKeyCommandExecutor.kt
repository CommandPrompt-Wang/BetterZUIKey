package moe.lovefirefly.betterzuikey

import android.content.Context
import moe.lovefirefly.betterzuikey.Utils.LogHelper
import moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Runs smart-key shell scripts in the module app process.
 * Default: `/system/bin/sh -c` (app UID). Root checkbox: `su -c`.
 */
object AppKeyCommandExecutor {

    const val EXIT_TIMEOUT = -2

    data class Result(val exitCode: Int, val output: String)

    private val processLock = Any()
    @Volatile
    private var activeProcess: Process? = null

    @JvmStatic
    fun runAsync(
        context: Context,
        script: String,
        root: Boolean,
        singleton: Boolean = true,
        timeoutMinutes: Int = 1,
    ) {
        val trimmed = script.trim()
        if (trimmed.isEmpty()) return
        Thread({
            val result = execute(context, trimmed, root, singleton, timeoutMinutes)
            logResult(root, result)
        }, "AppKeyCommand").start()
    }

    @JvmStatic
    @JvmOverloads
    fun execute(
        context: Context,
        script: String,
        root: Boolean,
        singleton: Boolean = false,
        timeoutMinutes: Int = 1,
    ): Result {
        val trimmed = script.trim()
        if (trimmed.isEmpty()) {
            return Result(-1, "")
        }
        if (singleton) {
            stopActiveProcess()
        }
        return try {
            val pb = if (root) {
                ProcessBuilder("su", "-c", trimmed)
            } else {
                ProcessBuilder("/system/bin/sh", "-c", trimmed)
            }
            pb.redirectErrorStream(true)
            pb.directory(context.filesDir)
            val process = pb.start()
            registerActiveProcess(process)

            val outputLock = Any()
            val outputBuilder = StringBuilder()
            val readerThread = Thread({
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            synchronized(outputLock) {
                                outputBuilder.append(line).append('\n')
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }, "AppKeyCommandReader")
            readerThread.start()

            val timeoutMs = timeoutMinutes.coerceAtLeast(1).toLong() * 60_000L
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            readerThread.join(500)

            val output = synchronized(outputLock) { outputBuilder.toString().trimEnd() }
            if (!finished) {
                process.destroyForcibly()
                clearActiveProcess(process)
                val body = output.ifEmpty { context.getString(R.string.dialog_app_key_command_no_output) }
                return Result(EXIT_TIMEOUT, body)
            }

            clearActiveProcess(process)
            Result(process.exitValue(), output)
        } catch (e: Exception) {
            Result(-1, e.message ?: e.toString())
        }
    }

    @JvmStatic
    fun formatForDisplay(context: Context, result: Result, timeoutMinutes: Int = 1): String {
        if (result.exitCode == -1 && result.output.isEmpty()) {
            return context.getString(R.string.dialog_app_key_command_empty)
        }
        if (result.exitCode == EXIT_TIMEOUT) {
            val body = result.output.ifEmpty {
                context.getString(R.string.dialog_app_key_command_no_output)
            }
            return context.getString(R.string.dialog_app_key_command_timeout_format, timeoutMinutes, body)
        }
        val body = result.output.ifEmpty {
            context.getString(R.string.dialog_app_key_command_no_output)
        }
        return if (result.exitCode == 0) {
            body
        } else {
            context.getString(R.string.dialog_app_key_command_exit_format, result.exitCode, body)
        }
    }

    private fun registerActiveProcess(process: Process) {
        synchronized(processLock) { activeProcess = process }
    }

    private fun clearActiveProcess(process: Process) {
        synchronized(processLock) {
            if (activeProcess === process) {
                activeProcess = null
            }
        }
    }

    private fun stopActiveProcess() {
        synchronized(processLock) {
            activeProcess?.destroyForcibly()
            activeProcess = null
        }
    }

    private fun logResult(root: Boolean, result: Result) {
        if (result.exitCode == 0) {
            LogHelper.log(
                VerboseLevel.INFO,
                "AppKeyCommand: exit=0 root=", root.toString(),
                if (result.output.isEmpty()) "" else " out=${result.output}"
            )
        } else {
            LogHelper.log(
                VerboseLevel.WARNING,
                "AppKeyCommand: exit=", result.exitCode.toString(),
                " root=", root.toString(),
                if (result.output.isEmpty()) "" else " out=${result.output}"
            )
        }
    }
}
