/*
 * Copyright 2024 Exactpro (Exactpro Systems Limited)
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
import com.exactpro.th2.codec.api.IPipelineCodec
import com.exactpro.th2.codec.api.IReportingContext
import com.exactpro.th2.codec.fixng.FixNgCodecFactory.Companion.PROTOCOL
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Direction
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.EventId
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageGroup
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageId
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.ParsedMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

@State(Scope.Benchmark)
open class BenchmarkState {
    lateinit var codecTyped: IPipelineCodec
    lateinit var codecString: IPipelineCodec
    private lateinit var rawBody: ByteBuf
    lateinit var rawGroup: MessageGroup
    lateinit var parsedGroupTyped: MessageGroup
    lateinit var parsedGroupString: MessageGroup

    @Setup(Level.Trial)
    fun setup() {
        val dictionary: IDictionaryStructure = FixNgCodec::class.java.classLoader
            .getResourceAsStream("dictionary-benchmark.xml")
            .use(XmlDictionaryStructureLoader()::load)

        codecTyped = FixNgCodec(dictionary, FixNgCodecSettings(dictionary = "", decodeValuesToStrings = false))
        codecString = FixNgCodec(dictionary, FixNgCodecSettings(dictionary = "", decodeValuesToStrings = true))
        rawBody = Unpooled.wrappedBuffer(MSG_CORRECT.toByteArray(Charsets.US_ASCII))
        rawGroup = MessageGroup(listOf(RawMessage(id = parsedMessageTyped.id, eventId = parsedMessageTyped.eventId, body = rawBody, metadata = mutableMapOf("encode-mode" to "dirty"))))
        parsedGroupTyped = MessageGroup(listOf(parsedMessageTyped))
        parsedGroupString = MessageGroup(listOf(parsedMessageString))
    }

    @Setup(Level.Invocation)
    fun resetReader() {
        rawBody.resetReaderIndex()
    }

    companion object {
        private const val MSG_CORRECT = "8=FIXT.1.1\u00019=295\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=191\u0001"

        private val parsedMessageTyped = ParsedMessage(
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

        @Suppress("UNCHECKED_CAST")
        private val parsedMessageString = ParsedMessage(
            MessageId("test_alias", Direction.OUTGOING, 0L, Instant.now(), emptyList()),
            EventId("test_id", "test_book", "test_scope", Instant.now()),
            "ExecutionReport",
            mutableMapOf(/*"encode-mode" to "dirty"*/),
            PROTOCOL,
            convertValuesToString(parsedMessageTyped.body) as MutableMap<String, Any>
        )

        private fun convertValuesToString(value: Any?): Any = when (value) {
            is Map<*, *> -> value.mapValues { convertValuesToString(it.value) }
            is List<*> -> value.map(::convertValuesToString)
            else -> value.toString()
        }
    }
}

open class FixNgCodecBenchmark {
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun encodeFixMessageTyped(state: BenchmarkState) {
        state.codecTyped.encode(state.parsedGroupTyped, context)
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun parseFixMessageTyped(state: BenchmarkState) {
        state.codecTyped.decode(state.rawGroup, context)
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun encodeFixMessageString(state: BenchmarkState) {
        state.codecString.encode(state.parsedGroupString, context)
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun parseFixMessageString(state: BenchmarkState) {
        state.codecString.decode(state.rawGroup, context)
    }

    companion object {
         private val context = object : IReportingContext {
            override fun warning(message: String) { }
            override fun warnings(messages: Iterable<String>) { }
        }
    }
}