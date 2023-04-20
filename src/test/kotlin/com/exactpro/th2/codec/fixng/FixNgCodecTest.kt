/*
 * Copyright 2023 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.sf.common.messages.structures.IDictionaryStructure
import com.exactpro.sf.common.messages.structures.loaders.XmlDictionaryStructureLoader
import com.exactpro.th2.codec.fixng.FixNgCodec.Companion.toMessages
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageGroup
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.ParsedMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class FixNgCodecTest {

    private val dictionary: IDictionaryStructure =
        FixNgCodecTest::class.java.classLoader.getResourceAsStream("dictionary.xml")
            .use(XmlDictionaryStructureLoader()::load)
    private val codec = FixNgCodec(dictionary.toMessages(), FixNgCodecSettings(dictionary = ""))
    @Test
    fun `simple test decode encode`() {
        listOf(
            RawMessage(body = Unpooled.wrappedBuffer("8=FIXT.1.1\u00019=156\u000135=8\u000134=10947\u000149=SENDER\u000152=20230419-10:36:07.415088\u000156=RECEIVER\u00011=test\u000111=zSuNbrBIZyVljs\u000138=500\u000139=0\u000140=A\u000141=zSuNbrBIZyVljs\u000144=1000\u000147=500\u000154=B\u000155=ABC\u000159=M\u000110=012\u0001".toByteArray())),
            RawMessage(body = Unpooled.wrappedBuffer("8=FIXT.1.1\u00019=133\u000135=D\u000134=11005\u000149=SENDER\u000152=20230419-10:36:07.415088\u000156=RECEIVER\u00011=test\u000111=UsVpSVQIcuqjQe\u000138=500\u000140=A\u000144=1000\u000147=500\u000154=B\u000155=ABC\u000159=M\u000110=000\u0001".toByteArray())),
        ).forEach { source ->
            codec.decode(MessageGroup(mutableListOf(source))).also { group ->
                assertEquals(1, group.messages.size)
            }.messages.first().also { decoded ->
                assertTrue(decoded is ParsedMessage)
//                TODO: uncomment when encode will be done
//                codec.encode(MessageGroup(mutableListOf(decoded))).also { group ->
//                    assertEquals(1, group.messages.size)
//                }.messages.first().also { encoded ->
//                    assertTrue(encoded is RawMessage)
//                    assertEquals(source, encoded)
//                }
            }
        }
    }

    @Test
    fun `tag appears out of order`() {
        val tag = 999
        assertThrows<IllegalStateException> {
            codec.decode(MessageGroup(mutableListOf(RawMessage(body = Unpooled.wrappedBuffer("8=FIXT.1.1\u00019=156\u000135=8\u000134=10947\u000149=SENDER\u000152=20230419-10:36:07.415088\u000156=RECEIVER\u0001$tag=500\u000110=012\u0001".toByteArray())))))
        }.also { ex ->
            assertEquals("Tag appears out of order: $tag", ex.message)
        }
    }
}