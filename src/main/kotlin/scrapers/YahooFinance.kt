package com.github.jo_makar.scrapers

import io.github.oshai.kotlinlogging.KotlinLogging

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Yahoo Finance ticker historical data
 * Implemented using the unofficial public api (a la yfinance)
 */
class YahooFinance {
    companion object {
        private val logger = KotlinLogging.logger {}

        fun scrapeTickers(tickers: List<String>): Flow<Ticker> {
            class UserAgentInterceptor(val userAgent: String): Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val origReq = chain.request()
                    val req = origReq.newBuilder()
                        .header("User-Agent", userAgent)
                        .build()
                    return chain.proceed(req)
                }
            }

            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            val client = OkHttpClient.Builder()
                .cache(Cache(File("/tmp/spelunker3-yahoofinance"), 128 * 1048576))
                .addInterceptor(UserAgentInterceptor(userAgent))
                .build()

            return flow {
                val baseUrls = listOf("https://query1.finance.yahoo.com", "https://query2.finance.yahoo.com")

                for (ticker in tickers) {
                    val url = buildString {
                        append(baseUrls.random())
                        append("/v8/finance/chart/${ticker.uppercase()}")
                        append("?interval=1d&range=1y")
                    }
                    logger.info { "url = $url" }

                    client.newCall(
                        Request.Builder().url(url).build()
                    ).execute().use { resp ->
                        val body: JsonObject = Json.decodeFromStream(resp.body.byteStream())

                        val (timestamps, closes) = let {
                            val chart = body["chart"]
                            check(chart is JsonObject)
                            val result = chart["result"]
                            check(result is JsonArray && result.size == 1)
                            val firstResult = result[0]
                            check(firstResult is JsonObject)

                            val timestamps = firstResult["timestamp"]
                            check(timestamps is JsonArray)
                            check(timestamps.size > 1)

                            val indicators = firstResult["indicators"]
                            check(indicators is JsonObject)
                            val quote = indicators["quote"]
                            check(quote is JsonArray && quote.size == 1)
                            val firstQuote = quote[0]
                            check(firstQuote is JsonObject)
                            val closes = firstQuote["close"]
                            check(closes is JsonArray)

                            check(timestamps.size == closes.size)

                            Pair(timestamps, closes)
                        }

                        fun localDate(e: JsonElement): LocalDate {
                            check(e is JsonPrimitive)
                            val f: JsonPrimitive = e
                            val timestamp = when {
                                f.intOrNull != null -> f.int.toLong()
                                f.longOrNull != null -> f.long
                                else -> throw IllegalStateException("unexpected timestamp type")
                            }

                            return Instant.ofEpochSecond(timestamp)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        }

                        val oldDate = localDate(timestamps[0])
                        val midDate = localDate(timestamps[timestamps.size / 2])
                        val newDate = localDate(timestamps[timestamps.size - 1])
                        check(oldDate.isBefore(newDate))

                        fun close(e: JsonElement): Double {
                            check(e is JsonPrimitive)
                            val f: JsonPrimitive = e
                            return when {
                                f.floatOrNull != null -> f.float.toDouble()
                                f.doubleOrNull != null -> f.double
                                else -> throw IllegalStateException("unexpected close type")
                            }
                        }

                        emit(
                            Ticker(
                                ticker.uppercase(),
                                newDate,
                                close(closes[closes.size - 1]),
                                midDate,
                                close(closes[closes.size / 2]),
                                oldDate,
                                close(closes[0]),
                            )
                        )

                        if (resp.cacheResponse == null) {
                            delay(2000)
                        }
                    }
                }
            }
        }
    }

    data class Ticker(
        val ticker: String,
        val newDate: LocalDate,
        val newClose: Double,
        val midDate: LocalDate,
        val midClose: Double,
        val oldDate: LocalDate,
        val oldClose: Double,
    )
}
