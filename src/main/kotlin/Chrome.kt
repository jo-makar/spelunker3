package com.github.jo_makar

import io.github.oshai.kotlinlogging.KotlinLogging

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.Scanner

import kotlin.io.path.deleteRecursively
import kotlin.io.path.outputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class Chrome private constructor(
    private val scope: CoroutineScope,
    private val tempDir: Path,
    private val process: Process
): AutoCloseable {
    companion object {
        private val logger = KotlinLogging.logger {}

        suspend fun create(
            scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
            profile: String = "scraper",
            headless: Boolean = true
        ): Chrome {
            var success = false
            var tempDir: Path? = null
            var process: Process? = null

            try {
                tempDir = Files.createTempDirectory("spelunker3-chrome-")
                logger.info { "temp dir: $tempDir" }

                val inputPipePath = tempDir.resolve("input-pipe")
                val outputPipePath = tempDir.resolve("output-pipe")
                for (pipePath in listOf(inputPipePath.toString(), outputPipePath.toString())) {
                    if (!executeCommand(listOf("mkfifo", pipePath))) {
                        throw IllegalStateException("mkfifo failed")
                    }
                }

                val command = buildString {
                    append("google-chrome ")
                    append("--remote-debugging-port ")
                    // A non-standard --user-data-dir is required for use with --remote-debugging-port/pipe
                    // Ref: https://developer.chrome.com/blog/remote-debugging-port
                    append("--user-data-dir=${System.getenv("HOME")!!}/.config/google-chrome-$profile ")
                    if (headless) {
                        append("--headless ")
                    }
                    append("3<$inputPipePath 4>$outputPipePath")
                }

                process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                logger.info { "process started with pid = ${process.pid()}" }

                scope.launch {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            logger.info { "stdout/err: $line" }
                        }
                    }
                }

                scope.launch {
                    // Ref: https://github.com/cyrus-and/chrome-remote-interface/issues/381
                    Scanner(outputPipePath.toFile()).use { scanner ->
                        scanner.useDelimiter("\u0000")
                        while (scanner.hasNext()) {
                            val line = scanner.next()
                            logger.info { "input: $line" }
                            // FIXME Write to a circular buffer (guarded by a mutex)
                        }
                        logger.warn { "input pipe eof" }
                    }
                }

                scope.launch {
                    // FIXME Read from a queue (guarded by a mutex?) and write to the output stream
                    inputPipePath.outputStream().use { outputStream ->
                        // FIXME outputStream.write(/*...*/.toByteArray()) then flush()
                    }
                }

                delay(15000)

                success = true
            } finally {
                if (!success) {
                    scope.cancel()
                    process?.let {
                        it.descendants().forEach(ProcessHandle::destroyForcibly)
                        it.destroyForcibly()
                    }
                    tempDir?.deleteRecursively()
                }
            }
            return Chrome(scope, tempDir, process)
        }

        private fun executeCommand(command: List<String>, timeoutMs: Long = 5000): Boolean {
            return runBlocking {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

                val deferredOutput = async {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        reader.readText()
                    }
                }

                if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    val output = deferredOutput.await()
                    val exitValue = process.exitValue()
                    if (exitValue == 0) {
                        if (output.isNotEmpty()) {
                            logger.info { "command (${command[0]}) successful, output = $output" }
                        }
                    } else {
                        logger.error { "command (${command[0]}) failed, exit value = $exitValue, output = $output" }
                        return@runBlocking false
                    }
                } else {
                    logger.error { "command (${command[0]}) timeout, forcibly destroyed" }
                    process.destroyForcibly()
                    return@runBlocking false
                }

                return@runBlocking true
            }
        }
    }

    override fun close() {
        scope.cancel()

        process.let {
            fun destroy(handle: ProcessHandle, timeoutMs: Long = 15000) {
                handle.destroy()
                try {
                    handle.onExit().get(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    logger.error { "process ${handle.pid()} destroy timeout, forcibly destroyed" }
                    handle.destroyForcibly()
                }
            }

            it.descendants().forEach { handle -> destroy(handle) }
            destroy(it.toHandle())
        }

        tempDir.deleteRecursively()
    }
}