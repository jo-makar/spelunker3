package com.github.jo_makar

import io.github.oshai.kotlinlogging.KotlinLogging

import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

fun main() = runBlocking {
    Chrome.create().use { chrome ->
        chrome.navigate("https://www.whatsmyip.org")
        val ip = chrome.evaluate("document.getElementById(\"ip\").textContent")
        logger.info { "ip = $ip" }
    }
}