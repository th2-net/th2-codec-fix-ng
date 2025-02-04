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
import com.exactpro.th2.codec.fixng.FixNgCodecFactory.Companion.PROTOCOL
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Direction
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.EventId
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageGroup
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageId
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.ParsedMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage
import io.netty.buffer.CompositeByteBuf
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime

class FixNgCodecTest {
    private val dictionary: IDictionaryStructure = FixNgCodecTest::class.java.classLoader
        .getResourceAsStream("dictionary.xml")
        .use(XmlDictionaryStructureLoader()::load)

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

    @ParameterizedTest
    @MethodSource("configs")
    fun `simple encode`(isDirty: Boolean, delimiter: Char) = encodeTest(MSG_CORRECT, isDirty, delimiter)

    @ParameterizedTest
    @MethodSource("configs")
    fun `simple encode from string values`(isDirty: Boolean, delimiter: Char) =
        encodeTest(MSG_CORRECT, isDirty, delimiter, encodeFromStringValues = true)

    @ParameterizedTest
    @MethodSource("configs")
    fun `simple decode`(isDirty: Boolean, delimiter: Char) =
        decodeTest(MSG_CORRECT, dirtyMode = isDirty, delimiter = delimiter)

    @ParameterizedTest
    @MethodSource("configs")
    fun `simple decode to string values`(isDirty: Boolean, delimiter: Char) =
        decodeTest(MSG_CORRECT, dirtyMode = isDirty, delimiter = delimiter, decodeToStringValues = true)

    @ParameterizedTest
    @MethodSource("configs")
    fun `simple decode with no body`(isDirty: Boolean, delimiter: Char) =
        decodeTest(
            MSG_CORRECT_WITHOUT_BODY,
            dirtyMode = isDirty,
            delimiter = delimiter,
            expectedMessage = expectedMessageWithoutBody
        )

    @ParameterizedTest
    @MethodSource("configs")
    fun `encode with addition field that exists in dictionary`(isDirty: Boolean, delimiter: Char) {
        parsedBody["CFICode"] = "12345"
        encodeTest(
            MSG_ADDITIONAL_FIELD_DICT,
            isDirty,
            delimiter,
            "Unexpected field in message. Field name: CFICode. Field value: 12345."
        )
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `decode with addition field that exists in dictionary (dirty)`(delimiter: Char) {
        parsedBody["CFICode"] = "12345"
        decodeTest(
            MSG_ADDITIONAL_FIELD_DICT,
            dirtyMode = true,
            delimiter = delimiter,
            "Unexpected field in message. Field name: CFICode. Field value: 12345."
        )
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `decode with addition field that exists in dictionary (non dirty)`(delimiter: Char) {
        parsedBody["CFICode"] = "12345"
        // note: Unknown tag in the message causes the processing of messages to stop and moves on to the next part of
        // the message. As a result, required tags remain unread, which leads to the following error.
        decodeTest(
            MSG_ADDITIONAL_FIELD_DICT,
            dirtyMode = false,
            delimiter = delimiter,
            "Required tag missing. Tag: 10."
        )
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `encode with addition field that does not exists in dictionary`(delimiter: Char) {
        parsedBody["UNKNOWN_FIELD"] = "test_value"
        assertThatThrownBy { createCodec(delimiter).encode(MessageGroup(listOf(parsedMessage)), reportingContext) }
            .isInstanceOf(IllegalStateException::class.java)
            .message()
            .startsWith("Field does not exist in dictionary. Field name: UNKNOWN_FIELD. Field value: test_value.")
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `decode with addition field that does not exists in dictionary (dirty)`(delimiter: Char) {
        parsedBody["9999"] = "54321"
        decodeTest(
            MSG_ADDITIONAL_FIELD_NO_DICT,
            dirtyMode = true,
            delimiter = delimiter,
            "Field does not exist in dictionary. Field tag: 9999. Field value: 54321."
        )
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `decode with addition field that does not exists in dictionary (non dirty)`(delimiter: Char) {
        parsedBody["9999"] = "54321"
        // note: Unknown tag in the message causes the processing of messages to stop and moves on to the next part of
        // the message. As a result, required tags remain unread, which leads to the following error.
        decodeTest(
            MSG_ADDITIONAL_FIELD_NO_DICT,
            dirtyMode = false,
            delimiter = delimiter,
            "Required tag missing. Tag: 10."
        )
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `encode with addition field that contain tag instead of name (dirty)`(delimiter: Char) {
        parsedBody["9999"] = "12345" // field doesn't exist in dictionary
        encodeTest(
            MSG_ADDITIONAL_FIELD_TAG,
            dirtyMode = true,
            delimiter,
            "Tag instead of field name. Field name: 9999. Field value: 12345."
        )
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `encode with addition field that contain tag instead of name (non dirty)`(delimiter: Char) {
        parsedBody["9999"] = "12345" // field doesn't exist in dictionary
        encodeTest(
            MSG_ADDITIONAL_FIELD_TAG,
            dirtyMode = false, delimiter,
            "Unexpected field in message. Field name: 9999. Field value: 12345."
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `encode with required field removed`(isDirty: Boolean, delimiter: Char) {
        parsedBody.remove("ExecID")
        encodeTest(MSG_REQUIRED_FIELD_REMOVED, isDirty, delimiter, "Required field missing. Field name: ExecID.")
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with required field removed`(isDirty: Boolean, delimiter: Char) {
        parsedBody.remove("ExecID")
        decodeTest(
            MSG_REQUIRED_FIELD_REMOVED,
            dirtyMode = isDirty,
            delimiter = delimiter,
            "Required tag missing. Tag: 17."
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `encode with required delimiter field in group removed in first entry`(isDirty: Boolean, delimiter: Char) {
        @Suppress("UNCHECKED_CAST")
        ((parsedBody["TradingParty"] as Map<String, Any>)["NoPartyIDs"] as List<MutableMap<String, Any>>)[0].remove("PartyID")
        encodeTest(
            MSG_DELIMITER_FIELD_IN_GROUP_REMOVED_IN_FIRST_ENTRY,
            isDirty,
            delimiter,
            "Required field missing. Field name: PartyID."
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with required delimiter field in group removed in first entry`(isDirty: Boolean, delimiter: Char) {
        @Suppress("UNCHECKED_CAST")
        ((parsedBody["TradingParty"] as Map<String, Any>)["NoPartyIDs"] as List<MutableMap<String, Any>>)[0].remove("PartyID")
        decodeTest(
            MSG_DELIMITER_FIELD_IN_GROUP_REMOVED_IN_FIRST_ENTRY,
            dirtyMode = isDirty,
            delimiter = delimiter,
            "Field PartyIDSource (447) appears before delimiter (448)"
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `encode with required delimiter field in group removed in second entry`(isDirty: Boolean, delimiter: Char) {
        @Suppress("UNCHECKED_CAST")
        ((parsedBody["TradingParty"] as Map<String, Any>)["NoPartyIDs"] as List<MutableMap<String, Any>>)[1].remove("PartyID")
        encodeTest(
            MSG_DELIMITER_FIELD_IN_GROUP_REMOVED_IN_SECOND_ENTRY,
            isDirty,
            delimiter,
            "Required field missing. Field name: PartyID."
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with required delimiter field in group removed in second entry`(isDirty: Boolean, delimiter: Char) {
        @Suppress("UNCHECKED_CAST")
        ((parsedBody["TradingParty"] as Map<String, Any>)["NoPartyIDs"] as List<MutableMap<String, Any>>)[1].remove("PartyID")
        decodeTest(
            MSG_DELIMITER_FIELD_IN_GROUP_REMOVED_IN_SECOND_ENTRY,
            dirtyMode = isDirty,
            delimiter = delimiter,
            "Field PartyIDSource (447) appears before delimiter (448)"
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `encode with wrong enum value`(isDirty: Boolean, delimiter: Char) {
        parsedBody["ExecType"] = 'X'
        encodeTest(MSG_WRONG_ENUM, isDirty, delimiter, "Invalid value in enum field ExecType. Actual: X.")
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with wrong enum value`(isDirty: Boolean, delimiter: Char) {
        parsedBody["ExecType"] = 'X'
        decodeTest(
            MSG_WRONG_ENUM,
            dirtyMode = isDirty,
            delimiter = delimiter,
            "Invalid value in enum field ExecType. Actual: X."
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `encode with correct enum value as string`(isDirty: Boolean, delimiter: Char) {
        parsedBody["ExecType"] = "0"
        encodeTest(MSG_CORRECT, isDirty, delimiter)
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `encode with wrong value type`(isDirty: Boolean, delimiter: Char) {
        parsedBody["LeavesQty"] = "Five" // String instead of BigDecimal
        encodeTest(
            MSG_WRONG_TYPE,
            isDirty,
            delimiter,
            "Wrong number value in java.math.BigDecimal field 'LeavesQty'. Value: Five."
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `encode with correct BigDecimal value in string`(isDirty: Boolean, delimiter: Char) {
        parsedBody["LeavesQty"] = "500" // String instead of BigDecimal
        encodeTest(MSG_CORRECT, isDirty, delimiter)
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with wrong value type`(isDirty: Boolean, delimiter: Char) {
        parsedBody["LeavesQty"] = "Five"
        decodeTest(
            MSG_WRONG_TYPE,
            dirtyMode = isDirty,
            delimiter = delimiter,
            "Wrong number value in java.math.BigDecimal field 'LeavesQty'. Value: Five."
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `encode with empty value`(isDirty: Boolean, delimiter: Char) {
        parsedBody["Account"] = ""
        encodeTest(MSG_EMPTY_VAL, isDirty, delimiter, "Empty value in the field 'Account'.")
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `decode with empty value (dirty)`(delimiter: Char) {
        parsedBody["Account"] = ""
        decodeTest(MSG_EMPTY_VAL, dirtyMode = true, delimiter = delimiter, "Empty value in the field 'Account'.")
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `decode with empty value (non dirty)`(delimiter: Char) {
        parsedBody["Account"] = ""
        decodeTest(MSG_EMPTY_VAL, dirtyMode = false, delimiter = delimiter, "No valid value at offset: 235")
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `encode with non printable characters`(isDirty: Boolean, delimiter: Char) {
        parsedBody["Account"] = "test\taccount"
        encodeTest(
            MSG_NON_PRINTABLE,
            isDirty,
            delimiter,
            "Non-printable characters in the field 'Account'. Value: test\taccount"
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with non printable characters`(isDirty: Boolean, delimiter: Char) {
        parsedBody["Account"] = "test\taccount"
        decodeTest(
            MSG_NON_PRINTABLE,
            dirtyMode = isDirty,
            delimiter = delimiter,
            "Non printable characters in the field 'Account'."
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `encode with calculated required fields removed`(isDirty: Boolean, delimiter: Char) {
        @Suppress("UNCHECKED_CAST")
        val header = parsedBody["header"] as MutableMap<String, Any>
        header.remove("BeginString")
        header.remove("BodyLength")
        header.remove("MsgType")
        @Suppress("UNCHECKED_CAST")
        (parsedBody["trailer"] as MutableMap<String, Any>).remove("CheckSum")
        encodeTest(MSG_CORRECT, isDirty, delimiter)
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `encode with calculated required header fields removed`(isDirty: Boolean, delimiter: Char) {
        @Suppress("UNCHECKED_CAST")
        val header = parsedBody["header"] as MutableMap<String, Any>
        header.remove("SenderCompID")
        header.remove("TargetCompID")
        header.remove("MsgSeqNum")
        header.remove("SendingTime")
        encodeTest(MSG_REQUIRED_HEADER_REMOVED, isDirty, delimiter)
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `tag appears out of order (dirty)`(delimiter: Char) {
        @Suppress("UNCHECKED_CAST")
        val trailer = parsedBody["trailer"] as MutableMap<String, Any>
        trailer["LegUnitOfMeasure"] = "500"
        decodeTest(
            MSG_TAG_OUT_OF_ORDER,
            dirtyMode = true,
            delimiter = delimiter,
            "Unexpected field in message. Field name: LegUnitOfMeasure. Field value: 500"
        )
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `tag appears out of order (non dirty)`(delimiter: Char) {
        decodeTest(MSG_TAG_OUT_OF_ORDER, dirtyMode = false, delimiter = delimiter, "Tag appears out of order: 999")
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode nested components`(isDirty: Boolean, delimiter: Char) =
        decodeTest(
            MSG_NESTED_REQ_COMPONENTS,
            dirtyMode = isDirty,
            delimiter = delimiter,
            expectedMessage = parsedMessageWithNestedComponents
        )

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with missing req field in req nested component`(isDirty: Boolean, delimiter: Char) {
        @Suppress("UNCHECKED_CAST")
        (parsedBodyWithNestedComponents["OuterComponent"] as MutableMap<String, MutableMap<String, Any>>)["InnerComponent"]?.remove("OrdType")
        decodeTest(
            MSG_NESTED_REQ_COMPONENTS_MISSED_REQ,
            dirtyMode = isDirty,
            delimiter = delimiter,
            expectedErrorText = "Required tag missing. Tag: 40.",
            expectedMessage = parsedMessageWithNestedComponents
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with missing optional field in req nested component`(isDirty: Boolean, delimiter: Char) {
        @Suppress("UNCHECKED_CAST")
        (parsedBodyWithNestedComponents["OuterComponent"] as MutableMap<String, MutableMap<String, Any>>)["InnerComponent"]?.remove("Text")
        decodeTest(
            MSG_NESTED_REQ_COMPONENTS_MISSED_OPTIONAL,
            dirtyMode = isDirty,
            delimiter = delimiter,
            expectedMessage = parsedMessageWithNestedComponents
        )
    }

    private fun convertToOptionalComponent(): ParsedMessage {
        @Suppress("UNCHECKED_CAST")
        (parsedBodyWithNestedComponents["header"] as MutableMap<String, String>)["MsgType"] = "TEST_2"
        val msgBuilder = parsedMessageWithNestedComponents.toBuilder()
        msgBuilder.setType("NestedOptionalComponentTestMessage")
        return msgBuilder.build()
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode nested optional components`(isDirty: Boolean, delimiter: Char) {
        val message = convertToOptionalComponent()
        decodeTest(MSG_NESTED_OPT_COMPONENTS, dirtyMode = isDirty, delimiter = delimiter, expectedMessage = message)
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with missing req field in opt nested component`(isDirty: Boolean, delimiter: Char) {
        val message = convertToOptionalComponent()
        @Suppress("UNCHECKED_CAST")
        (parsedBodyWithNestedComponents["OuterComponent"] as MutableMap<String, MutableMap<String, Any>>)["InnerComponent"]?.remove("OrdType")
        decodeTest(
            MSG_NESTED_OPT_COMPONENTS_MISSED_REQ,
            dirtyMode = isDirty,
            delimiter = delimiter,
            expectedErrorText = "Required tag missing. Tag: 40.",
            expectedMessage = message
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with missing all fields in opt nested component`(isDirty: Boolean, delimiter: Char) {
        val message = convertToOptionalComponent()
        @Suppress("UNCHECKED_CAST")
        (parsedBodyWithNestedComponents["OuterComponent"] as MutableMap<String, MutableMap<String, Any>>).remove("InnerComponent")
        decodeTest(
            MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_FIELDS,
            dirtyMode = isDirty,
            delimiter = delimiter,
            expectedErrorText = "Required tag missing. Tag: 40.",
            expectedMessage = message
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with missing all fields in inner and outer nested components`(isDirty: Boolean, delimiter: Char) {
        val message = convertToOptionalComponent()
        parsedBodyWithNestedComponents.remove("OuterComponent")
        decodeTest(
            MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_FIELDS_INNER_AND_OUTER,
            dirtyMode = isDirty,
            delimiter = delimiter,
            expectedMessage = message
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with missing req fields in both inner and outer components`(isDirty: Boolean, delimiter: Char) {
        val message = convertToOptionalComponent()
        @Suppress("UNCHECKED_CAST")
        (parsedBodyWithNestedComponents["OuterComponent"] as MutableMap<String, MutableMap<String, Any>>).remove("LeavesQty")
        @Suppress("UNCHECKED_CAST")
        (parsedBodyWithNestedComponents["OuterComponent"] as MutableMap<String, MutableMap<String, Any>>)["InnerComponent"]!!.remove("OrdType")
        decodeTest(
            MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_OUTER_FIELDS_AND_REQ_INNER_FIELD,
            dirtyMode = isDirty,
            delimiter = delimiter,
            expectedErrorText = "Required tag missing. Tag: 40.",
            expectedSecondErrorText = "Required tag missing. Tag: 151.",
            expectedMessage = message
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `encode nested groups`(isDirty: Boolean, delimiter: Char) = encodeTest(
        MSG_NESTED_GROUPS,
        isDirty,
        delimiter,
        parsedMessage = parsedMessageWithNestedGroups
    )

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode nested groups`(isDirty: Boolean, delimiter: Char) = decodeTest(
        MSG_NESTED_GROUPS,
        dirtyMode = isDirty,
        delimiter = delimiter,
        expectedMessage = parsedMessageWithNestedGroups
    )

    @ParameterizedTest
    @MethodSource("configs")
    fun `encode time zone`(isDirty: Boolean, delimiter: Char) = encodeTest(
        MSG_TIME_ZONE,
        isDirty,
        delimiter,
        parsedMessage = parsedMessageWithTimezone
    )

    private fun createCodec(delimiter: Char = '', decodeValuesToStrings: Boolean = false): FixNgCodec {
        return FixNgCodec(dictionary, FixNgCodecSettings(
            dictionary = "",
            decodeValuesToStrings = decodeValuesToStrings,
            decodeDelimiter = delimiter
        ))
    }

    private fun encodeTest(
        expectedRawMessage: String,
        dirtyMode: Boolean,
        delimiter: Char,
        expectedError: String? = null,
        encodeFromStringValues: Boolean = false,
        parsedMessage: ParsedMessage = this.parsedMessage
    ) {
        if (dirtyMode) {
            encodeTestDirty(expectedRawMessage, parsedMessage, delimiter, expectedError, encodeFromStringValues)
        } else {
            encodeTestNonDirty(expectedRawMessage, parsedMessage, delimiter, expectedError, encodeFromStringValues)
        }
    }

    private fun encodeTestDirty(
        expectedRawMessage: String,
        parsedMessage: ParsedMessage,
        delimiter: Char,
        expectedWarning: String? = null,
        encodeFromStringValues: Boolean = false
    ) {
        val parsedBody = parsedMessage.body as MutableMap<String, Any?>

        if (encodeFromStringValues) {
            @Suppress("UNCHECKED_CAST")
            val stringBody = convertValuesToString(parsedBody) as Map<String, Any>
            parsedBody.putAll(stringBody)
        }

        val encoded = createCodec(delimiter = delimiter).encode(MessageGroup(listOf(parsedMessage)), reportingContext)
        val body = encoded.messages.single().body as CompositeByteBuf
        val fixMsg = body.toString(StandardCharsets.US_ASCII)
        assertThat(fixMsg).isEqualTo(expectedRawMessage)
        if (expectedWarning == null) {
            assertThat(reportingContext.warnings).isEmpty()
        } else {
            assertThat(reportingContext.warnings.single()).startsWith(DIRTY_MODE_WARNING_PREFIX + expectedWarning)
        }
    }

    private fun encodeTestNonDirty(
        expectedRawMessage: String,
        parsedMessage: ParsedMessage,
        delimiter: Char,
        expectedError: String? = null,
        encodeFromStringValues: Boolean = false
    ) {
        val parsedBody = parsedMessage.body as MutableMap<String, Any?>

        if (encodeFromStringValues) {
            @Suppress("UNCHECKED_CAST")
            val stringBody = convertValuesToString(parsedBody) as Map<String, Any>
            parsedBody.putAll(stringBody)
        }

        val parsed = parsedMessage.toBuilder().apply { metadataBuilder().remove("encode-mode").build() }.build()

        if (expectedError != null) {
            assertThatThrownBy {
                createCodec(delimiter = delimiter).encode(MessageGroup(listOf(parsed)), reportingContext)
                println()
            }.isInstanceOf(IllegalStateException::class.java).message()
                .startsWith(expectedError)
        } else {
            val encoded = createCodec(delimiter = delimiter).encode(MessageGroup(listOf(parsed)), reportingContext)

            val body = encoded.messages.single().body as CompositeByteBuf
            val fixMsg = body.toString(StandardCharsets.US_ASCII)
            assertThat(fixMsg).isEqualTo(expectedRawMessage)
        }
    }

    private fun decodeTest(
        rawMessageString: String,
        dirtyMode: Boolean,
        delimiter: Char,
        expectedErrorText: String? = null,
        expectedSecondErrorText: String? = null,
        expectedMessage: ParsedMessage = parsedMessage,
        decodeToStringValues: Boolean = false
    ) {
        if (dirtyMode) {
            decodeTestDirty(
                rawMessageString.replaceSoh(delimiter),
                delimiter,
                expectedErrorText,
                expectedSecondErrorText,
                expectedMessage,
                decodeToStringValues
            )
        } else {
            decodeTestNonDirty(
                rawMessageString.replaceSoh(delimiter),
                delimiter,
                expectedErrorText,
                expectedMessage,
                decodeToStringValues
            )
        }
    }

    private fun decodeTestDirty(
        rawMessageString: String,
        delimiter: Char,
        expectedErrorText: String? = null,
        expectedSecondErrorText: String? = null,
        expectedMessage: ParsedMessage = parsedMessage,
        decodeValuesToStrings: Boolean = false
    ) {
        val expectedBody = expectedMessage.body
        val rawMessage = RawMessage(
            id = parsedMessage.id,
            eventId = parsedMessage.eventId,
            metadata = mapOf("encode-mode" to "dirty"),
            body = Unpooled.wrappedBuffer(rawMessageString.toByteArray(Charsets.US_ASCII))
        )

        val codec = createCodec(delimiter, decodeValuesToStrings)
        val decodedGroup = codec.decode(MessageGroup(listOf(rawMessage)), reportingContext)
        val parsedMessage = decodedGroup.messages.single() as ParsedMessage

        // we don't validate `CheckSum` and `BodyLength` in incorrect messages
        val fieldsToIgnore = if (expectedErrorText == null) emptyArray() else arrayOf("trailer.CheckSum", "header.BodyLength")
        val expected = if (decodeValuesToStrings) convertValuesToString(expectedBody) else expectedBody

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

    private fun decodeTestNonDirty(
        rawMessageString: String,
        delimiter: Char,
        expectedErrorText: String? = null,
        expectedMessage: ParsedMessage = parsedMessage,
        decodeValuesToStrings: Boolean = false
    ) {
        val expectedBody = expectedMessage.body
        val rawMessage = RawMessage(
            id = parsedMessage.id,
            eventId = parsedMessage.eventId,
            metadata = emptyMap(),
            body = Unpooled.wrappedBuffer(rawMessageString.toByteArray(Charsets.US_ASCII))
        )

        val codec = createCodec(delimiter, decodeValuesToStrings)
        if (expectedErrorText != null) {
            assertThatThrownBy {
                codec.decode(MessageGroup(listOf(rawMessage)), reportingContext)
            }.isInstanceOf(IllegalStateException::class.java).message().startsWith(expectedErrorText)
        } else {
            val decodedGroup = codec.decode(MessageGroup(listOf(rawMessage)), reportingContext)
            val parsedMessage = decodedGroup.messages.single() as ParsedMessage
            val expected = if (decodeValuesToStrings) convertValuesToString(expectedBody) else expectedBody
            assertThat(parsedMessage.body)
                .usingRecursiveComparison()
                .isEqualTo(expected)
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

    private val parsedMessageWithNestedGroups = ParsedMessage(
        MessageId("test_alias", Direction.OUTGOING, 0L, Instant.now(), emptyList()),
        EventId("test_id", "test_book", "test_scope", Instant.now()),
        "NestedGroupsTestMessage",
        mutableMapOf("encode-mode" to "dirty"),
        PROTOCOL,
        mutableMapOf(
            "header" to mutableMapOf(
                "BeginString" to "FIXT.1.1",
                "BodyLength" to 88,
                "MsgType" to "TEST_3",
                "MsgSeqNum" to 125,
                "TargetCompID" to "INET",
                "SenderCompID" to "MZHOT0"
            ),
            "OuterGroup" to mutableMapOf(
                "NoOrders" to mutableListOf(
                    mutableMapOf(
                        "NestedGroups" to mutableMapOf(
                            "NoBidDescriptors" to mutableListOf(
                                mutableMapOf("BidDescriptorType" to 1),
                                mutableMapOf("BidDescriptorType" to 2),
                                mutableMapOf("BidDescriptorType" to 3)
                            )
                        )
                    ),
                    mutableMapOf(
                        "NestedGroups" to mutableMapOf(
                            "NoBidDescriptors" to mutableListOf(
                                mutableMapOf("BidDescriptorType" to 3),
                                mutableMapOf("BidDescriptorType" to 2),
                                mutableMapOf("BidDescriptorType" to 1)
                            )
                        )
                    )
                )
            ),
            "trailer" to mapOf(
                "CheckSum" to "211"
            )
        )
    )

    private val parsedMessageWithTimezone = ParsedMessage(
        MessageId("test_alias", Direction.OUTGOING, 0L, Instant.now(), emptyList()),
        EventId("test_id", "test_book", "test_scope", Instant.now()),
        "TimeZoneTestMessage",
        mutableMapOf("encode-mode" to "dirty"),
        PROTOCOL,
        mutableMapOf(
            "header" to mutableMapOf(
                "MsgSeqNum" to 10947,
                "SenderCompID" to "SENDER",
                "SendingTime" to "2023-04-19T10:36:07.415088Z",
                "TargetCompID" to "RECEIVER",
                "BeginString" to "FIXT.1.1",
                "BodyLength" to 295,
                "MsgType" to "TEST_4"
            ),
            "TransactTime" to "2018-02-05T10:38:08.000008Z",
            "TotalVolumeTradedDate" to "2018-02-05Z",
            "TotalVolumeTradedTime" to "10:38:08.000008Z",
            "trailer" to mutableMapOf(
                "CheckSum" to "122"
            )
        )
    )

    companion object {
        private const val DIRTY_MODE_WARNING_PREFIX = "Dirty mode WARNING: "

        private const val MSG_CORRECT = "8=FIXT.1.19=29535=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=191"
        private const val MSG_CORRECT_WITHOUT_BODY = "8=FIX.4.29=5535=034=12549=MZHOT052=20240801-08:03:01.22956=INET10=039"
        private const val MSG_ADDITIONAL_FIELD_DICT = "8=FIXT.1.19=30535=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.000008461=1234510=143"
        private const val MSG_ADDITIONAL_FIELD_NO_DICT = "8=FIXT.1.19=30535=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.0000089999=5432110=097"
        private const val MSG_ADDITIONAL_FIELD_TAG = "8=FIXT.1.19=30635=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.0000089999=1234510=217"
        private const val MSG_REQUIRED_FIELD_REMOVED = "8=FIXT.1.19=28235=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508811=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=060"
        private const val MSG_DELIMITER_FIELD_IN_GROUP_REMOVED_IN_FIRST_ENTRY = "8=FIXT.1.19=28035=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=061"
        private const val MSG_DELIMITER_FIELD_IN_GROUP_REMOVED_IN_SECOND_ENTRY = "8=FIXT.1.19=28935=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=180"
        private const val MSG_WRONG_ENUM = "8=FIXT.1.19=29535=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=X39=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=231"
        private const val MSG_WRONG_TYPE = "8=FIXT.1.19=29635=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=Five14=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=181"
        private const val MSG_EMPTY_VAL = "8=FIXT.1.19=29135=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=251"
        private const val MSG_NON_PRINTABLE = "8=FIXT.1.19=30335=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test\taccount40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=171"
        private const val MSG_REQUIRED_HEADER_REMOVED = "8=FIXT.1.19=23635=817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=050"
        private const val MSG_TAG_OUT_OF_ORDER = "8=FIXT.1.19=29535=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=000999=500"

        private const val MSG_NESTED_REQ_COMPONENTS = "8=FIXT.1.19=5935=TEST_149=MZHOT056=INET34=12558=text_140=1151=123410=191"
        private const val MSG_NESTED_REQ_COMPONENTS_MISSED_REQ = "8=FIXT.1.19=5935=TEST_149=MZHOT056=INET34=12558=text_1151=123410=191"
        private const val MSG_NESTED_REQ_COMPONENTS_MISSED_OPTIONAL = "8=FIXT.1.19=5935=TEST_149=MZHOT056=INET34=12540=1151=123410=191"

        private const val MSG_NESTED_OPT_COMPONENTS = "8=FIXT.1.19=5935=TEST_249=MZHOT056=INET34=12558=text_140=1151=123410=191"
        private const val MSG_NESTED_OPT_COMPONENTS_MISSED_REQ = "8=FIXT.1.19=5935=TEST_249=MZHOT056=INET34=12558=text_1151=123410=191"
        private const val MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_FIELDS = "8=FIXT.1.19=5935=TEST_249=MZHOT056=INET34=125151=123410=191"
        private const val MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_FIELDS_INNER_AND_OUTER = "8=FIXT.1.19=5935=TEST_249=MZHOT056=INET34=12510=191"
        private const val MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_OUTER_FIELDS_AND_REQ_INNER_FIELD = "8=FIXT.1.19=5935=TEST_249=MZHOT056=INET34=12558=text_110=191"

        private const val MSG_NESTED_GROUPS = "8=FIXT.1.19=8835=TEST_349=MZHOT056=INET34=12573=2398=3399=1399=2399=3398=3399=3399=2399=110=211"
        private const val MSG_TIME_ZONE = "8=FIXT.1.19=12335=TEST_449=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508860=20180205-10:38:08.000008449=20180205450=10:38:0810=206"

        @JvmStatic
        fun configs() = listOf(
            Arguments.of(true, ''),
            Arguments.of(true, '|'),
            Arguments.of(false, ''),
            Arguments.of(false, '|'),
        )

        private fun String.replaceSoh(value: Char) = if (value == '') this else replace('', value)
    }
}