/*
 * Copyright 2025 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exactpro.th2.codec.fixng

import com.exactpro.sf.comparison.conversion.MultiConverter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.time.temporal.TemporalAccessor
import kotlin.reflect.KClass

class Formatter<T: TemporalAccessor>(
    fixFormat: String,
    isoFormat: String,
    minWidth: Int,
    maxWidth: Int,
    private val clazz: KClass<T>,
    private val parser: (String, DateTimeFormatter) -> T,
) {
    private val fixFormatter = DateTimeFormatterBuilder()
        .appendPattern(fixFormat).apply {
            if (maxWidth > 0) appendFraction(ChronoField.NANO_OF_SECOND, minWidth, maxWidth, true)
        }.toFormatter()

    private val isoFormatter = DateTimeFormatterBuilder()
        .appendPattern(isoFormat).apply {
            if (maxWidth > 0) appendFraction(ChronoField.NANO_OF_SECOND, minWidth, maxWidth, true)
        }.toFormatter()


    fun formatFix(isoValue: String): String = formatFix(MultiConverter.convert(isoValue, clazz.java))

    fun formatFix(value: T): String = fixFormatter.format(value)

    fun formatIso(value: T): String = isoFormatter.format(value)

    fun parseFix(isoValue: String): T = parser.invoke(isoValue, fixFormatter)
}

private const val ISO_DATE_FORMAT = "yyyy-MM-dd"
private const val ISO_TIME_FORMAT = "HH:mm:ss"
private const val ISO_DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss"

private const val FIX_DATE_FORMAT = "yyyyMMdd"
private const val FIX_TIME_FORMAT = "HH:mm:ss"
private const val FIX_DATE_TIME_FORMAT = "yyyyMMdd-HH:mm:ss"

val DATE = Formatter(FIX_DATE_FORMAT, ISO_DATE_FORMAT, 0, 0, LocalDate::class, LocalDate::parse)
val TIME_AUTO = Formatter(FIX_TIME_FORMAT, ISO_TIME_FORMAT, 0, 9, LocalTime::class, LocalTime::parse)
val TIME_SECONDS = Formatter(FIX_TIME_FORMAT, ISO_TIME_FORMAT, 0, 0, LocalTime::class, LocalTime::parse)
val TIME_MILLISECONDS = Formatter(FIX_TIME_FORMAT, ISO_TIME_FORMAT, 3, 3, LocalTime::class, LocalTime::parse)
val TIME_MICROSECONDS = Formatter(FIX_TIME_FORMAT, ISO_TIME_FORMAT, 6, 6, LocalTime::class, LocalTime::parse)
val TIME_NANOSECONDS = Formatter(FIX_TIME_FORMAT, ISO_TIME_FORMAT, 9, 9, LocalTime::class, LocalTime::parse)
val DATE_TIME_AUTO = Formatter(FIX_DATE_TIME_FORMAT, ISO_DATE_TIME_FORMAT, 0, 9, LocalDateTime::class, LocalDateTime::parse)
val DATE_TIME_SECONDS = Formatter(FIX_DATE_TIME_FORMAT, ISO_DATE_TIME_FORMAT, 0, 0, LocalDateTime::class, LocalDateTime::parse)
val DATE_TIME_MILLISECONDS = Formatter(FIX_DATE_TIME_FORMAT, ISO_DATE_TIME_FORMAT, 3, 3, LocalDateTime::class, LocalDateTime::parse)
val DATE_TIME_MICROSECONDS = Formatter(FIX_DATE_TIME_FORMAT, ISO_DATE_TIME_FORMAT, 6, 6, LocalDateTime::class, LocalDateTime::parse)
val DATE_TIME_NANOSECONDS = Formatter(FIX_DATE_TIME_FORMAT, ISO_DATE_TIME_FORMAT, 9, 9, LocalDateTime::class, LocalDateTime::parse)

internal inline fun <reified T> checkAndFormatFix(isoValue: String): String {
    val length = lengthWithoutZ(isoValue)
    return when(T::class) {
        LocalDateTime::class -> when (length) {
            in 27..29 -> DATE_TIME_NANOSECONDS.formatFix(isoValue)
            in 24..26 -> DATE_TIME_MICROSECONDS.formatFix(isoValue)
            in 21..23 -> DATE_TIME_MILLISECONDS.formatFix(isoValue)
            19 -> DATE_TIME_SECONDS.formatFix(isoValue)
            else -> DATE_TIME_AUTO.formatFix(isoValue)
        }
        LocalTime::class -> when (length) {
            in 16 .. 18 -> TIME_NANOSECONDS.formatFix(isoValue)
            in 13 .. 15 -> TIME_MICROSECONDS.formatFix(isoValue)
            in 10 .. 12 -> TIME_MILLISECONDS.formatFix(isoValue)
            8 -> TIME_SECONDS.formatFix(isoValue)
            else -> TIME_AUTO.formatFix(isoValue)
        }
        LocalDate::class -> DATE.formatFix(isoValue)
        else -> error("unsupported ${T::class.java} type for formatting")
    }
}

internal fun <T: TemporalAccessor> formatIso(origValue: String, value: T): String {
    return when(value) {
        is LocalDateTime -> when (origValue.length) {
            in 25..27 -> DATE_TIME_NANOSECONDS.formatIso(value)
            in 22..24 -> DATE_TIME_MICROSECONDS.formatIso(value)
            in 19..21 -> DATE_TIME_MILLISECONDS.formatIso(value)
            17 -> DATE_TIME_SECONDS.formatIso(value)
            else -> DATE_TIME_AUTO.formatIso(value)
        }
        is LocalTime -> when (origValue.length) {
            in 16 .. 18 -> TIME_NANOSECONDS.formatIso(value)
            in 13 .. 15 -> TIME_MICROSECONDS.formatIso(value)
            in 10 .. 12 -> TIME_MILLISECONDS.formatIso(value)
            8 -> TIME_SECONDS.formatIso(value)
            else -> TIME_AUTO.formatIso(value)
        }
        is LocalDate -> DATE.formatIso(value)
        else -> error("unsupported ${value::class.java} type for formatting")
    }
}

internal fun lengthWithoutZ(isoValue: String): Int = if (isoValue.endsWith("Z")) isoValue.length - 1 else isoValue.length
