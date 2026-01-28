package com.github.jo_makar

import io.github.oshai.kotlinlogging.KotlinLogging

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

fun main() = runBlocking {
    Chrome.create().use { chrome ->
        // FIXME
        delay(15000)
    }
}