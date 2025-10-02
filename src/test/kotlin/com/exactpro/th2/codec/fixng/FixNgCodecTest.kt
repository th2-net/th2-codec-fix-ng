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

import com.exactpro.sf.common.messages.structures.IDictionaryStructure
import com.exactpro.sf.common.messages.structures.loaders.XmlDictionaryStructureLoader
import com.exactpro.sf.comparison.conversion.MultiConverter
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
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import kotlin.collections.set
import kotlin.math.max

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
    @CsvSource(
        "true,java.lang.Character,a,,287,072",
        "true,java.lang.Byte,127,,289,131",
        "true,java.lang.Short,32767,,291,235",
        "true,java.lang.Integer,2147483647,,296,245",
        "true,java.lang.Long,9223372036854775807,,305,198",
        "true,java.lang.Float,3.4028234,,295,174",
        "true,java.lang.Double,1.7976931348623157,,304,141",
        "true,java.math.BigDecimal,1.7976931348623157,,304,141",
        "true,java.time.LocalDateTime,2025-09-29T14:07:53.168966352,20250929-14:07:53.168966352,313,091",
        "true,java.time.LocalDate,2025-09-29,20250929,294,130",
        "true,java.time.LocalTime,14:07:53.168,,298,094", // FIXME: Seconds unit should depend of settings
        "true,java.lang.Boolean,true,Y,287,064",

        "false,java.lang.Character,a,,287,072",
        "false,java.lang.Byte,127,,289,131",
        "false,java.lang.Short,32767,,291,235",
        "false,java.lang.Integer,2147483647,,296,245",
        "false,java.lang.Long,9223372036854775807,,305,198",
        "false,java.lang.Float,3.4028234,,295,174",
        "false,java.lang.Double,1.7976931348623157,,304,141",
        "false,java.math.BigDecimal,1.7976931348623157,,304,141",
        "false,java.time.LocalDateTime,2025-09-29T14:07:53.168966352,20250929-14:07:53.168966352,313,091",
        "false,java.time.LocalDate,2025-09-29,20250929,294,130",
        "false,java.time.LocalTime,14:07:53.168,,298,094", // FIXME: Seconds unit should depend of settings
        "false,java.lang.Boolean,true,Y,287,064",
    )
    fun `encode with different value types for string field`(
        isDirty: Boolean,
        clazz: Class<*>,
        value: String,
        encodedValue: String?,
        length: Int,
        checkSum: String,
    ) {
        parsedBody["ExecID"] = MultiConverter.convert(value, clazz)
        encodeTest(
            MSG_CORRECT
                .replace("17=495504662", "17=${encodedValue ?: value}")
                .replace("9=295", "9=${length}")
                .replace("10=191", "10=${checkSum}"),
            dirtyMode = isDirty,
            '',
        )
    }

    @ParameterizedTest
    @CsvSource(
        "2025-09-29T14:07:53.123456789,20250929-14:07:53.123456789,298,130",
        "2025-09-29T14:07:53.123456780,20250929-14:07:53.123456780,298,121",
        "2025-09-29T14:07:53.123456700,20250929-14:07:53.123456700,298,113",
        "2025-09-29T14:07:53.123456000,20250929-14:07:53.123456000,298,106",
        "2025-09-29T14:07:53.123450000,20250929-14:07:53.123450000,298,100",
        "2025-09-29T14:07:53.123400000,20250929-14:07:53.123400000,298,095",
        "2025-09-29T14:07:53.123000000,20250929-14:07:53.123000000,298,091",
        "2025-09-29T14:07:53.120000000,20250929-14:07:53.120000000,298,088",
        "2025-09-29T14:07:53.100000000,20250929-14:07:53.100000000,298,086",
        "2025-09-29T14:07:53.000000000,20250929-14:07:53.000000000,298,085",

        "2025-09-29T14:07:53.12345,20250929-14:07:53.123450,295,209",
        "2025-09-29T14:07:53.1234,20250929-14:07:53.123400,295,204",

        "2025-09-29T14:07:53.12,20250929-14:07:53.120,292,050",
        "2025-09-29T14:07:53.1,20250929-14:07:53.100,292,048",

        "2025-09-29T14:07:53,20250929-14:07:53,288,118",
    )
    fun `encode local date time value with trailing zeros`(
        value: String,
        encodedValue: String,
        length: Int,
        checkSum: String,
    ) {
        parsedBody["TransactTime"] = value
        encodeTest(
            MSG_CORRECT
                .replace("60=20180205-10:38:08.000008", "60=${encodedValue}")
                .replace("9=295", "9=${length}")
                .replace("10=191", "10=${checkSum}"),
            dirtyMode = false,
            '',
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `simple encode from string values`(isDirty: Boolean, delimiter: Char) =
        encodeTest(MSG_CORRECT, isDirty, delimiter, encodeFromStringValues = true)

    @ParameterizedTest
    @MethodSource("configs")
    fun `simple decode`(isDirty: Boolean, delimiter: Char) =
        decodeTest(MSG_CORRECT, dirtyMode = isDirty, delimiter = delimiter)

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `body length is not int decode (dirty)`(delimiter: Char) {
        with(parsedMessage) {
            (body map "header").set("BodyLength", VALUE_NOT_INT_BODY_LENGTH)
            (body map "trailer").set("CheckSum", "069")
            decodeTest(
                MSG_WITH_NOT_INT_BODY_LENGTH,
                dirtyMode = true,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf(
                    "Wrong number value in integer field 'BodyLength'. Value: $VALUE_NOT_INT_BODY_LENGTH.",
                )
            )
        }
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `body length is not int decode (not dirty)`(delimiter: Char) {
        decodeTest(
            MSG_WITH_NOT_INT_BODY_LENGTH,
            dirtyMode = false,
            delimiter = delimiter,
            expectedErrors = listOf(
                "Wrong number value in integer field 'BodyLength'. Value: $VALUE_NOT_INT_BODY_LENGTH.",
            )
        )
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `body length less zero decode (dirty)`(delimiter: Char) {
        with(parsedMessage) {
            (body map "header").set("BodyLength", VALUE_BODY_LENGTH_LESS_ZERO)
            (body map "trailer").set("CheckSum", "173")
            decodeTest(
                MSG_WITH_BODY_LENGTH_LESS_ZERO,
                dirtyMode = true,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf(
                    "BodyLength (9) field must have positive or zero value instead of $VALUE_BODY_LENGTH_LESS_ZERO",
                )
            )
        }
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `body length less zero decode (not dirty)`(delimiter: Char) {
        decodeTest(
            MSG_WITH_BODY_LENGTH_LESS_ZERO,
            dirtyMode = false,
            delimiter = delimiter,
            expectedErrors = listOf(
                "BodyLength (9) field must have positive or zero value instead of $VALUE_BODY_LENGTH_LESS_ZERO",
            )
        )
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `body length greater real body decode (dirty)`(delimiter: Char) {
        with(parsedMessage) {
            (body map "header").set("BodyLength", VALUE_BODY_LENGTH_GREATER_REAL_BODY)
            (body map "trailer").set("CheckSum", "202")
            decodeTest(
                MSG_WITH_BODY_LENGTH_GREATER_REAL_BODY,
                dirtyMode = true,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf(
                    "BodyLength (9) field value $VALUE_BODY_LENGTH_GREATER_REAL_BODY is grater than real message body 295",
                )
            )
        }
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `body length greater real body decode (not dirty)`(delimiter: Char) {
        decodeTest(
            MSG_WITH_BODY_LENGTH_GREATER_REAL_BODY,
            dirtyMode = false,
            delimiter = delimiter,
            expectedErrors = listOf(
                "BodyLength (9) field value $VALUE_BODY_LENGTH_GREATER_REAL_BODY is grater than real message body 295",
            )
        )
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `no checksum after body decode (dirty)`(delimiter: Char) {
        with(parsedMessage) {
            (body map "header").set("BodyLength", VALUE_NO_CHECKSUM_AFTER_BODY)
            (body map "trailer").set("CheckSum", "190")
            decodeTest(
                MSG_WITH_NO_CHECKSUM_AFTER_BODY,
                dirtyMode = true,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf(
                    "BodyLength (9) must forward to the CheckSum (10) instead of 60 tag",
                )
            )
        }
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `no checksum after body decode (not dirty)`(delimiter: Char) {
        decodeTest(
            MSG_WITH_NO_CHECKSUM_AFTER_BODY,
            dirtyMode = false,
            delimiter = delimiter,
            expectedErrors = listOf(
                "BodyLength (9) must forward to the CheckSum (10) instead of 60 tag",
            )
        )
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `short check sum value decode (dirty)`(delimiter: Char) {
        with(parsedMessage) {
            (body map "header").set("BodyLength", 296)
            body.set("ExecID", "4955046999")
            (body map "trailer").set("CheckSum", VALUE_SHORT_CHECK_SUM)
            decodeTest(
                MSG_WITH_SHORT_CHECK_SUM,
                dirtyMode = true,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf(
                    "CheckSum (10) field must have 3 bytes length, instead of size: 1, value: '$VALUE_SHORT_CHECK_SUM'",
                )
            )
        }
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `short check sum value decode (not dirty)`(delimiter: Char) {
        decodeTest(
            MSG_WITH_SHORT_CHECK_SUM,
            dirtyMode = false,
            delimiter = delimiter,
            expectedErrors = listOf(
                "CheckSum (10) field must have 3 bytes length, instead of size: 1, value: '3'",
            )
        )
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `too big check sum value decode (dirty)`(delimiter: Char) {
        with(parsedMessage) {
            (body map "trailer").set("CheckSum", VALUE_TOO_BIG_CHECK_SUM)
            decodeTest(
                MSG_WITH_TOO_BIG_CHECK_SUM,
                dirtyMode = true,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf(
                    "CheckSum (10) field must have value from 0 to 255 included both limits instead of '$VALUE_TOO_BIG_CHECK_SUM' value",
                )
            )
        }
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `too big check sum value decode (not dirty)`(delimiter: Char) {
        decodeTest(
            MSG_WITH_TOO_BIG_CHECK_SUM,
            dirtyMode = false,
            delimiter = delimiter,
            expectedErrors = listOf(
                "CheckSum (10) field must have value from 0 to 255 included both limits instead of '$VALUE_TOO_BIG_CHECK_SUM' value",
            )
        )
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `incorrect check sum value decode (dirty)`(delimiter: Char) {
        with(parsedMessage) {
            (body map "trailer").set("CheckSum", VALUE_INCORRECT_CHECK_SUM)
            decodeTest(
                MSG_WITH_INCORRECT_CHECK_SUM,
                dirtyMode = true,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf(
                    "CheckSum (10) field has $VALUE_INCORRECT_CHECK_SUM value which isn't matched to calculated value 191",
                )
            )
        }
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `incorrect check sum value decode (not dirty)`(delimiter: Char) {
        decodeTest(
            MSG_WITH_INCORRECT_CHECK_SUM,
            dirtyMode = false,
            delimiter = delimiter,
            expectedErrors = listOf(
                "CheckSum (10) field has $VALUE_INCORRECT_CHECK_SUM value which isn't matched to calculated value 191",
            )
        )
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    @Suppress("SpellCheckingInspection")
    fun `tags with 0 prefix decode (dirty)`(delimiter: Char) {
        with(parsedMessage) {
            (body map "header").set("BodyLength", 302)
            (body map "trailer").set("CheckSum", "100")
            decodeTest(
                MSG_WITH_0_PREFIX,
                dirtyMode = true,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf(
                    "Tag with zero prefix at offset: 0, raw: '08=FIXT.1....'",
                    "Tag with zero prefix at offset: 12, raw: '...09=302${delimiter}035...'",
                    "Tag with zero prefix at offset: 19, raw: '...035=8${delimiter}49=S...'",
                    "Tag with zero prefix at offset: 47, raw: '...034=10947${delimiter}...'",
                    "Tag with zero prefix at offset: 98, raw: '...011=zSuNbr...'",
                    "Tag with zero prefix at offset: 186, raw: '...0453=2${delimiter}044...'",
                    "Tag with zero prefix at offset: 193, raw: '...0448=NGALL...'",
                    "Tag with zero prefix at offset: 215, raw: '...0452=76${delimiter}44...'",
                    "Tag with zero prefix at offset: 229, raw: '...0447=P${delimiter}452...'",
                    "Tag with zero prefix at offset: 321, raw: '...010=100${delimiter}'",
                ),
            )
        }
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `tags with 0 prefix decode (not dirty)`(delimiter: Char) =
        decodeTest(
            MSG_WITH_0_PREFIX,
            dirtyMode = false,
            delimiter = delimiter,
            expectedErrors = listOf("Tag with zero prefix at offset: 0, raw: '08=FIXT.1....'"),
        )

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
        with(parsedMessage) {
            body.set("CFICode", "12345")
            (body map "header").set("BodyLength", 305)
            (body map "trailer").set("CheckSum", "143")
            decodeTest(
                MSG_ADDITIONAL_FIELD_DICT,
                dirtyMode = true,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf("Unexpected field in message. Field name: CFICode. Field value: 12345."),
            )
        }
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
            expectedErrors = listOf("Required tag missing. Tag: 10."),
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
        with(parsedMessage) {
            body.set("9999", "54321")
            (body map "header").set("BodyLength", 306)
            (body map "trailer").set("CheckSum", "217")
            decodeTest(
                MSG_ADDITIONAL_FIELD_NO_DICT,
                dirtyMode = true,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf("Field does not exist in dictionary. Field tag: 9999. Field value: 54321."),
            )
        }
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
            expectedErrors = listOf("Required tag missing. Tag: 10."),
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
        with(parsedMessage) {
            body remove "ExecID"
            (body map "header").set("BodyLength", 282)
            (body map "trailer").set("CheckSum", "060")
            parsedBody.remove("ExecID")
            decodeTest(
                MSG_REQUIRED_FIELD_REMOVED,
                dirtyMode = isDirty,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf("Required tag missing. Tag: 17."),
            )
        }
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
        with(parsedMessage) {
            body map "TradingParty" list "NoPartyIDs" map 0 remove "PartyID"
            (body map "header").set("BodyLength", 280)
            (body map "trailer").set("CheckSum", "061")
            decodeTest(
                MSG_DELIMITER_FIELD_IN_GROUP_REMOVED_IN_FIRST_ENTRY,
                dirtyMode = isDirty,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf("Field PartyIDSource (447) appears before delimiter (448)")
            )
        }
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
        with(parsedMessage) {
            body map "TradingParty" list "NoPartyIDs" map 1 remove "PartyID"
            (body map "header").set("BodyLength", 289)
            (body map "trailer").set("CheckSum", "180")
            decodeTest(
                MSG_DELIMITER_FIELD_IN_GROUP_REMOVED_IN_SECOND_ENTRY,
                dirtyMode = isDirty,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf("Field PartyIDSource (447) appears before delimiter (448)"),
            )
        }
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
        with(parsedMessage) {
            body.set("ExecType", 'X')
            (body map "trailer").set("CheckSum", "231")
            decodeTest(
                MSG_WRONG_ENUM,
                dirtyMode = isDirty,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf("Invalid value in enum field ExecType. Actual: X."),
            )
        }
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
        with(parsedMessage) {
            body.set("LeavesQty", "Five")
            (body map "header").set("BodyLength", 296)
            (body map "trailer").set("CheckSum", "181")
            decodeTest(
                MSG_WRONG_TYPE,
                dirtyMode = isDirty,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf("Wrong number value in java.math.BigDecimal field 'LeavesQty'. Value: Five."),
            )
        }
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
        with(parsedMessage) {
            body.set("Account", "")
            (body map "header").set("BodyLength", 291)
            (body map "trailer").set("CheckSum", "251")
            decodeTest(
                MSG_EMPTY_VAL,
                dirtyMode = true,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf("Empty value in the field 'Account'.")
            )
        }
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `decode with empty value (non dirty)`(delimiter: Char) {
        parsedBody["Account"] = ""
        decodeTest(
            MSG_EMPTY_VAL,
            dirtyMode = false,
            delimiter = delimiter,
            expectedErrors = listOf("No valid value at offset: 235"),
        )
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
        with(parsedMessage) {
            body.set("Account", "test\taccount")
            (body map "header").set("BodyLength", 303)
            (body map "trailer").set("CheckSum", "171")
            decodeTest(
                MSG_NON_PRINTABLE,
                dirtyMode = isDirty,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf("Non printable characters in the field 'Account'."),
            )
        }
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
        with(parsedMessage) {
            body remove "OuterComponent"
            (body map "trailer").set("LegUnitOfMeasure", "500")
            (body map "header").set("BodyLength", 295)
            (body map "trailer").set("CheckSum", "000")
            decodeTest(
                MSG_TAG_OUT_OF_ORDER,
                dirtyMode = true,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf(
                    "Unexpected field in message. Field name: LegUnitOfMeasure. Field value: 500",
                    "Message ends with 999 tag instead of CheckSum (10)",
                ),
            )
        }
    }

    @ParameterizedTest
    @ValueSource(chars = ['', '|'])
    fun `tag appears out of order (non dirty)`(delimiter: Char) {
        decodeTest(
            MSG_TAG_OUT_OF_ORDER,
            dirtyMode = false,
            delimiter = delimiter,
            expectedErrors = listOf("Tag appears out of order: 999"),
        )
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
        with(parsedMessageWithNestedComponents) {
            body map "OuterComponent" map "InnerComponent" remove "OrdType"
            (body map "header").set("BodyLength", 54)
            (body map "trailer").set("CheckSum", "231")
            decodeTest(
                MSG_NESTED_REQ_COMPONENTS_MISSED_REQ,
                dirtyMode = isDirty,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf("Required tag missing. Tag: 40."),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with missing optional field in req nested component`(isDirty: Boolean, delimiter: Char) {
        with(parsedMessageWithNestedComponents) {
            body map "OuterComponent" map "InnerComponent" remove "Text"
            (body map "header").set("BodyLength", 49)
            (body map "trailer").set("CheckSum", "190")
            decodeTest(
                MSG_NESTED_REQ_COMPONENTS_MISSED_OPTIONAL,
                dirtyMode = isDirty,
                delimiter = delimiter,
                expectedMessage = this,
            )
        }
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
        with(convertToOptionalComponent()) {
            (body map "trailer").set("CheckSum", "192")
            decodeTest(MSG_NESTED_OPT_COMPONENTS, dirtyMode = isDirty, delimiter = delimiter, expectedMessage = this)
        }
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with missing req field in opt nested component`(isDirty: Boolean, delimiter: Char) {
        with(convertToOptionalComponent()) {
            body map "OuterComponent" map "InnerComponent" remove "OrdType"
            (body map "header").set("BodyLength", 54)
            (body map "trailer").set("CheckSum", "232")
            decodeTest(
                MSG_NESTED_OPT_COMPONENTS_MISSED_REQ,
                dirtyMode = isDirty,
                delimiter = delimiter,
                expectedErrors = listOf("Required tag missing. Tag: 40."),
                expectedMessage = this
            )
        }
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with missing all fields in opt nested component`(isDirty: Boolean, delimiter: Char) {
        with(convertToOptionalComponent()) {
            body map "OuterComponent" remove "InnerComponent"
            (body map "header").set("BodyLength", 44)
            (body map "trailer").set("CheckSum", "231")
            decodeTest(
                MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_FIELDS,
                dirtyMode = isDirty,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf("Required tag missing. Tag: 40.")
            )
        }
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with missing all fields in inner and outer nested components`(isDirty: Boolean, delimiter: Char) {
        with(convertToOptionalComponent()) {
            body remove "OuterComponent"
            (body map "header").set("BodyLength", 35)
            (body map "trailer").set("CheckSum", "072")
            decodeTest(
                MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_FIELDS_INNER_AND_OUTER,
                dirtyMode = isDirty,
                delimiter = delimiter,
                expectedMessage = this
            )
        }
    }

    @ParameterizedTest
    @MethodSource("configs")
    fun `decode with missing req fields in both inner and outer components`(isDirty: Boolean, delimiter: Char) {
        with(convertToOptionalComponent()) {
            body map "OuterComponent" remove "LeavesQty"
            body map "OuterComponent" map "InnerComponent" remove "OrdType"
            (body map "header").set("BodyLength", 45)
            (body map "trailer").set("CheckSum", "073")
            decodeTest(
                MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_OUTER_FIELDS_AND_REQ_INNER_FIELD,
                dirtyMode = isDirty,
                delimiter = delimiter,
                expectedMessage = this,
                expectedErrors = listOf("Required tag missing. Tag: 40.", "Required tag missing. Tag: 151."),
            )
        }
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

    @ParameterizedTest
    @MethodSource("configs")
    // https://github.com/th2-net/th2-codec-fix-ng/issues/43
    fun `encode true boolean`(isDirty: Boolean, delimiter: Char) =
        encodeTest(MSG_LOGON, isDirty, delimiter, parsedMessage = parsedLogon)

    @ParameterizedTest
    @CsvSource(
        ignoreLeadingAndTrailingWhitespace = false, value = [
            "true,,true",
            "true,,TrUe",
            "true,|,true",
            "true,|,TrUe",
            "false,,true",
            "false,,TrUe",
            "false,|,true",
            "false,|,TrUe",
        ]
    )
    // https://github.com/th2-net/th2-codec-fix-ng/issues/43
    fun `encode true string`(isDirty: Boolean, delimiter: Char, value: String) = with(parsedLogon) {
        body.set("ResetSeqNumFlag", value)
        encodeTest(
            expectedRawMessage = MSG_LOGON,
            dirtyMode = isDirty,
            delimiter = delimiter,
            parsedMessage = this
        )
    }

    @ParameterizedTest
    @MethodSource("configs")
    // https://github.com/th2-net/th2-codec-fix-ng/issues/43
    fun `encode false boolean`(isDirty: Boolean, delimiter: Char) = with(parsedLogon) {
        body.set("ResetSeqNumFlag", false)
        encodeTest(
            expectedRawMessage = MSG_LOGON
                .replace("141=Y", "141=N")
                .replace("10=213", "10=202"),
            dirtyMode = isDirty,
            delimiter = delimiter,
            parsedMessage = this
        )
    }

    @ParameterizedTest
    @CsvSource(
        ignoreLeadingAndTrailingWhitespace = false, value = [
            "true,,false",
            "true,,FaLsE",
            "true,|,false",
            "true,|,FaLsE",
            "false,,false",
            "false,,FaLsE",
            "false,|,false",
            "false,|,FaLsE",
        ]
    )
    // https://github.com/th2-net/th2-codec-fix-ng/issues/43
    fun `encode false string`(isDirty: Boolean, delimiter: Char, value: String) = with(parsedLogon) {
        body.set("ResetSeqNumFlag", value)
        encodeTest(
            expectedRawMessage = MSG_LOGON
                .replace("141=Y", "141=N")
                .replace("10=213", "10=202"),
            dirtyMode = isDirty,
            delimiter = delimiter,
            parsedMessage = this
        )
    }

    @ParameterizedTest
    @CsvSource(
        ignoreLeadingAndTrailingWhitespace = false, value = [
            "true,,y",
            "true,,Y",
            "true,|,y",
            "true,|,Y",
            "false,,y",
            "false,,Y",
            "false,|,y",
            "false,|,Y",
        ]
    )
    // https://github.com/th2-net/th2-codec-fix-ng/issues/43
    fun `encode Y string`(isDirty: Boolean, delimiter: Char, value: String) = with(parsedLogon) {
        body.set("ResetSeqNumFlag", value)
        encodeTest(
            expectedRawMessage = MSG_LOGON,
            dirtyMode = isDirty,
            delimiter = delimiter,
            parsedMessage = this
        )
    }

    @ParameterizedTest
    @CsvSource(
        ignoreLeadingAndTrailingWhitespace = false, value = [
            "true,,n",
            "true,,N",
            "true,|,n",
            "true,|,N",
            "false,,n",
            "false,,N",
            "false,|,n",
            "false,|,N",
        ]
    )
    // https://github.com/th2-net/th2-codec-fix-ng/issues/43
    fun `encode N string`(isDirty: Boolean, delimiter: Char, value: String) = with(parsedLogon) {
        body.set("ResetSeqNumFlag", value)
        encodeTest(
            expectedRawMessage = MSG_LOGON
                .replace("141=Y", "141=N")
                .replace("10=213", "10=202"),
            dirtyMode = isDirty,
            delimiter = delimiter,
            parsedMessage = this
        )
    }

    private fun createCodec(delimiter: Char = '', decodeValuesToStrings: Boolean = false): FixNgCodec {
        return FixNgCodec(dictionary, FixNgCodecSettings(
            dictionary = "",
            decodeValuesToStrings = decodeValuesToStrings,
            decodeDelimiter = delimiter,
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
        encodeFromStringValues: Boolean = false,
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
        encodeFromStringValues: Boolean = false,
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
        expectedErrors: List<String> = emptyList(),
        expectedMessage: ParsedMessage = parsedMessage,
        decodeToStringValues: Boolean = false
    ) {
        if (dirtyMode) {
            decodeTestDirty(
                rawMessageString.replaceSoh(delimiter),
                delimiter,
                expectedErrors,
                expectedMessage,
                decodeToStringValues
            )
        } else {
            decodeTestNonDirty(
                rawMessageString.replaceSoh(delimiter),
                delimiter,
                expectedErrors.firstOrNull(),
                expectedMessage,
                decodeToStringValues
            )
        }
    }

    private fun decodeTestDirty(
        rawMessageString: String,
        delimiter: Char,
        expectedErrors: List<String> = emptyList(),
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

        val expected = if (decodeValuesToStrings) convertValuesToString(expectedBody) else expectedBody

        assertThat(parsedMessage.body)
            .usingRecursiveComparison()
            .isEqualTo(expected)

        if (expectedErrors.isEmpty()) {
            assertThat(reportingContext.warnings).isEmpty()
        } else {
            assertAll(
                (0 until max(expectedErrors.size, reportingContext.warnings.size)).asSequence()
                    .map { index ->
                        Executable {
                            val expected = expectedErrors.getOrNull(index)
                            val actual = reportingContext.warnings.getOrNull(index)
                            assertNotNull(expected) { "No expected text for actual warning[$index] '$actual'" }
                            assertNotNull(actual) { "No actual warning for expected text[$index] '$expected'" }
                            assertThat(actual).describedAs {
                                "Expected text doesn't match to actual warning, index: $index"
                            }.startsWith(DIRTY_MODE_WARNING_PREFIX + expected)
                        }
                    }.toList()
            )
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

    @Suppress("SpellCheckingInspection")
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

    @Suppress("SpellCheckingInspection")
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

    @Suppress("SpellCheckingInspection")
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
            "trailer" to mutableMapOf(
                "CheckSum" to "191"
            )
        )
    )
    private val parsedBodyWithNestedComponents: MutableMap<String, Any?> = parsedMessageWithNestedComponents.body as MutableMap

    @Suppress("SpellCheckingInspection")
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

    @Suppress("SpellCheckingInspection")
    private val parsedLogon = ParsedMessage(
        MessageId("test_alias", Direction.OUTGOING, 0L, Instant.now(), emptyList()),
        EventId("test_id", "test_book", "test_scope", Instant.now()),
        "Logon",
        mutableMapOf(),
        PROTOCOL,
        mutableMapOf(
            "header" to mutableMapOf(
                "MsgSeqNum" to 1,
                "SenderCompID" to "SENDER",
                "SendingTime" to "2025-07-01T03:30:59.831",
                "TargetCompID" to "RECEIVER",
                "BeginString" to "FIXT.1.1",
                "BodyLength" to 82,
                "MsgType" to "Logon"
            ),
            "EncryptMethod" to 0,
            "HeartBtInt" to 30,
            "ResetSeqNumFlag" to true,
            "DefaultApplVerID" to "9",
            "trailer" to mutableMapOf(
                "CheckSum" to "229"
            )
        )
    )

    @Suppress("UNCHECKED_CAST")
    private infix fun Map<String, Any?>.map(name: String): Map<String, Any?> = get(name) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private infix fun Map<String, Any?>.list(name: String): List<Any?> = get(name) as List<Any?>
    @Suppress("UNCHECKED_CAST")
    private infix fun Map<String, Any?>.remove(name: String) { (this as MutableMap<String, Any?>).remove(name) }
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.set(name: String, value: Any) { (this as MutableMap<String, Any?>).put(name, value) }
    @Suppress("UNCHECKED_CAST")
    private infix fun List<Any?>.map(index: Int): Map<String, Any?> = get(index) as Map<String, Any?>

    companion object {
        private const val DIRTY_MODE_WARNING_PREFIX = "Dirty mode WARNING: "

        private const val VALUE_NOT_INT_BODY_LENGTH = "abc"
        private const val VALUE_BODY_LENGTH_LESS_ZERO = -10
        private const val VALUE_BODY_LENGTH_GREATER_REAL_BODY = 999
        private const val VALUE_NO_CHECKSUM_AFTER_BODY = 267
        private const val VALUE_SHORT_CHECK_SUM = "3"
        private const val VALUE_TOO_BIG_CHECK_SUM = "999"
        private const val VALUE_INCORRECT_CHECK_SUM = "255"

        private const val MSG_LOGON = "8=FIXT.1.19=8235=A49=SENDER56=RECEIVER34=152=20250701-03:30:59.83198=0108=30141=Y1137=910=213"

        private const val MSG_CORRECT = "8=FIXT.1.19=29535=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=191"
        private const val MSG_WITH_SHORT_CHECK_SUM = "8=FIXT.1.19=29635=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=495504699911=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=$VALUE_SHORT_CHECK_SUM"
        private const val MSG_WITH_TOO_BIG_CHECK_SUM = "8=FIXT.1.19=29535=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=$VALUE_TOO_BIG_CHECK_SUM"
        private const val MSG_WITH_INCORRECT_CHECK_SUM = "8=FIXT.1.19=29535=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=$VALUE_INCORRECT_CHECK_SUM"
        private const val MSG_WITH_NOT_INT_BODY_LENGTH = "8=FIXT.1.19=$VALUE_NOT_INT_BODY_LENGTH35=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=069"
        private const val MSG_WITH_BODY_LENGTH_LESS_ZERO = "8=FIXT.1.19=$VALUE_BODY_LENGTH_LESS_ZERO35=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=173"
        private const val MSG_WITH_BODY_LENGTH_GREATER_REAL_BODY = "8=FIXT.1.19=$VALUE_BODY_LENGTH_GREATER_REAL_BODY35=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=202"
        private const val MSG_WITH_NO_CHECKSUM_AFTER_BODY = "8=FIXT.1.19=$VALUE_NO_CHECKSUM_AFTER_BODY35=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.00000810=190"
        private const val MSG_WITH_0_PREFIX = "08=FIXT.1.109=302035=849=SENDER56=RECEIVER034=1094752=20230419-10:36:07.41508817=495504662011=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=80453=20448=NGALL1FX01447=D0452=76448=00447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.000008010=100"
        private const val MSG_CORRECT_WITHOUT_BODY = "8=FIX.4.29=5535=034=12549=MZHOT052=20240801-08:03:01.22956=INET10=039"
        private const val MSG_ADDITIONAL_FIELD_DICT = "8=FIXT.1.19=30535=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.000008461=1234510=143"
        private const val MSG_ADDITIONAL_FIELD_NO_DICT = "8=FIXT.1.19=30635=849=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508817=49550466211=zSuNbrBIZyVljs41=zSuNbrBIZyVljs37=49415882150=039=0151=50014=50048=NWDR22=8453=2448=NGALL1FX01447=D452=76448=0447=P452=31=test40=A59=054=B55=ABC38=50044=100047=50060=20180205-10:38:08.0000089999=5432110=217"
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
        private const val MSG_NESTED_REQ_COMPONENTS_MISSED_REQ = "8=FIXT.1.19=5435=TEST_149=MZHOT056=INET34=12558=text_1151=123410=231"
        private const val MSG_NESTED_REQ_COMPONENTS_MISSED_OPTIONAL = "8=FIXT.1.19=4935=TEST_149=MZHOT056=INET34=12540=1151=123410=190"

        private const val MSG_NESTED_OPT_COMPONENTS = "8=FIXT.1.19=5935=TEST_249=MZHOT056=INET34=12558=text_140=1151=123410=192"
        private const val MSG_NESTED_OPT_COMPONENTS_MISSED_REQ = "8=FIXT.1.19=5435=TEST_249=MZHOT056=INET34=12558=text_1151=123410=232"
        private const val MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_FIELDS = "8=FIXT.1.19=4435=TEST_249=MZHOT056=INET34=125151=123410=231"
        private const val MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_FIELDS_INNER_AND_OUTER = "8=FIXT.1.19=3535=TEST_249=MZHOT056=INET34=12510=072"
        private const val MSG_NESTED_OPT_COMPONENTS_MISSED_ALL_OUTER_FIELDS_AND_REQ_INNER_FIELD = "8=FIXT.1.19=4535=TEST_249=MZHOT056=INET34=12558=text_110=073"

        private const val MSG_NESTED_GROUPS = "8=FIXT.1.19=8835=TEST_349=MZHOT056=INET34=12573=2398=3399=1399=2399=3398=3399=3399=2399=110=211"
        private const val MSG_TIME_ZONE = "8=FIXT.1.19=13035=TEST_449=SENDER56=RECEIVER34=1094752=20230419-10:36:07.41508860=20180205-10:38:08.000008449=20180205450=10:38:08.00000810=034"

        @JvmStatic
        fun configs() = listOf(
            Arguments.of(true, ''),
            Arguments.of(true, '|'),
            Arguments.of(false, ''),
            Arguments.of(false, '|'),
        )
    }
}