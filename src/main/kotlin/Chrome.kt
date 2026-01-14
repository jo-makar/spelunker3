package com.github.jo_makar

import io.github.oshai.kotlinlogging.KotlinLogging

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively

class Chrome(profile: String = "scraper", headless: Boolean = true): AutoCloseable {
    private val logger = KotlinLogging.logger {}
    private val tempDir: Path

    init {
        var complete = false
        var _tempDir: Path? = null

        try {
            _tempDir = Files.createTempDirectory("spelunker3-chrome-")
            logger.info { "temp dir: $_tempDir" }

            // FIXME STOPPED Run mkfifo to create named pipes in (init)TempDir
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
}