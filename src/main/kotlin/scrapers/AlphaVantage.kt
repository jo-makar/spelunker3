package com.github.jo_makar.scrapers

import io.github.oshai.kotlinlogging.KotlinLogging

import java.io.File
import java.time.format.DateTimeFormatter
import java.time.LocalDate

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Alpha Vantage ticker data
 * NB A free api key allows 500 api calls per day
 */
class AlphaVantage {
    companion object {
        private val logger = KotlinLogging.logger {}

        fun scrapeTickers(apiKey: String, tickers: List<String>): Flow<Ticker> {
            val client = OkHttpClient.Builder()
                .cache(Cache(File("/tmp/spelunker3-alphavantage"), 128 * 1048576))
                .build()

            return flow {
                for (ticker in tickers) {
                    val url = buildString {
                        // Unfortunately the free api use of TIME_SERIES_DAILY only provides the last 100 days
                        append("https://www.alphavantage.co/query?function=TIME_SERIES_WEEKLY")
                        append("&symbol=${ticker.uppercase()}")
                        append("&apikey=$apiKey")
                    }
                    logger.info { "url = $url" }

                    client.newCall(
                        Request.Builder().url(url).build()
                    ).execute().use { resp ->
                        val body: JsonObject = Json.decodeFromStream(resp.body.byteStream())

                        val series: JsonObject = when(val series = body["Weekly Time Series"]) {
                            null -> throw IllegalStateException("series missing")
                            is JsonObject -> series
                            else -> throw IllegalStateException("series unexpected type")
                        }

                        val (start, end) = LocalDate.now().let {
                            val yearAgo = it.minusYears(1)
                            val formatter = DateTimeFormatter.ofPattern("yyyy-MM")
                            Pair(formatter.format(it), formatter.format(yearAgo))
                        }

                        val startKey = series.keys
                            .filter { it.startsWith(start) }
                            .maxOf { it }
                        val endKey = series.keys
                            .filter { it.startsWith(end) }
                            .minOf { it }

                        fun valueClose(key: String): Double {
                            return when (val value = series[key]) {
                                is JsonObject -> {
                                    when (val close = value["4. close"]) {
                                        is JsonPrimitive if close.isString -> close.content.toDouble()
                                        else -> throw IllegalStateException("value close unexpected type")
                                    }
                                }
                                else -> throw IllegalStateException("value unexpected type")
                            }
                        }

                        emit(
                            Ticker(
                                ticker.uppercase(),
                                LocalDate.parse(startKey),
                                valueClose(startKey),
                                LocalDate.parse(endKey),
                                valueClose(endKey),
                            )
                        )
                    }

                    delay(2000) // One request per second allowed
                }
            }
        }
    }

    data class Ticker(
        val ticker: String,
        val newDate: LocalDate,
        val newClose: Double,
        val oldDate: LocalDate,
        val oldClose: Double,
    )
}
