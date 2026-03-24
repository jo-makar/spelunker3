package com.github.jo_makar

import com.github.jo_makar.scrapers.SecGov
import com.github.jo_makar.scrapers.YahooFinance

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList

import java.time.LocalDate
import java.util.Collections
import java.util.TreeSet

import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

class SecGovScraper : CliktCommand() {
    val formType: String by option().default("4")

    // Start date is inclusive and end date is exclusive
    // Expected format: yyyy-mm-dd
    val startDate: LocalDate by option()
        .convert { LocalDate.parse(it) }
        .default(LocalDate.now().minusDays(7))
    val endDate: LocalDate by option()
        .convert { LocalDate.parse(it) }
        .default(LocalDate.now())

    val threshold: Double by option().double().default(100_000.0)
    val cik: String? by option()

    override fun run() {
        if (formType != "4") {
            throw BadParameterValue("formType != 4")
        }
        if (startDate >= endDate) {
            throw BadParameterValue("startDate >= endDate")
        }
        logger.info { "start/endDate = $startDate - $endDate" }

        runBlocking {
            data class Entry(
                val form: SecGov.Form4,
                val crs: Double,
            ): Comparable<Entry> {
                override fun compareTo(other: Entry): Int =
                    if (form.ticker != other.form.ticker) {
                        crs.compareTo(other.crs)
                    } else {
                        form.value.compareTo(other.form.value)
                    }
            }

            val sortedEntries = TreeSet<Entry>(Collections.reverseOrder())

            // Comparative Relative Strength: (ticker six-month return) - (market index six-month return)
            val crsByTicker = mutableMapOf<String, Double>()
            val indexTickerPctChange = run {
                val ticker = YahooFinance.scrapeTickers(listOf("spy")).toList().first()
                (ticker.newClose - ticker.midClose) / ticker.midClose * 100.0
            }

            SecGov.scrapeForms4(startDate, endDate, cik).collect { form4 ->
                logger.info { form4 }

                val filter = when {
                    listOf("N/A", "NONE").contains(form4.ticker) -> true
                    form4.value < threshold -> true
                    else -> false
                }
                if (!filter) {
                    if (!crsByTicker.containsKey(form4.ticker)) {
                        crsByTicker[form4.ticker] = run {
                            val ticker = YahooFinance.scrapeTickers(listOf(form4.ticker)).toList().first()
                            (ticker.newClose - ticker.midClose) / ticker.midClose * 100.0 - indexTickerPctChange
                        }
                    }

                    sortedEntries.add(Entry(form4, crsByTicker[form4.ticker]!!))
                }
            }

            sortedEntries.forEach {
                println(buildString {
                    append(String.format("%6s ", it.form.ticker))
                    if (cik == null) {
                        append("${it.form.cik} ")
                        append(String.format("%6.1f%% ", it.crs))
                    }
                    append(String.format("$%.0f ", it.form.value).padStart(11, ' '))
                    if (cik != null) {
                        append(it.form.date.toString())
                        append("${it.form.ownerTitle} ")
                        append(String.format("%b ", it.form.has10b51))
                    }
                    append(it.form.filingDetailUrl)
                })
            }
        }
    }
}

fun main(args: Array<String>) = SecGovScraper().main(args)