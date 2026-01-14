package com.github.jo_makar

import io.github.oshai.kotlinlogging.KotlinLogging

class Chrome {
    private val logger = KotlinLogging.logger {}
    init {
        // FIXME
        logger.info { "initializing" }
    }
}