package com.github.jo_makar

import io.github.oshai.kotlinlogging.KotlinLogging

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit

import kotlin.concurrent.atomics.AtomicInt
import kotlin.io.path.deleteRecursively
import kotlin.io.path.outputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class Chrome private constructor(
    private val scope: CoroutineScope,
    private val tempDir: Path,
    private val process: Process,
    private val pipeFlow: SharedFlow<JsonObject>,
    private val cmdFlow: MutableSharedFlow<String>
): AutoCloseable {
    private var cmdId = AtomicInt(0)
    private var targetToSessionMap: MutableMap<String, String> = mutableMapOf()

    companion object {
        private val logger = KotlinLogging.logger {}

        suspend fun create(profile: String = "scraper", headless: Boolean = true): Chrome {
            val scope = CoroutineScope(Dispatchers.IO)
            val pipeFlow = MutableSharedFlow<JsonObject>()
            val cmdFlow = MutableSharedFlow<String>()

            var success = false
            var tempDir: Path? = null
            var process: Process? = null

            try {
                tempDir = scope.run {
                    Files.createTempDirectory("spelunker3-chrome-")
                }
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
                    append("--remote-debugging-pipe ")
                    // A non-standard --user-data-dir is required for use with --remote-debugging-port/pipe
                    // Ref: https://developer.chrome.com/blog/remote-debugging-port
                    append("--user-data-dir=${System.getenv("HOME")!!}/.config/google-chrome-$profile ")
                    if (headless) {
                        append("--headless ")
                    }
                    append("3<$inputPipePath 4>$outputPipePath")
                }

                process = scope.run {
                    ProcessBuilder("sh", "-c", command)
                        .redirectErrorStream(true)
                        .start()
                }
                logger.info { "process started with pid = ${process.pid()}" }

                scope.launch {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            logger.info { "stdout/err: $line" }
                        }
                    }
                }

                // NB Messages (commands, responses, events) are delimited by the null byte
                // Ref: https://github.com/cyrus-and/chrome-remote-interface/issues/381

                scope.launch {
                    // Using FileChannel directly (rather than FileInputStream) to avoid buffering issues,
                    // ie FileChannel will return partial buffer fills rather than block until full (or EOF).

                    FileChannel.open(outputPipePath, StandardOpenOption.READ).use { fileChannel ->
                        val buffer = ByteBuffer.allocate(1024)

                        fun ByteBuffer.indexOf(b: Byte): Int {
                            for (i in position()..<limit()) {
                                if (get(i) == b)
                                    return i
                            }
                            return -1
                        }

                        while (true) {
                            val bytesRead = fileChannel.read(buffer)
                            when (bytesRead) {
                                -1 -> break
                                0 -> delay(100)
                                else -> {
                                    buffer.flip()
                                    while (true) {
                                        when (val idx = buffer.indexOf(0)) {
                                            -1 -> break
                                            else -> {
                                                val entryBytes = ByteArray(idx)
                                                buffer.get(entryBytes)
                                                buffer.get() // Null byte

                                                // NB Malformed input is replaced with the Unicode replacement char
                                                val entryString = String(entryBytes, Charsets.UTF_8)
                                                logger.info { "input: $entryString" }

                                                val entryObject: JsonObject? = try {
                                                    Json.decodeFromString(entryString)
                                                } catch (e: Exception) {
                                                    logger.error(e) { "failed to deserialize: $entryString" }
                                                    null
                                                }
                                                if (entryObject != null) {
                                                    pipeFlow.emit(entryObject)
                                                }
                                            }
                                        }
                                    }
                                    buffer.compact()
                                }
                            }
                        }
                        logger.warn { "input pipe eof" }
                    }
                }

                scope.launch {
                    inputPipePath.outputStream().use { outputStream ->
                        cmdFlow.asSharedFlow().collect { entry ->
                            scope.run {
                                outputStream.write(entry.toByteArray())
                                outputStream.write(0)
                                outputStream.flush()
                            }
                            logger.info { "cmd: $entry" }
                        }
                    }
                }

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

            val chrome = Chrome(scope, tempDir, process, pipeFlow.asSharedFlow(), cmdFlow)
            delay(5000)

            val targetPages: List<JsonObject> = run {
                val resp = chrome.sendCmd("Target.getTargets")
                when(val targets = resp["targetInfos"]) {
                    null -> throw IllegalStateException("missing targetInfos: $resp")
                    is JsonArray -> {
                        targets
                            .filter {
                                it is JsonObject &&
                                it["type"] == JsonPrimitive("page")
                            }
                            .map { it as JsonObject }
                            .toList()
                    }
                    else -> throw IllegalStateException("unexpected targetInfos type: $resp")
                }
            }
            if (targetPages.isEmpty()) {
                throw IllegalStateException("no page targets found")
            }

            val targetId = run {
                val first = targetPages.first()
                when(val id = first["targetId"]) {
                    null -> throw IllegalStateException("missing targetId: $first")
                    is JsonPrimitive if id.isString -> id.content
                    else -> throw IllegalStateException("unexpected targetId type: $first")
                }
            }
            val sessionId = run {
                val resp = chrome.sendCmd(
                    "Target.attachToTarget",
                    params = mapOf(
                        "targetId" to JsonPrimitive(targetId),
                        "flatten" to JsonPrimitive(true)
                    )
                )
                when(val id = resp["sessionId"]) {
                    null -> throw IllegalStateException("missing sessionId: $resp")
                    is JsonPrimitive if id.isString -> id.content
                    else -> throw IllegalStateException("unexpected sessionId type: $resp")
                }
            }
            chrome.targetToSessionMap[targetId] = sessionId
            chrome.setupSession(sessionId)

            return chrome
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
        runBlocking {
            sendCmd("Browser.close", timeoutMs = 15000)
        }

        scope.cancel()

        process
            .takeIf(Process::isAlive)
            ?.let {
                fun destroy(handle: ProcessHandle, timeoutMs: Long = 15000) {
                    handle.destroy()
                    try {
                        handle.onExit().get(timeoutMs, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        logger.error { "process ${handle.pid()} destroy timeout, forcibly destroyed" }
                        handle.destroyForcibly()
                    }
                }

                it.descendants()
                    .filter(ProcessHandle::isAlive)
                    .forEach { handle -> destroy(handle) }
                if (it.isAlive) {
                    destroy(it.toHandle())
                }
            }

        tempDir.deleteRecursively()
    }

    suspend fun sendCmd(
        method: String,
        sessionId: String? = null,
        params: Map<String, JsonElement> = emptyMap(),
        timeoutMs: Long = 5000
    ): JsonObject {
        val cmd: Map<String, JsonElement> = buildMap {
            put("id", JsonPrimitive(cmdId.fetchAndAdd(1)))
            put("method", JsonPrimitive(method))

            if (sessionId != null) {
                put("sessionId", JsonPrimitive(sessionId))
            } else if (targetToSessionMap.size == 1) {
                put("sessionId", JsonPrimitive(targetToSessionMap.entries.first().value))
            }

            if (params.isNotEmpty())
                put("params", buildJsonObject {
                    params.forEach {
                        put(it.key, it.value)
                    }
                })
        }

        val deferredResp = scope.async {
            pipeFlow
                .filter { it["id"] == cmd["id"] }
                .first()
        }

        cmdFlow.emit(Json.encodeToString(cmd))
        val resp = withTimeout(timeoutMs) {
            deferredResp.await()
        }
        logger.info { "resp: $resp" }

        when (val respResult = resp["result"]) {
            null -> throw IllegalStateException("missing result: $resp")
            is JsonObject -> return respResult
            else -> throw IllegalStateException("unexpected result type: $resp")
        }
    }

    private fun setupSession(sessionId: String) {
        runBlocking {
            sendCmd("Page.enable", sessionId)

            val userAgent: String = run {
                when(val resp = sendCmd("Browser.getVersion", sessionId)["userAgent"]) {
                    null -> throw IllegalStateException("missing userAgent: $resp")
                    is JsonPrimitive if resp.isString -> resp.toString()
                    else -> throw IllegalStateException("unexpected userAgent type: $resp")
                }
            }
            val newUserAgent = userAgent.replace("HeadlessChrome", "Chrome")

            sendCmd(
                "Network.setUserAgentOverride",
                sessionId,
                mapOf("userAgent" to JsonPrimitive(newUserAgent))
            )
        }
    }

    fun newTab(): String {
        return runBlocking {
            val targetId: String = run {
                val resp = sendCmd(
                    "Target.createTarget",
                    params = mapOf(
                        "url" to JsonPrimitive("about:blank"),
                        "newWindow" to JsonPrimitive(false)
                    )
                )
                when(val id = resp["targetId"]) {
                    null -> throw IllegalStateException("missing targetId: $resp")
                    is JsonPrimitive if id.isString -> id.content
                    else -> throw IllegalStateException("unexpected targetId type: $resp")
                }
            }

            val sessionId = run {
                val resp = sendCmd(
                    "Target.attachToTarget",
                    params = mapOf(
                        "targetId" to JsonPrimitive(targetId),
                        "flatten" to JsonPrimitive(true)
                    )
                )
                when(val id = resp["sessionId"]) {
                    null -> throw IllegalStateException("missing sessionId: $resp")
                    is JsonPrimitive if id.isString -> id.content
                    else -> throw IllegalStateException("unexpected sessionId type: $resp")
                }
            }

            targetToSessionMap[targetId] = sessionId
            setupSession(sessionId)
            sessionId
        }
    }

    fun closeTab(sessionId: String) {
        var targetId: String? = null
        for ((entryTargetId, entrySessionId) in targetToSessionMap) {
            if (entrySessionId == sessionId) {
                targetId = entryTargetId
                break
            }
        }
        if (targetId == null)
            throw IllegalStateException("session id not found: $sessionId")

        targetToSessionMap.remove(targetId)
        runBlocking { sendCmd("Target.closeTarget", sessionId) }
    }

    // FIXME STOPPED navigate(url, referer, timeout=60s)
    //               evaluate(expr, timeout=5s)
}