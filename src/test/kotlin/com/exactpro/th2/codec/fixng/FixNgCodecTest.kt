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
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.ParsedMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageGroup
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageId
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.EventId
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Direction
import com.exactpro.th2.codec.fixng.FixNgCodecFactory.Companion.PROTOCOL
import io.netty.buffer.CompositeByteBuf
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime

class FixNgCodecTest {
    private val dictionary: IDictionaryStructure = FixNgCodecTest::class.java.classLoader
        .getResourceAsStream("dictionary.xml")
        .use(XmlDictionaryStructureLoader()::load)

    private val codec = FixNgCodec(dictionary, FixNgCodecSettings(dictionary = "", decodeValuesToStrings = false))

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
    fun `simple encode`() = encodeTest(MSG_CORRECT)

    @Test
    fun `simple encode from string values`() = encodeTest(MSG_CORRECT, encodeFromStringValues = true)

    @Test
    fun `simple decode`() = decodeTest(MSG_CORRECT)

    @Test
    fun `simple decode to string values`() = decodeTest(MSG_CORRECT, decodeToStringValues = true)

    @Test
    fun `simple decode with no body`() = decodeTest(MSG_CORRECT_WITHOUT_BODY, expectedMessage = expectedMessageWithoutBody)

    @Test
    fun `encode with addition field that exists in dictionary`() {
        parsedBody["CFICode"] = "12345"
        encodeTest(MSG_ADDITIONAL_FIELD_DICT, "Unexpected field in message. Field name: CFICode. Field value: 12345.")
    }

    @Test
    fun `decode with addition field that exists in dictionary`() {
        parsedBody["CFICode"] = "12345"
        decodeTest(MSG_ADDITIONAL_FIELD_DICT, "Unexpected field in message. Field name: CFICode. Field value: 12345.")
    }

    @Test
    fun `encode with addition field that does not exists in dictionary`() {
        parsedBody["UNKNOWN_FIELD"] = "test_value"
        assertThatThrownBy { codec.encode(MessageGroup(listOf(parsedMessage)), reportingContext) }
            .isInstanceOf(IllegalStateException::class.java)
            .message()
            .startsWith("Field does not exist in dictionary. Field name: UNKNOWN_FIELD. Field value: test_value.")
    }

    @Test
    fun `decode with addition field that does not exists in dictionary`() {
        parsedBody["9999"] = "54321"
        decodeTest(MSG_ADDITIONAL_FIELD_NO_DICT, "Field does not exist in dictionary. Field tag: 9999. Field value: 54321.")
    }

    @Test
    fun `encode with addition field that contain tag instead of name`() {
        parsedBody["9999"] = "12345" // field doesn't exist in dictionary
        encodeTest(MSG_ADDITIONAL_FIELD_TAG, "Tag instead of field name. Field name: 9999. Field value: 12345.")
    }

    @Test
    fun `encode with required field removed`() {
        parsedBody.remove("ExecID")
        encodeTest(MSG_REQUIRED_FIELD_REMOVED, "Required field missing. Field name: ExecID.")
    }

    @Test
    fun `decode with required field removed`() {
        parsedBody.remove("ExecID")
        decodeTest(MSG_REQUIRED_FIELD_REMOVED, "Required tag missing. Tag: 17.")
    }

    @Test
    fun `encode with required delimiter field in group removed in first entry`() {
        @Suppress("UNCHECKED_CAST")
        ((parsedBody["TradingParty"] as Map<String, Any>)["NoPartyIDs"] as List<MutableMap<String, Any>>)[0].remove("PartyID")
        encodeTest(MSG_DELIMITER_FIELD_IN_GROUP_REMOVED_IN_FIRST_ENTRY, "Required field missing. Field name: PartyID.")
    }

    @Test
    fun `decode with required delimiter field in group removed in first entry`() {
        @Suppress("UNCHECKED_CAST")
        ((parsedBody["TradingParty"] as Map<String, Any>)["NoPartyIDs"] as List<MutableMap<String, Any>>)[0].remove("PartyID")
        decodeTest(MSG_DELIMITER_FIELD_IN_GROUP_REMOVED_IN_FIRST_ENTRY, "Field PartyIDSource (447) appears before delimiter (448)")
    }

    @Test
    fun `encode with required delimiter field in group removed in second entry`() {
        @Suppress("UNCHECKED_CAST")
        ((parsedBody["TradingParty"] as Map<String, Any>)["NoPartyIDs"] as List<MutableMap<String, Any>>)[1].remove("PartyID")
        encodeTest(MSG_DELIMITER_FIELD_IN_GROUP_REMOVED_IN_SECOND_ENTRY, "Required field missing. Field name: PartyID.")
    }

    @Test
    fun `decode with required delimiter field in group removed in second entry`() {
        @Suppress("UNCHECKED_CAST")
        ((parsedBody["TradingParty"] as Map<String, Any>)["NoPartyIDs"] as List<MutableMap<String, Any>>)[1].remove("PartyID")
        decodeTest(MSG_DELIMITER_FIELD_IN_GROUP_REMOVED_IN_SECOND_ENTRY, "Field PartyIDSource (447) appears before delimiter (448)")
    }

    @Test
    fun `encode with wrong enum value`() {
        parsedBody["ExecType"] = 'X'
        encodeTest(MSG_WRONG_ENUM, "Invalid value in enum field ExecType. Actual: X.")
    }

    @Test
    fun `decode with wrong enum value`() {
        parsedBody["ExecType"] = 'X'
        decodeTest(MSG_WRONG_ENUM, "Invalid value in enum field ExecType. Actual: X.")
    }

    @Test
    fun `encode with correct enum value as string`() {
        parsedBody["ExecType"] = "0"
        encodeTest(MSG_CORRECT)
    }

    @Test
    fun `encode with wrong value type`() {
        parsedBody["LeavesQty"] = "Five" // String instead of BigDecimal
        encodeTest(MSG_WRONG_TYPE, "Wrong number value in java.math.BigDecimal field 'LeavesQty'. Value: Five.")
    }

    @Test
    fun `encode with correct BigDecimal value in string`() {
        parsedBody["LeavesQty"] = "500" // String instead of BigDecimal
        encodeTest(MSG_CORRECT)
    }

    @Test
    fun `decode with wrong value type`() {
        parsedBody["LeavesQty"] = "Five"
        decodeTest(MSG_WRONG_TYPE, "Wrong number value in java.math.BigDecimal field 'LeavesQty'. Value: Five.")
    }

    @Test
    fun `encode with empty value`() {
        parsedBody["Account"] = ""
        encodeTest(MSG_EMPTY_VAL, "Empty value in the field 'Account'.")
    }

    @Test
    fun `decode with empty value`() {
        parsedBody["Account"] = ""
        decodeTest(MSG_EMPTY_VAL, "Empty value in the field 'Account'.")
    }

    @Test
    fun `encode with non printable characters`() {
        parsedBody["Account"] = "test\taccount"
        encodeTest(MSG_NON_PRINTABLE, "Non-printable characters in the field 'Account'. Value: test\taccount")
    }

    @Test
    fun `decode with non printable characters`() {
        parsedBody["Account"] = "test\taccount"
        decodeTest(MSG_NON_PRINTABLE, "Non printable characters in the field 'Account'.")
    }

    @Test
    fun `encode with calculated required fields removed`() {
        @Suppress("UNCHECKED_CAST")
        val header = parsedBody["header"] as MutableMap<String, Any>
        header.remove("BeginString")
        header.remove("BodyLength")
        header.remove("MsgType")
        @Suppress("UNCHECKED_CAST")
        (parsedBody["trailer"] as MutableMap<String, Any>).remove("CheckSum")
        encodeTest(MSG_CORRECT)
    }

    @Test
    fun `encode with calculated required header fields removed`() {
        @Suppress("UNCHECKED_CAST")
        val header = parsedBody["header"] as MutableMap<String, Any>
        header.remove("SenderCompID")
        header.remove("TargetCompID")
        header.remove("MsgSeqNum")
        header.remove("SendingTime")
        encodeTest(MSG_REQUIRED_HEADER_REMOVED)
    }

    @Test
    fun `tag appears out of order`() =
        decodeTest(MSG_TAG_OUT_OF_ORDER, "Tag appears out of order: 999", dirtyMode = false)

    @Test
    fun `decode nested components`() =
        decodeTest(MSG_NESTED_REQ_COMPONENTS, expectedMessage = parsedMessageWithNestedComponents)

    @Test
    fun `decode with missing req field in req nested component`() {
        @Suppress("UNCHECKED_CAST")
        (parsedBodyWithNestedComponents["OuterComponent"] as MutableMap<String, MutableMap<String, Any>>)["InnerComponent"]?.remove("OrdType")
        decodeTest(MSG_NESTED_REQ_COMPONENTS_MISSED_REQ, expectedErrorText = "Required tag missing. Tag: 40.", expectedMessage = parsedMessageWithNestedComponents)
    }

    @Test
    fun `decode with missing optional field in req nested component`() {
        @Suppress("UNCHECKED_CAST")
        (parsedBodyWithNestedComponents["OuterComponent"] as MutableMap<String, MutableMap<String, Any>>)["InnerComponent"]?.remove("Text")
        decodeTest(MSG_NESTED_REQ_COMPONENTS_MISSED_OPTIONAL, expectedMessage = parsedMessageWithNestedComponents)
    }

    private fun convertToOptionalComponent(): ParsedMessage {
        @Suppress("UNCHECKED_CAST")
        (parsedBodyWithNestedComponents["header"] as MutableMap<String, String>)["MsgType"] = "TEST_2"
        val msgBuilder = parsedMessageWithNestedComponents.toBuilder()
        msgBuilder.setType("NestedOptionalComponentTestMessage")
        return msgBuilder.build()
    }

    @Test
    fun `decode nested optional components`() {
        val message = convertToOptionalComponent()
        decodeTest(MSG_NESTED_OPT_COMPONENTS, expectedMessage = message)
    }

    @Test
    fun `decode with missing req field in opt nested component`() {
        val message = convertToOptionalComponent()
        @Suppress("UNCHECKED_CAST")
        (parsedBodyWithNestedComponents["OuterComponent"] as MutableMap<String, MutableMap<String, Any>>)["InnerComponent"]?.remove("OrdType")
        decodeTest(MSG_NESTED_OPT_COMPONENTS_MISSED_REQ, expectedErrorText = "Required tag missing. Tag: 40.", expectedMessage = message)
    }

    @Test
    fun `decode with missing all fields in opt nested component`() {
        val message = convertToOptionalComponent()
        @Suppress("UNCHECKED_CAST")
        (parsedBodyWithNestedComponents["OuterComponent"] as MutableMap<String, MutableMap<String, Any>>).remove("InnerComponent")
        decodeTest(MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_FIELDS, expectedErrorText = "Required tag missing. Tag: 40.", expectedMessage = message)
    }

    @Test
    fun `decode with missing all fields in inner and outer nested components`() {
        val message = convertToOptionalComponent()
        parsedBodyWithNestedComponents.remove("OuterComponent")
        decodeTest(MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_FIELDS_INNER_AND_OUTER, expectedMessage = message)
    }

    @Test
    fun `decode with missing req fields in both inner and outer components`() {
        val message = convertToOptionalComponent()
        @Suppress("UNCHECKED_CAST")
        (parsedBodyWithNestedComponents["OuterComponent"] as MutableMap<String, MutableMap<String, Any>>).remove("LeavesQty")
        @Suppress("UNCHECKED_CAST")
        (parsedBodyWithNestedComponents["OuterComponent"] as MutableMap<String, MutableMap<String, Any>>)["InnerComponent"]!!.remove("OrdType")
        decodeTest(
            MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_OUTER_FIELDS_AND_REQ_INNER_FIELD,
            expectedErrorText = "Required tag missing. Tag: 40.",
            expectedSecondErrorText = "Required tag missing. Tag: 151.",
            expectedMessage = message
        )
    }

    private fun encodeTest(
        expectedRawMessage: String,
        expectedWarning: String? = null,
        encodeFromStringValues: Boolean = false
    ) {
        if (encodeFromStringValues) {
            @Suppress("UNCHECKED_CAST")
            val stringBody = convertValuesToString(parsedBody) as Map<String, Any>
            parsedBody.putAll(stringBody)
        }

        val encoded = codec.encode(MessageGroup(listOf(parsedMessage)), reportingContext)
        val body = encoded.messages.single().body as CompositeByteBuf
        val fixMsg = body.toString(StandardCharsets.US_ASCII)
        assertThat(fixMsg).isEqualTo(expectedRawMessage)
        if (expectedWarning == null) {
            assertThat(reportingContext.warnings).isEmpty()
        } else {
            assertThat(reportingContext.warnings.single()).startsWith(DIRTY_MODE_WARNING_PREFIX + expectedWarning)
        }
    }

    private fun decodeTest(
        rawMessageString: String,
        expectedErrorText: String? = null,
        expectedSecondErrorText: String? = null,
        expectedMessage: ParsedMessage = parsedMessage,
        dirtyMode: Boolean = true,
        decodeToStringValues: Boolean = false
    ) {
        val expectedBody = expectedMessage.body
        val rawMessage = RawMessage(
            id = parsedMessage.id,
            eventId = parsedMessage.eventId,
            metadata = if (dirtyMode) mapOf("encode-mode" to "dirty") else emptyMap(),
            body = Unpooled.wrappedBuffer(rawMessageString.toByteArray(Charsets.US_ASCII))
        )

        val decodedGroup = try {
            val codec = if (decodeToStringValues) FixNgCodec(dictionary, FixNgCodecSettings(dictionary = "")) else this.codec
            codec.decode(MessageGroup(listOf(rawMessage)), reportingContext)
        } catch (e: IllegalStateException) {
            if (dirtyMode) {
                throw e
            } else {
                assertThat(e.message).startsWith(expectedErrorText)
                return
            }
        }

        val parsedMessage = decodedGroup.messages.single() as ParsedMessage

        // we don't validate `CheckSum` and `BodyLength` in incorrect messages
        val fieldsToIgnore = if (expectedErrorText == null) emptyArray() else arrayOf("trailer.CheckSum", "header.BodyLength")
        val expected = if (decodeToStringValues) convertValuesToString(expectedBody) else expectedBody

        assertThat(parsedMessage.body)
            .usingRecursiveComparison()
            .ignoringFields(*fieldsToIgnore)
            .isEqualTo(expected)

        if (expectedErrorText == null) {
            assertThat(reportingContext.warnings).isEmpty()
        } else {
            if (expectedSecondErrorText == null) {
                assertThat(reportingContext.warnings.single()).startsWith(DIRTY_MODE_WARNING_PREFIX + expectedErrorText)
            } else {
                assertThat(reportingContext.warnings).size().isEqualTo(2)
                assertThat(reportingContext.warnings[0]).startsWith(DIRTY_MODE_WARNING_PREFIX + expectedErrorText)
                assertThat(reportingContext.warnings[1]).startsWith(DIRTY_MODE_WARNING_PREFIX + expectedSecondErrorText)
            }
        }
    }

    private fun convertValuesToString(value: Any?): Any = when (value) {
        is Map<*, *> -> value.mapValues { convertValuesToString(it.value) }
        is List<*> -> value.map(::convertValuesToString)
        else -> value.toString()
    }

    private val parsedMessage = ParsedMessage(
        MessageId("test_alias", Direction.OUTGOING, 0L, Instant.now(), emptyList()),
        EventId("test_id", "test_book", "test_scope", Instant.now()),
        "ExecutionReport",
        mutableMapOf("encode-mode" to "dirty"),
        PROTOCOL,
        mutableMapOf(
            "header" to mutableMapOf(
                "MsgSeqNum" to 10947,
                "SenderCompID" to "SENDER",
                "SendingTime" to LocalDateTime.parse("2023-04-19T10:36:07.415088"),
                "TargetCompID" to "RECEIVER",
                "BeginString" to "FIXT.1.1",
                "BodyLength" to 295,
                "MsgType" to "8"
            ),
            "ExecID" to "495504662",
            "ClOrdID" to "zSuNbrBIZyVljs",
            "OrigClOrdID" to "zSuNbrBIZyVljs",
            "OrderID" to "49415882",
            "ExecType" to '0',
            "OrdStatus" to '0',
            "LeavesQty" to BigDecimal(500),
            "CumQty" to BigDecimal(500),
            "SecurityID" to "NWDR",
            "SecurityIDSource" to "8",
            "TradingParty" to mutableMapOf(
                "NoPartyIDs" to mutableListOf(
                    mutableMapOf(
                        "PartyID" to "NGALL1FX01",
                        "PartyIDSource" to 'D',
                        "PartyRole" to 76
                    ),
                    mutableMapOf(
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
            "OrderQty" to BigDecimal(500),
            "Price" to BigDecimal(1000),
            "Unknown" to "500",
            "TransactTime" to LocalDateTime.parse("2018-02-05T10:38:08.000008"),
            "trailer" to mutableMapOf(
                "CheckSum" to "191"
            )
        )
    )

    private val parsedBody: MutableMap<String, Any?> = parsedMessage.body as MutableMap

    private val expectedMessageWithoutBody = ParsedMessage(
        MessageId("test_alias", Direction.OUTGOING, 0L, Instant.now(), emptyList()),
        EventId("test_id", "test_book", "test_scope", Instant.now()),
        "ExecutionReport",
        mutableMapOf("encode-mode" to "dirty"),
        PROTOCOL,
        mutableMapOf(
            "header" to mapOf(
                "MsgSeqNum" to 125,
                "SenderCompID" to "MZHOT0",
                "SendingTime" to LocalDateTime.parse("2024-08-01T08:03:01.229"),
                "TargetCompID" to "INET",
                "BeginString" to "FIX.4.2",
                "BodyLength" to 55,
                "MsgType" to "0"
            ),
            "trailer" to mapOf(
                "CheckSum" to "039"
            )
        )
    )

    private val parsedMessageWithNestedComponents = ParsedMessage(
        MessageId("test_alias", Direction.OUTGOING, 0L, Instant.now(), emptyList()),
        EventId("test_id", "test_book", "test_scope", Instant.now()),
        "NestedRequiredComponentTestMessage",
        mutableMapOf("encode-mode" to "dirty"),
        PROTOCOL,
        mutableMapOf(
            "header" to mutableMapOf(
                "BeginString" to "FIXT.1.1",
                "BodyLength" to 59,
                "MsgType" to "TEST_1",
                "MsgSeqNum" to 125,
                "TargetCompID" to "INET",
                "SenderCompID" to "MZHOT0"
            ),
            "OuterComponent" to mutableMapOf(
                "LeavesQty" to BigDecimal(1234), // tag 151
                "InnerComponent" to mutableMapOf(
                    "Text" to "text_1", // tag 58
                    "OrdType" to '1' // 40
                )
            ),
            "trailer" to mapOf(
                "CheckSum" to "191"
            )
        )
    )
    private val parsedBodyWithNestedComponents: MutableMap<String, Any?> = parsedMessageWithNestedComponents.body as MutableMap

    companion object {
        private const val DIRTY_MODE_WARNING_PREFIX = "Dirty mode WARNING: "

        private const val MSG_CORRECT = "8=FIXT.1.1\u00019=295\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=191\u0001"
        private const val MSG_CORRECT_WITHOUT_BODY = "8=FIX.4.2\u00019=55\u000135=0\u000134=125\u000149=MZHOT0\u000152=20240801-08:03:01.229\u000156=INET\u000110=039\u0001"
        private const val MSG_ADDITIONAL_FIELD_DICT = "8=FIXT.1.1\u00019=305\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u0001461=12345\u000110=143\u0001"
        private const val MSG_ADDITIONAL_FIELD_NO_DICT = "8=FIXT.1.1\u00019=305\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u00019999=54321\u000110=097\u0001"
        private const val MSG_ADDITIONAL_FIELD_TAG = "8=FIXT.1.1\u00019=306\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u00019999=12345\u000110=217\u0001"
        private const val MSG_REQUIRED_FIELD_REMOVED = "8=FIXT.1.1\u00019=282\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=060\u0001"
        private const val MSG_DELIMITER_FIELD_IN_GROUP_REMOVED_IN_FIRST_ENTRY = "8=FIXT.1.1\u00019=280\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=061\u0001"
        private const val MSG_DELIMITER_FIELD_IN_GROUP_REMOVED_IN_SECOND_ENTRY = "8=FIXT.1.1\u00019=289\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=180\u0001"
        private const val MSG_WRONG_ENUM = "8=FIXT.1.1\u00019=295\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=X\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=231\u0001"
        private const val MSG_WRONG_TYPE = "8=FIXT.1.1\u00019=296\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=Five\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=181\u0001"
        private const val MSG_EMPTY_VAL = "8=FIXT.1.1\u00019=291\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=251\u0001"
        private const val MSG_NON_PRINTABLE = "8=FIXT.1.1\u00019=303\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\taccount\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=171\u0001"
        private const val MSG_REQUIRED_HEADER_REMOVED = "8=FIXT.1.1\u00019=236\u000135=8\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=050\u0001"
        private const val MSG_TAG_OUT_OF_ORDER = "8=FIXT.1.1\u00019=295\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=000\u0001999=500\u0001"

        private const val MSG_NESTED_REQ_COMPONENTS = "8=FIXT.1.19=5935=TEST_149=MZHOT056=INET34=12558=text_140=1151=123410=191"
        private const val MSG_NESTED_REQ_COMPONENTS_MISSED_REQ = "8=FIXT.1.19=5935=TEST_149=MZHOT056=INET34=12558=text_1151=123410=191"
        private const val MSG_NESTED_REQ_COMPONENTS_MISSED_OPTIONAL = "8=FIXT.1.19=5935=TEST_149=MZHOT056=INET34=12540=1151=123410=191"

        private const val MSG_NESTED_OPT_COMPONENTS = "8=FIXT.1.19=5935=TEST_249=MZHOT056=INET34=12558=text_140=1151=123410=191"
        private const val MSG_NESTED_OPT_COMPONENTS_MISSED_REQ = "8=FIXT.1.19=5935=TEST_249=MZHOT056=INET34=12558=text_1151=123410=191"
        private const val MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_FIELDS = "8=FIXT.1.19=5935=TEST_249=MZHOT056=INET34=125151=123410=191"
        private const val MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_FIELDS_INNER_AND_OUTER = "8=FIXT.1.19=5935=TEST_249=MZHOT056=INET34=12510=191"
        private const val MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_OUTER_FIELDS_AND_REQ_INNER_FIELD = "8=FIXT.1.19=5935=TEST_249=MZHOT056=INET34=12558=text_110=191"
    }
}