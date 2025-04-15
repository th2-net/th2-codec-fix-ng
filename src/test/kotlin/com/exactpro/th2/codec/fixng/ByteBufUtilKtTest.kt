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

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.charArrayOf


class ByteBufUtilKtTest {

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `calculate checksum test`(delimiter: Char) {
        val actual = Unpooled.wrappedBuffer(MSG_CORRECT.dropLast(7).replaceSoh(delimiter).toByteArray(Charsets.US_ASCII)).calculateChecksum(
            delimiter.code.toByte()
        )
        assertEquals(191, actual)
    }

    companion object {
        private const val MSG_CORRECT = "8=FIXT.1.19=29535=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=191"
    }
}