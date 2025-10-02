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

class Formatter1<T: TemporalAccessor>(
    format: String,
    minWidth: Int,
    maxWidth: Int,
    private val clazz: KClass<T>,
    private val parser: (String, DateTimeFormatter) -> T,
) {
    private val formatter = DateTimeFormatterBuilder()
        .appendPattern(format).apply {
            if (maxWidth > 0) appendFraction(ChronoField.NANO_OF_SECOND, minWidth, maxWidth, true)
        }.toFormatter()


    fun format(isoValue: String): String = format(MultiConverter.convert(isoValue, clazz.java))

    fun format(value: T): String = formatter.format(value)

    fun parse(isoValue: String): T = parser.invoke(isoValue, formatter)
}

private const val DATE_FORMAT = "yyyyMMdd"
private const val TIME_FORMAT = "HH:mm:ss"
private const val DATE_TIME_FORMAT = "yyyyMMdd-HH:mm:ss"

val DATE = Formatter1(DATE_FORMAT, 0, 0, LocalDate::class, LocalDate::parse)
val TIME_AUTO = Formatter1(TIME_FORMAT, 0, 9, LocalTime::class, LocalTime::parse)
val TIME_SECONDS = Formatter1(TIME_FORMAT, 0, 0, LocalTime::class, LocalTime::parse)
val TIME_MILLISECONDS = Formatter1(TIME_FORMAT, 3, 3, LocalTime::class, LocalTime::parse)
val TIME_MICROSECONDS = Formatter1(TIME_FORMAT, 6, 6, LocalTime::class, LocalTime::parse)
val TIME_NANOSECONDS = Formatter1(TIME_FORMAT, 9, 9, LocalTime::class, LocalTime::parse)
val DATE_TIME_AUTO = Formatter1(DATE_TIME_FORMAT, 0, 9, LocalDateTime::class, LocalDateTime::parse)
val DATE_TIME_SECONDS = Formatter1(DATE_TIME_FORMAT, 0, 0, LocalDateTime::class, LocalDateTime::parse)
val DATE_TIME_MILLISECONDS = Formatter1(DATE_TIME_FORMAT, 3, 3, LocalDateTime::class, LocalDateTime::parse)
val DATE_TIME_MICROSECONDS = Formatter1(DATE_TIME_FORMAT, 6, 6, LocalDateTime::class, LocalDateTime::parse)
val DATE_TIME_NANOSECONDS = Formatter1(DATE_TIME_FORMAT, 9, 9, LocalDateTime::class, LocalDateTime::parse)

internal inline fun <reified T> checkAndFormat(isoValue: String): String {
    val length = lengthWithoutZ(isoValue)
    return when(T::class) {
        LocalDateTime::class -> when (length) {
            in 27..29 -> DATE_TIME_NANOSECONDS.format(isoValue)
            in 24..26 -> DATE_TIME_MICROSECONDS.format(isoValue)
            in 21..23 -> DATE_TIME_MILLISECONDS.format(isoValue)
            19 -> DATE_TIME_SECONDS.format(isoValue)
            else -> DATE_TIME_AUTO.format(isoValue)
        }
        LocalTime::class -> when (length) {
            in 16 .. 18 -> TIME_NANOSECONDS.format(isoValue)
            in 13 .. 15 -> TIME_MICROSECONDS.format(isoValue)
            in 10 .. 12 -> TIME_MILLISECONDS.format(isoValue)
            8 -> TIME_SECONDS.format(isoValue)
            else -> TIME_AUTO.format(isoValue)
        }
        LocalDate::class -> DATE.format(isoValue)
        else -> error("unsupported ${T::class.java} type for formatting")
    }
}

internal fun lengthWithoutZ(isoValue: String): Int = if (isoValue.endsWith("Z")) isoValue.length - 1 else isoValue.length
