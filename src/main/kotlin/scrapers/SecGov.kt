package com.github.jo_makar.scrapers

import io.github.oshai.kotlinlogging.KotlinLogging

import java.io.File
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory

import kotlin.math.ceil
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow

import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList

/**
 * Security and Exchange Commission (SEC) form filings
 *
 * Notably, form 4 is filed by company insiders (directors, officers or 10%+ shareholders)
 * to report any change in the ownership of the company's equity securities
 * and must be filed shortly (two business days) following the transaction
 */
class SecGov {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()

        fun scrapeForm4(startDate: LocalDate, endDate: LocalDate): Flow<Form4> {
            fun LocalDate.quarter(): Int = ceil(monthValue.toDouble() / 3).toInt()

            fun NodeList.asList(): List<Node> = buildList {
                for (i in 0..<length) {
                    add(item(i))
                }
            }

            class UserAgentInterceptor(val userAgent: String): Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val origReq = chain.request()
                    val req = origReq.newBuilder()
                        .header("User-Agent", userAgent)
                        .build()
                    return chain.proceed(req)
                }
            }

            val client = OkHttpClient.Builder()
                .cache(Cache(File("/tmp/spelunker3-secgov"), 500 * 1048576))
                // Apparently specific user agents formats are whitelisted
                // Refs: https://stackoverflow.com/a/77766686
                //       https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data
                .addInterceptor(UserAgentInterceptor("Mozilla/5.0 (Company info@company.com)"))
                .build()

            return flow {
                for (date in startDate.datesUntil(endDate)) {
                    if (date.dayOfWeek in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) {
                        continue
                    }

                    val indexUrl = buildString {
                        append("https://www.sec.gov/Archives/edgar/daily-index")
                        append("/${date.year}/QTR${date.quarter()}")
                        append("/form.${date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))}.idx")
                    }
                    logger.info { "indexUrl = $indexUrl" }

                    client.newCall(
                        Request.Builder().url(indexUrl).build()
                    ).execute().use { resp ->
                        var state = 0  // 0: header, 1: body
                        for (line in resp.body.byteStream().bufferedReader().lines()) {
                            if (state == 0) {
                                if (line.startsWith("-".repeat(100))) {
                                    state = 1
                                }
                            } else /* if (state == 1) */ {
                                val tokens = line.split(" {2,}".toRegex())
                                if (tokens[0] != "4") {
                                    continue
                                }

                                val filingUrl = "https://www.sec.gov/Archives/" + tokens[tokens.size - 2]
                                logger.info { "filingUrl = $filingUrl" }
                                client.newCall(
                                    Request.Builder().url(filingUrl).build()
                                ).execute().use { resp ->
                                    val xml = run {
                                        val origXml = resp.body.string()
                                        val startIdx = origXml.indexOf("<?xml")
                                        val endIdx = origXml.indexOf("</ownershipDocument>")
                                        if (startIdx == -1 || endIdx == -1) {
                                            throw IllegalStateException("invalid xml")
                                        }
                                        origXml.substring(startIdx, endIdx + 20)
                                    }

                                    builder.reset()
                                    val doc = builder.parse(xml.byteInputStream())

                                    val ticker = doc.getElementsByTagName("issuerTradingSymbol")
                                        .item(0)
                                        .textContent

                                    var value = 0.0
                                    for (node in (
                                            doc.getElementsByTagName("nonDerivativeTransaction").asList() +
                                            doc.getElementsByTagName("derivativeTransaction").asList()
                                    )) {
                                        val element = node as Element

                                        val shares = run {
                                            val nodeList = element.getElementsByTagName("transactionShares")
                                            when {
                                                nodeList.length == 0 -> 0.0
                                                else -> nodeList.item(0).textContent.toDouble()
                                            }
                                        }
                                        val price = run {
                                            val text = element.getElementsByTagName("transactionPricePerShare")
                                                .item(0)
                                                .textContent
                                                .trim()
                                            when {
                                                text.isNotEmpty() -> text.toDouble()
                                                else -> 0.0
                                            }
                                        }
                                        val factor = when (
                                            val code = element.getElementsByTagName("transactionAcquiredDisposedCode")
                                                .item(0)
                                                .textContent
                                                .trim()
                                        ) {
                                            "A" -> 1
                                            "D" -> -1
                                            else -> throw IllegalStateException("invalid code: $code")
                                        }

                                        value += shares * price * factor
                                    }

                                    emit(Form4(date, filingUrl, ticker, value))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    data class Form4(
        val date: LocalDate,
        val url: String,
        val ticker: String,
        val value: Double
    ): Comparable<Form4> {
        override fun compareTo(other: Form4): Int = value.compareTo(other.value)
    }
}
