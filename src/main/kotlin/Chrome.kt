package com.github.jo_makar

import io.github.oshai.kotlinlogging.KotlinLogging

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import kotlin.io.path.deleteRecursively
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class Chrome(profile: String = "scraper", headless: Boolean = true): AutoCloseable {
    private val tempDir: Path

    init {
        var complete = false
        var _tempDir: Path? = null

        try {
            _tempDir = Files.createTempDirectory("spelunker3-chrome-")
            logger.info { "temp dir: $_tempDir" }

            val pipePath = _tempDir.resolve("pipe").toString()
            if (!executeCommand(listOf("mkfifo", pipePath), 5000)) {
                throw IllegalStateException("mkfifo failed")
            }

            // FIXME STOPPED Use mkfifo to create named pipes in (init)TempDir
            //               for later use with the google-chrome command
            //               (eg sh -c 'google-chrome ... 3>/tmp/...abc 4</tmp/...def')

            val command: List<String> = buildList {
                add("google-chrome")
                add("--remote-debugging-pipe")
                // A non-standard --user-data-dir is required for use with --remote-debugging-port/pipe
                // Ref: https://developer.chrome.com/blog/remote-debugging-port
                add("--user-data-dir=${System.getenv("HOME")!!}/.config/google-chrome-$profile")
                if (headless) {
                    add("--headless")
                }
            }

            // FIXME Use java.lang.ProcessBuilder to launch chrome
            //ProcessBuilder(command)

            complete = true
            tempDir = _tempDir
        } finally {
            if (!complete) {
                _tempDir?.deleteRecursively()
            }
        }
    }

    override fun close() {
        tempDir.deleteRecursively()
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        private fun executeCommand(command: List<String>, timeoutMs: Long): Boolean {
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
}