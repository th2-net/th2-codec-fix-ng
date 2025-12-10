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

import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.assertEquals

class TimeUtilTest {

    @ParameterizedTest
    @MethodSource("check-and-format-date-time")
    fun `check and format date time`(isoValue: String, expected: String) {
        assertEquals(expected, checkAndFormatFix<LocalDateTime>(isoValue))
    }

    @ParameterizedTest
    @MethodSource("check-and-format-date-time")
    fun `check and format date time with Z`(isoValue: String, expected: String) {
        assertEquals(expected, checkAndFormatFix<LocalDateTime>("${isoValue}Z"))
    }

    @ParameterizedTest
    @MethodSource("check-and-format-date-time-pico")
    fun `check and format date time with picoseconds`(isoValue: String) {
        assertThrows<DateTimeException> {
            checkAndFormatFix<LocalDateTime>(isoValue)
        }.also {
            assertEquals("Text '$isoValue' could not be parsed, unparsed text found at index 29", it.message)
        }
    }

    @ParameterizedTest
    @MethodSource("check-and-format-date-time-pico")
    fun `check and format date time with picoseconds and Z`(isoValue: String) {
        assertThrows<DateTimeException> {
            checkAndFormatFix<LocalDateTime>("${isoValue}Z")
        }.also {
            assertEquals("Text '${isoValue}Z' could not be parsed, unparsed text found at index 29", it.message)
        }
    }

    @ParameterizedTest
    @MethodSource("check-and-format-time")
    fun `check and format time`(isoValue: String, expected: String) {
        assertEquals(expected, checkAndFormatFix<LocalTime>(isoValue))
    }

    @ParameterizedTest
    @MethodSource("check-and-format-time")
    fun `check and format time with Z`(isoValue: String, expected: String) {
        assertEquals(expected, checkAndFormatFix<LocalTime>("${isoValue}Z"))
    }

    @ParameterizedTest
    @MethodSource("check-and-format-time-pico")
    fun `check and format time with picoseconds`(isoValue: String) {
        assertThrows<DateTimeException> {
            checkAndFormatFix<LocalTime>(isoValue)
        }.also {
            assertEquals("Text '$isoValue' could not be parsed, unparsed text found at index 18", it.message)
        }
    }

    @ParameterizedTest
    @MethodSource("check-and-format-time-pico")
    fun `check and format time with picoseconds and Z`(isoValue: String) {
        assertThrows<DateTimeException> {
            checkAndFormatFix<LocalTime>("${isoValue}Z")
        }.also {
            assertEquals("Text '${isoValue}Z' could not be parsed, unparsed text found at index 18", it.message)
        }
    }

    @ParameterizedTest
    @MethodSource("check-and-format-date")
    fun `check and format date`(isoValue: String, expected: String) {
        assertEquals(expected, checkAndFormatFix<LocalDate>(isoValue))
    }

    @ParameterizedTest
    @MethodSource("check-and-format-date")
    fun `check and format date with Z`(isoValue: String, expected: String) {
        assertEquals(expected, checkAndFormatFix<LocalDate>("${isoValue}Z"))
    }

    companion object {
        @JvmStatic
        fun `check-and-format-date-time`() = listOf(
            Arguments.of("2025-09-29T14:07:53.123456789", "20250929-14:07:53.123456789"),
            Arguments.of("2025-09-29T14:07:53.123456780", "20250929-14:07:53.123456780"),
            Arguments.of("2025-09-29T14:07:53.123456700", "20250929-14:07:53.123456700"),
            Arguments.of("2025-09-29T14:07:53.123456000", "20250929-14:07:53.123456000"),
            Arguments.of("2025-09-29T14:07:53.123450000", "20250929-14:07:53.123450000"),
            Arguments.of("2025-09-29T14:07:53.123400000", "20250929-14:07:53.123400000"),
            Arguments.of("2025-09-29T14:07:53.123000000", "20250929-14:07:53.123000000"),
            Arguments.of("2025-09-29T14:07:53.120000000", "20250929-14:07:53.120000000"),
            Arguments.of("2025-09-29T14:07:53.100000000", "20250929-14:07:53.100000000"),
            Arguments.of("2025-09-29T14:07:53.000000000", "20250929-14:07:53.000000000"),

            Arguments.of("2025-09-29T14:07:53.12345", "20250929-14:07:53.123450"),
            Arguments.of("2025-09-29T14:07:53.1234", "20250929-14:07:53.123400"),

            Arguments.of("2025-09-29T14:07:53.12", "20250929-14:07:53.120"),
            Arguments.of("2025-09-29T14:07:53.1", "20250929-14:07:53.100"),

            Arguments.of("2025-09-29T14:07:53", "20250929-14:07:53"),
        )

        @JvmStatic
        fun `check-and-format-date-time-pico`() = listOf(
            Arguments.of("2025-09-29T14:07:53.123456789123"),
            Arguments.of("2025-09-29T14:07:53.12345678912"),
            Arguments.of("2025-09-29T14:07:53.1234567891"),
        )

        @JvmStatic
        fun `check-and-format-time`() = listOf(
            Arguments.of("14:07:53.123456789", "14:07:53.123456789"),
            Arguments.of("14:07:53.123456780", "14:07:53.123456780"),
            Arguments.of("14:07:53.123456700", "14:07:53.123456700"),
            Arguments.of("14:07:53.123456000", "14:07:53.123456000"),
            Arguments.of("14:07:53.123450000", "14:07:53.123450000"),
            Arguments.of("14:07:53.123400000", "14:07:53.123400000"),
            Arguments.of("14:07:53.123000000", "14:07:53.123000000"),
            Arguments.of("14:07:53.120000000", "14:07:53.120000000"),
            Arguments.of("14:07:53.100000000", "14:07:53.100000000"),
            Arguments.of("14:07:53.000000000", "14:07:53.000000000"),

            Arguments.of("14:07:53.12345", "14:07:53.123450"),
            Arguments.of("14:07:53.1234", "14:07:53.123400"),

            Arguments.of("14:07:53.12", "14:07:53.120"),
            Arguments.of("14:07:53.1", "14:07:53.100"),

            Arguments.of("14:07:53", "14:07:53"),
        )

        @JvmStatic
        fun `check-and-format-time-pico`() = listOf(
            Arguments.of("14:07:53.123456789123"),
            Arguments.of("14:07:53.12345678912"),
            Arguments.of("14:07:53.1234567891"),
        )

        @JvmStatic
        fun `check-and-format-date`() = listOf(
            Arguments.of("2025-09-29", "20250929"),
        )

    }
}