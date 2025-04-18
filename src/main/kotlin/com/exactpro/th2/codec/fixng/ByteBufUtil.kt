/*
 * Copyright 2023-2025 Exactpro (Exactpro Systems Limited)
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

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import java.nio.charset.Charset
import kotlin.math.min

const val SEP_BYTE = '='.code.toByte()
const val DIGIT_0 = '0'.code.toByte()
const val DIGIT_9 = '9'.code.toByte()

const val SOH_CHAR = ''
const val SOH_BYTE = SOH_CHAR.code.toByte()

@Suppress("KotlinConstantConditions")
private fun Int.getDigitCount(): Int = when {
    this < 10 -> 1
    this < 100 -> 2
    this < 1000 -> 3
    this < 10000 -> 4
    this < 100000 -> 5
    this < 1000000 -> 6
    this < 10000000 -> 7
    this < 100000000 -> 8
    this < 1000000000 -> 9
    else -> 10
}

private fun ByteBuf.printInt(sourceValue: Int, digits: Int = sourceValue.getDigitCount()): ByteBuf = apply {
    var value = sourceValue

    ensureWritable(digits)

    repeat(digits) { index ->
        setByte(digits - index - 1 + writerIndex(), value % 10 + DIGIT_0)
        value /= 10
    }

    writerIndex(writerIndex() + digits)
}

inline fun ByteBuf.readTag(warningHandler: (tag: Int, warning: String) -> Unit): Int {
    val offset = readerIndex()
    var tag = 0
    var zeroPrefix = false

    while (isReadable) {
        val byte = readByte()

        if (byte in DIGIT_0..DIGIT_9) {
            tag = tag * 10 + byte - DIGIT_0

            zeroPrefix = zeroPrefix || tag == 0
            if (tag >= 0) continue
        }

        return if (byte == SEP_BYTE && tag != 0) {
            if (zeroPrefix) warningHandler(tag, "Tag with zero prefix at offset: $offset, raw: '${prettyString(offset)}'")
            tag
        } else {
            break
        }
    }

    error("No valid tag at offset: $offset '${prettyString(offset)}'")
}

fun ByteBuf.prettyString(start: Int, length: Int = 10): String = buildString {
    if (start > 0) {
        append("...")
    }
    val remained = writerIndex() - start
    append(String(ByteBufUtil.getBytes(this@prettyString, start, min(remained, length))))
    if (remained > length) {
        append("...")
    }
}

fun ByteBuf.writeTag(tag: Int): ByteBuf {
    require(tag > 0) { "Tag cannot be negative" }
    return printInt(tag).writeByte(SEP_BYTE.toInt())
}

fun ByteBuf.readValue(delimiter: Byte, charset: Charset, isDirty: Boolean): String {
    val offset = readerIndex()
    val length = bytesBefore(delimiter)
    check(isDirty || length > 0) { "No valid value at offset: $offset, raw: '${prettyString(offset)}'" }
    readerIndex(offset + length + 1)
    return toString(offset, length, charset)
}

fun ByteBuf.writeValue(value: String, delimiter: Byte, charset: Charset): ByteBuf = apply {
    writeCharSequence(value, charset)
    writeByte(delimiter.toInt())
}

inline fun ByteBuf.forEachField(
    delimiter: Byte,
    charset: Charset,
    isDirty: Boolean,
    warningHandler: (tag: Int, warning: String) -> Unit,
    action: (tag: Int, value: String) -> Boolean,
) {
    while (isReadable) {
        val offset = readerIndex()
        if (action(readTag(warningHandler), readValue(delimiter, charset, isDirty))) continue
        readerIndex(offset)
        break
    }
}

inline fun ByteBuf.readField(
    tag: Int,
    delimiter: Byte,
    charset: Charset,
    isDirty: Boolean,
    warningHandler: (tag: Int, warning: String) -> Unit,
    message: (Int) -> String,
): String = readTag(warningHandler).let {
    check(it == tag) { message(it) }
    readValue(delimiter, charset, isDirty)
}

fun ByteBuf.writeField(tag: Int, value: String, delimiter: Byte, charset: Charset): ByteBuf =
    writeTag(tag).writeValue(value, delimiter, charset)

fun ByteBuf.writeField(tag: Int, value: Any?, delimiter: Byte, charset: Charset): ByteBuf =
    writeField(tag, value.toString(), delimiter, charset)

fun ByteBuf.writeChecksum(delimiter: Byte) {
    val checksum = calculateChecksum(delimiter)
    writeTag(10).printInt(checksum, 3).writeByte(delimiter.toInt())
}

fun ByteBuf.calculateChecksum(delimiter: Byte): Int {
    val index = readerIndex()
    var checksum = 0
    while (isReadable) {
        val value = readByte()
        checksum += if (value == delimiter) SOH_BYTE else value
    }
    readerIndex(index)
    return checksum % 256
}

fun ByteBuf.getLastTagIndex(delimiter: Byte): Int {
    var maxNotDelimiterIndex = writerIndex() - 1
    if (getByte(maxNotDelimiterIndex) == delimiter) {
        maxNotDelimiterIndex -= 1
    }
    return indexOf(maxNotDelimiterIndex, 0, delimiter) + 1
}