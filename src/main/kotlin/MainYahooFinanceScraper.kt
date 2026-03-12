package com.github.jo_makar

import com.github.jo_makar.scrapers.YahooFinance

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple

import io.github.oshai.kotlinlogging.KotlinLogging

import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

class MainYahooFinanceScraper : CliktCommand() {
    val tickers: List<String> by argument().multiple(required = true)

    override fun run() {
        runBlocking {
            val results = mutableListOf<YahooFinance.Ticker>()
            YahooFinance.scrapeTickers(tickers).collect { result ->
                logger.info { result }
                results.add(result)
            }

            results.forEach {
                val pctChange = (it.newClose - it.oldClose) / it.oldClose * 100.0
                println(String.format("%6s %7.2f%% %s $%.2f %s $%.2f", it.ticker, pctChange, it.newDate, it.newClose, it.oldDate, it.oldClose))
            }
        }
    }
}

fun main(args: Array<String>) = MainYahooFinanceScraper().main(args)
