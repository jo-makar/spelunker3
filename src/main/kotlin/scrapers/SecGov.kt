package com.github.jo_makar.scrapers

import java.time.LocalDate

import kotlin.math.ceil
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow

/**
 * Security and Exchange Commission (SEC) form filings
 *
 * Notably, form 4 is filed by company insiders (directors, officers or 10%+ shareholders)
 * to report any change in the ownership of the company's equity securities
 * and must be filed shortly (two business days) following the transaction
 */
class SecGov {
    companion object {
        fun scrapeForm4(startDate: LocalDate, endDate: LocalDate): Flow<Form4> {
            fun LocalDate.quarter(): Int = ceil(monthValue.toDouble() / 3).toInt()

            return flow {
                for (date in startDate.datesUntil(endDate)) {
                    // FIXME STOPPED download https://www.sec.gov/Archives/edgar/daily-index/2026/QTR1/form.20260201.idx
                    //               where the download func checks a local cache and returns an outputstream?
                    //               add appropriate entry to .gitignore
                    //               add `mkdir -p cache/SecGov/` and possibly a cleanup find cmd (files older than 10 days) to the just recipe
                    //               ultimately will need to emit(Form4(...)) here
                }
            }
        }
    }

    data class Form4(
        val date: LocalDate
    )
}