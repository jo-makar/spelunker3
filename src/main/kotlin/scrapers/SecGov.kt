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
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

        @JvmName("scrapeForms4WithNullableCik")
        fun scrapeForms4(startDate: LocalDate, endDate: LocalDate, cik: String?): Flow<Form4> =
            if (cik == null) {
                scrapeForms4(startDate, endDate)
            } else {
                scrapeForms4(startDate, endDate, cik)
            }

        fun scrapeForms4(startDate: LocalDate, endDate: LocalDate): Flow<Form4> {
            fun LocalDate.quarter(): Int = ceil(monthValue.toDouble() / 3).toInt()

            val client = buildClient()

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
                                    emit(parseFiling(resp.body.string(), filingUrl, date))
                                }
                            }
                        }
                    }
                }
            }
        }

        @JvmName("scrapeForms4WithNonNullCik")
        fun scrapeForms4(startDate: LocalDate, endDate: LocalDate, cik: String): Flow<Form4> {
            return flow {
                val client = buildClient()

                val cikUrl = "https://data.sec.gov/submissions/CIK$cik.json"
                logger.info { "cikUrl = $cikUrl" }

                client.newCall(
                    Request.Builder().url(cikUrl).build()
                ).execute().use { resp ->
                    val body: JsonObject = Json.decodeFromStream(resp.body.byteStream())

                    val (dates, forms, accessions) = run {
                        val recentFilings = run {
                            val filings = body["filings"]
                            check(filings is JsonObject)
                            val recent = filings["recent"]
                            check(recent is JsonObject)
                            recent
                        }

                        val recentFilingDates = recentFilings["filingDate"]
                        check(recentFilingDates is JsonArray)
                        val recentFilingForms = recentFilings["form"]
                        check(recentFilingForms is JsonArray)
                        val recentFilingAccessions = recentFilings["accessionNumber"]
                        check(recentFilingAccessions is JsonArray)

                        check(recentFilingDates.size == recentFilingForms.size)
                        check(recentFilingDates.size == recentFilingAccessions.size)

                        Triple(recentFilingDates, recentFilingForms, recentFilingAccessions)
                    }

                    fun localDate(e: JsonElement): LocalDate {
                        check(e is JsonPrimitive)
                        val f: JsonPrimitive = e
                        return LocalDate.parse(f.content)
                    }

                    fun string(e: JsonElement): String {
                        check(e is JsonPrimitive)
                        val f: JsonPrimitive = e
                        return f.content
                    }

                    for (idx in 0 until dates.size) {
                        val date = localDate(dates[idx])
                        if (date.isBefore(startDate)) {
                            continue
                        }
                        if (date.isEqual(endDate) || date.isAfter(endDate)) {
                            break
                        }

                        if (string(forms[idx]) != "4") {
                            continue
                        }

                        val accession = string(accessions[idx])
                        val filingUrl = run {
                            val trimmedCik = cik.trimStart('0')
                            val dirname = accession.replace("-", "")
                            "https://www.sec.gov/Archives/edgar/data/$trimmedCik/$dirname/$accession.txt"
                        }
                        logger.info { "filingUrl = $filingUrl" }

                        client.newCall(
                            Request.Builder().url(filingUrl).build()
                        ).execute().use { resp ->
                            emit(parseFiling(resp.body.string(), filingUrl, date))
                        }
                    }
                }
            }
        }

        private fun buildClient(): OkHttpClient {
            class UserAgentInterceptor(val userAgent: String): Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val origReq = chain.request()
                    val req = origReq.newBuilder()
                        .header("User-Agent", userAgent)
                        .build()
                    return chain.proceed(req)
                }
            }

            return OkHttpClient.Builder()
                .cache(Cache(File("/tmp/spelunker3-secgov"), 1024 * 1048576))
                // Apparently specific user agents formats are whitelisted
                // Refs: https://stackoverflow.com/a/77766686
                //       https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data
                .addInterceptor(UserAgentInterceptor("Mozilla/5.0 (Company info@company.com)"))
                .build()
        }

        private fun parseFiling(body: String, url: String, date: LocalDate): Form4 {
            fun NodeList.asList(): List<Node> = buildList {
                for (i in 0..<length) {
                    add(item(i))
                }
            }

            val xml = run {
                val startIdx = body.indexOf("<?xml")
                val endIdx = body.indexOf("</ownershipDocument>")
                if (startIdx == -1 || endIdx == -1) {
                    throw IllegalStateException("invalid xml")
                }
                body.substring(startIdx, endIdx + 20)
            }

            builder.reset()
            val doc = builder.parse(xml.byteInputStream())

            val ticker = doc.getElementsByTagName("issuerTradingSymbol")
                .item(0)
                .textContent

            val cik = doc.getElementsByTagName("issuerCik")
                .item(0)
                .textContent

            val ownerTitle = doc.getElementsByTagName("officerTitle").asList()
                .map { it.textContent.trim() }
                .filter { it.isNotEmpty() }
                .joinToString()

            val has10b51 = doc.documentElement.textContent.contains("10b5-1", true)

            var value = 0.0
            for (node in (
                    doc.getElementsByTagName("nonDerivativeTransaction").asList() +
                            doc.getElementsByTagName("derivativeTransaction").asList()
                    )) {
                val element = node as Element

                val count = run {
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

                value += count * price * factor
            }

            return Form4(
                date,
                url,
                url.let {
                    val baseUrl = it.substringBeforeLast("/")
                    val tail = it
                        .substringAfterLast("/")
                        .substringBefore(".txt")
                    val tailNoHyphens = tail.replace("-", "")

                    "$baseUrl/$tailNoHyphens/$tail-index.html"
                },
                ticker,
                cik,
                value,
                ownerTitle,
                has10b51,
            )
        }
    }

    data class Form4(
        val date: LocalDate,
        val textUrl: String,
        val filingDetailUrl: String,
        val ticker: String,
        val cik: String,
        val value: Double,
        val ownerTitle: String?,
        val has10b51: Boolean,
    ): Comparable<Form4> {
        override fun compareTo(other: Form4): Int = value.compareTo(other.value)
    }
}
