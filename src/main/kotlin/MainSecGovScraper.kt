package com.github.jo_makar

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.jo_makar.scrapers.SecGov

import io.github.oshai.kotlinlogging.KotlinLogging

import java.time.LocalDate

import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

class Command : CliktCommand() {
    val formType: String by option().default("4")

    // Start date is inclusive and end date is exclusive
    // Expected format: yyyy-mm-dd
    val startDate: LocalDate by option()
        .convert { LocalDate.parse(it) }
        .default(LocalDate.now().minusDays(7))
    val endDate: LocalDate by option()
        .convert { LocalDate.parse(it) }
        .default(LocalDate.now())

    override fun run() {
        if (formType != "4") {
            throw BadParameterValue("formType != 4")
        }
        if (startDate >= endDate) {
            throw BadParameterValue("startDate >= endDate")
        }
        logger.info { "start/endDate = $startDate - $endDate" }

        runBlocking {
            SecGov.scrapeForm4(startDate, endDate).collect { form4 ->
                logger.info { form4 }
            }
        }
    }
}

fun main(args: Array<String>) = Command().main(args)