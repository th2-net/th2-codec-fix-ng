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

import com.exactpro.sf.common.messages.structures.IDictionaryStructure
import com.exactpro.sf.common.messages.structures.loaders.XmlDictionaryStructureLoader
import com.exactpro.th2.codec.api.IReportingContext
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.*
import com.exactpro.th2.codec.fixng.FixNgCodecFactory.Companion.PROTOCOL
import io.netty.buffer.CompositeByteBuf
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FixNgCodecTest {
    private val dictionary: IDictionaryStructure = FixNgCodecTest::class.java.classLoader
        .getResourceAsStream("dictionary.xml")
        .use(XmlDictionaryStructureLoader()::load)

    private val codec = FixNgCodec(dictionary, FixNgCodecSettings(dictionary = ""))

    private val reportingContext = object : IReportingContext {
        private val _warnings: MutableList<String> = ArrayList()

        val warnings: List<String>
            get() = _warnings

        override fun warning(message: String) {
            _warnings.add(message)
        }

        override fun warnings(messages: Iterable<String>) {
            _warnings.addAll(messages)
        }
    }

    @Test
    fun `simple test decode encode`() {
        listOf(
            // EXECUTION_REPORT
            RawMessage(body = Unpooled.wrappedBuffer("8=FIXT.1.1\u00019=156\u000135=8\u000134=10947\u000149=SENDER\u000152=20230419-10:36:07.415088\u000156=RECEIVER\u00011=test\u000111=zSuNbrBIZyVljs\u000138=500\u000139=0\u000140=A\u000141=zSuNbrBIZyVljs\u000144=1000\u000147=500\u000154=B\u000155=ABC\u000159=0\u000110=012\u0001".toByteArray())),
            // ORDER_SINGLE
            RawMessage(body = Unpooled.wrappedBuffer("8=FIXT.1.1\u00019=133\u000135=D\u000134=11005\u000149=SENDER\u000152=20230419-10:36:07.415088\u000156=RECEIVER\u00011=test\u000111=UsVpSVQIcuqjQe\u000138=500\u000140=A\u000144=1000\u000147=500\u000154=B\u000155=ABC\u000159=0\u000110=000\u0001".toByteArray())),
        ).forEach { source ->
            codec.decode(MessageGroup(mutableListOf(source)), reportingContext).also { group ->
                assertEquals(1, group.messages.size)
            }.messages.first().also { decoded ->
                assertTrue(decoded is ParsedMessage)
//                TODO: uncomment when encode will be done
//                codec.encode(MessageGroup(mutableListOf(decoded)), reportingContext).also { group ->
//                    assertEquals(1, group.messages.size)
//                }.messages.first().also { encoded ->
//                    assertTrue(encoded is RawMessage)
//                    assertEquals(source, encoded)
//                }
            }
        }
    }

    @Test
    fun encode() {
        val encoded = codec.encode(MessageGroup(listOf(parsedMessage)), reportingContext)
        val body = encoded.messages.first().body as CompositeByteBuf
        val fixMsg = body.toString(StandardCharsets.US_ASCII)
        assertEquals(
            "8=FIXT1.1\u00019=295\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=145\u0001",
            fixMsg
        )

        val redecoded = codec.decode(encoded, reportingContext)
        assertTrue { redecoded.messages.first() is ParsedMessage }
    }

    @Test
    fun `encode with addition field that exists in dictionary`() {
        messageBody["CFICode"] = "12345"

        val encoded = codec.encode(MessageGroup(listOf(parsedMessage)), reportingContext)
        val body = encoded.messages.first().body as CompositeByteBuf
        val fixMsg = body.toString(StandardCharsets.US_ASCII)
        assertEquals(
            "8=FIXT1.1\u00019=305\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u0001461=12345\u000110=097\u0001",
            fixMsg
        )

        assertEquals(1, reportingContext.warnings.size)
        assertTrue("Actual warning: ${reportingContext.warnings[0]}") { reportingContext.warnings[0].startsWith("Dirty mode WARNING: Unexpected field in message. Field name: CFICode. Field value: 12345.") }
    }

    @Test
    fun `encode with addition field that does not exists in dictionary`() {
        messageBody["UNKNOWN_FIELD"] = "test_value"

        val exception = assertFailsWith<IllegalStateException> {
            codec.encode(MessageGroup(listOf(parsedMessage)), reportingContext)
        }

        assertTrue("Actual message: ${exception.message}") { exception.message?.startsWith("Field does not exist in dictionary. Field name: UNKNOWN_FIELD. Field value: test_value.") ?: false }
    }

    @Test
    fun `encode with addition field that contain tag instead of name`() {
        messageBody["461"] = "12345" // 'CFICode' field

        val encoded = codec.encode(MessageGroup(listOf(parsedMessage)), reportingContext)
        val body = encoded.messages.first().body as CompositeByteBuf
        val fixMsg = body.toString(StandardCharsets.US_ASCII)
        assertEquals(
            "8=FIXT1.1\u00019=305\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u0001461=12345\u000110=097\u0001",
            fixMsg
        )

        assertEquals(1, reportingContext.warnings.size)
        assertTrue { reportingContext.warnings[0].startsWith("Dirty mode WARNING: Tag instead of field name. Field name: 461. Field value: 12345.") }
    }

    @Test
    fun `encode with required field removed`() {
        messageBody.remove("ExecID")

        val encoded = codec.encode(MessageGroup(listOf(parsedMessage)), reportingContext)
        val body = encoded.messages.first().body as CompositeByteBuf
        val fixMsg = body.toString(StandardCharsets.US_ASCII)
        assertEquals(
            "8=FIXT1.1\u00019=282\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=014\u0001",
            fixMsg
        )

        assertEquals(1, reportingContext.warnings.size)
        assertTrue { reportingContext.warnings[0].startsWith("Dirty mode WARNING: Required field missing. Field name: ExecID.") }
    }

    @Test
    fun `encode with wrong enum value`() {
        messageBody["ExecType"]='X'

        val encoded = codec.encode(MessageGroup(listOf(parsedMessage)), reportingContext)
        val body = encoded.messages.first().body as CompositeByteBuf
        val fixMsg = body.toString(StandardCharsets.US_ASCII)
        assertEquals(
            "8=FIXT1.1\u00019=295\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=X\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=185\u0001",
            fixMsg
        )

        assertEquals(1, reportingContext.warnings.size)
        assertTrue("Actual warning: ${reportingContext.warnings[0]}") { reportingContext.warnings[0].startsWith("Dirty mode WARNING: Wrong value in field ExecType. Actual: X.") }
    }

    @Test
    fun `encode with wrong value type`() {
        messageBody["LeavesQty"]="500" // String instead of BigDecimal

        val encoded = codec.encode(MessageGroup(listOf(parsedMessage)), reportingContext)
        val body = encoded.messages.first().body as CompositeByteBuf
        val fixMsg = body.toString(StandardCharsets.US_ASCII)
        assertEquals(
            "8=FIXT1.1\u00019=295\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=145\u0001",
            fixMsg
        )

        assertEquals(1, reportingContext.warnings.size)
        assertTrue("Actual warning: ${reportingContext.warnings[0]}") { reportingContext.warnings[0].startsWith("Dirty mode WARNING: Wrong type value in field LeavesQty. Actual: class java.lang.String (value: 500). Expected class java.math.BigDecimal") }
    }

    @Test
    fun `encode with empty value`() {
        messageBody["Account"] = ""

        val encoded = codec.encode(MessageGroup(listOf(parsedMessage)), reportingContext)
        val body = encoded.messages.first().body as CompositeByteBuf
        val fixMsg = body.toString(StandardCharsets.US_ASCII)
        assertEquals(
            "8=FIXT1.1\u00019=291\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=205\u0001",
            fixMsg
        )

        assertEquals(1, reportingContext.warnings.size)
        assertTrue("Actual warning: ${reportingContext.warnings[0]}") { reportingContext.warnings[0].startsWith("Dirty mode WARNING: Empty value in the field 'Account'.") }
    }

    @Test
    fun `encode with non printable characters`() {
        messageBody["Account"] = "test\taccount"

        val encoded = codec.encode(MessageGroup(listOf(parsedMessage)), reportingContext)
        val body = encoded.messages.first().body as CompositeByteBuf
        val fixMsg = body.toString(StandardCharsets.US_ASCII)
        assertEquals(
            "8=FIXT1.1\u00019=303\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\taccount\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=125\u0001",
            fixMsg
        )

        assertEquals(1, reportingContext.warnings.size)
        assertTrue("Actual warning: ${reportingContext.warnings[0]}") { reportingContext.warnings[0].startsWith("Dirty mode WARNING: Non printable characters in the field 'Account'. Value: test\taccount") }
    }

    @Test
    fun `tag appears out of order`() {
        val tag = 999
        assertThrows<IllegalStateException> {
            codec.decode(
                MessageGroup(mutableListOf(
                    RawMessage(
                        body = Unpooled.wrappedBuffer("8=FIXT.1.1\u00019=156\u000135=8\u000134=10947\u000149=SENDER\u000152=20230419-10:36:07.415088\u000156=RECEIVER\u0001$tag=500\u000110=012\u0001".toByteArray())
                    )
                )),
                reportingContext)
        }.also { ex ->
            assertEquals("Tag appears out of order: $tag", ex.message)
        }
    }

    private val parsedMessage = ParsedMessage(
        MessageId("test_alias", Direction.OUTGOING, 0L, Instant.now(), emptyList()),
        EventId("test_id", "test_book", "test_scope", Instant.now()),
        "ExecutionReport",
        mapOf("encode-mode" to "dirty"),
        PROTOCOL,
        mapOf(
            "header" to mapOf(
                "MsgSeqNum" to 10947,
                "SenderCompID" to "SENDER",
                "SendingTime" to LocalDateTime.parse("2023-04-19T10:36:07.415088"),
                "TargetCompID" to "RECEIVER",
                "BeginString" to "FIXT.1.1",
                "BodyLength" to "156",
                "MsgType" to "8"
            ),
            "ExecID" to "495504662",
            "ClOrdID" to "zSuNbrBIZyVljs",
            "OrigClOrdID" to "zSuNbrBIZyVljs",
            "OrderID" to "49415882",
            "ExecType" to '0',
            "OrdStatus" to '0',
            "LeavesQty" to 500,
            "CumQty" to BigDecimal(500),
            "SecurityID" to "NWDR",
            "SecurityIDSource" to "8",
            "TradingParty" to mapOf(
                "NoPartyIDs" to listOf(
                    mapOf(
                        "PartyID" to "NGALL1FX01",
                        "PartyIDSource" to 'D',
                        "PartyRole" to 76
                    ),
                    mapOf(
                        "PartyID" to "0",
                        "PartyIDSource" to 'P',
                        "PartyRole" to 3
                    )
                )
            ),
            "Account" to "test",
            "OrdType" to 'A',
            "TimeInForce" to '0',
            "Side" to 'B',
            "Symbol" to "ABC",
            "OrderQty" to 500,
            "Price" to 1000,
            "Unknown" to "500",
            "TransactTime" to LocalDateTime.parse("2018-02-05T10:38:08.000008"),
            "trailer" to mapOf(
                "CheckSum" to 55
            )
        )
    )

    private val messageBody: MutableMap<String, Any?> = parsedMessage.body as MutableMap
}