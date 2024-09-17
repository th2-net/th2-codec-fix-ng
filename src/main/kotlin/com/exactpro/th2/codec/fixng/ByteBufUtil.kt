/*
 * Copyright 2023-2024 Exactpro (Exactpro Systems Limited)
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
import java.nio.charset.Charset

private const val SEP_BYTE = '='.code.toByte()
private const val SOH_BYTE = 1.toByte()
private const val DIGIT_0 = '0'.code.toByte()
private const val DIGIT_9 = '9'.code.toByte()

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

fun ByteBuf.readTag(): Int {
    val offset = readerIndex()
    var tag = 0

    while (isReadable) {
        val byte = readByte()

        if (byte in DIGIT_0..DIGIT_9) {
            tag = tag * 10 + byte - DIGIT_0
            if (tag >= 0) continue
        }

        return if (byte == SEP_BYTE && tag != 0) tag else break
    }

    error("No valid tag at offset: $offset")
}

fun ByteBuf.writeTag(tag: Int): ByteBuf {
    require(tag > 0) { "Tag cannot be negative" }
    return printInt(tag).writeByte(SEP_BYTE.toInt())
}

fun ByteBuf.readValue(charset: Charset, isDirty: Boolean): String {
    val offset = readerIndex()
    val length = bytesBefore(SOH_BYTE)
    check(isDirty || length > 0) { "No valid value at offset: $offset" }
    readerIndex(offset + length + 1)
    return toString(offset, length, charset)
}

fun ByteBuf.writeValue(value: String, charset: Charset): ByteBuf = apply {
    writeCharSequence(value, charset)
    writeByte(SOH_BYTE.toInt())
}

inline fun ByteBuf.forEachField(
    charset: Charset,
    isDirty: Boolean,
    action: (tag: Int, value: String) -> Boolean,
) {
    while (isReadable) {
        val offset = readerIndex()
        if (action(readTag(), readValue(charset, isDirty))) continue
        readerIndex(offset)
        break
    }
}

inline fun ByteBuf.readField(tag: Int, charset: Charset, isDirty: Boolean, message: (Int) -> String): String = readTag().let {
    check(it == tag) { message(it) }
    readValue(charset, isDirty)
}

fun ByteBuf.writeField(tag: Int, value: String, charset: Charset): ByteBuf = writeTag(tag).writeValue(value, charset)

fun ByteBuf.writeField(tag: Int, value: Any?, charset: Charset): ByteBuf = writeField(tag, value.toString(), charset)

fun ByteBuf.writeChecksum() {
    val index = readerIndex()
    var checksum = 0
    while (isReadable) checksum += readByte()
    readerIndex(index)
    writeTag(10).printInt(checksum % 256, 3).writeByte(SOH_BYTE.toInt())
}