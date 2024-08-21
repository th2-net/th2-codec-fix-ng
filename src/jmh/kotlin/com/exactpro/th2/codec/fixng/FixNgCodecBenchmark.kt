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
import java.time.Instant

@State(Scope.Benchmark)
open class BenchmarkState {
    lateinit var codec: IPipelineCodec
    lateinit var rawBody: ByteBuf
    lateinit var rawGroup: MessageGroup
    lateinit var parsedGroup: MessageGroup

    @Setup(Level.Trial)
    fun setup() {
        val dictionary: IDictionaryStructure = FixNgCodecTest::class.java.classLoader
            .getResourceAsStream("dictionary-benchmark.xml")
            .use(XmlDictionaryStructureLoader()::load)

        codec = FixNgCodec(dictionary, FixNgCodecSettings(dictionary = "", decodeValuesToStrings = true))
        rawBody = Unpooled.wrappedBuffer(MSG_CORRECT.toByteArray(Charsets.US_ASCII))
        rawGroup = MessageGroup(listOf(RawMessage(id = parsedMessage.id, eventId = parsedMessage.eventId, body = rawBody)))
        parsedGroup = MessageGroup(listOf(parsedMessage))
    }

    @Setup(Level.Invocation)
    fun resetReader() {
        rawBody.resetReaderIndex()
    }

    companion object {
        private const val MSG_CORRECT = "8=FIXT.1.1\u00019=295\u000135=8\u000149=SENDER\u000156=RECEIVER\u000134=10947\u000152=20230419-10:36:07.415088\u000117=495504662\u000111=zSuNbrBIZyVljs\u000141=zSuNbrBIZyVljs\u000137=49415882\u0001150=0\u000139=0\u0001151=500\u000114=500\u000148=NWDR\u000122=8\u0001453=2\u0001448=NGALL1FX01\u0001447=D\u0001452=76\u0001448=0\u0001447=P\u0001452=3\u00011=test\u000140=A\u000159=0\u000154=B\u000155=ABC\u000138=500\u000144=1000\u000147=500\u000160=20180205-10:38:08.000008\u000110=191\u0001"
        private val parsedMessage = ParsedMessage(
            MessageId("test_alias", Direction.OUTGOING, 0L, Instant.now(), emptyList()),
            EventId("test_id", "test_book", "test_scope", Instant.now()),
            "ExecutionReport",
            mapOf("encode-mode" to "dirty"),
            PROTOCOL,
            mapOf(
                "header" to mapOf(
                    "MsgSeqNum" to "10947",
                    "SenderCompID" to "SENDER",
                    "SendingTime" to "2023-04-19T10:36:07.415088",
                    "TargetCompID" to "RECEIVER",
                    "BeginString" to "FIXT.1.1",
                    "BodyLength" to "295",
                    "MsgType" to "8"
                ),
                "ExecID" to "495504662",
                "ClOrdID" to "zSuNbrBIZyVljs",
                "OrigClOrdID" to "zSuNbrBIZyVljs",
                "OrderID" to "49415882",
                "ExecType" to '0',
                "OrdStatus" to '0',
                "LeavesQty" to "500",
                "CumQty" to "500",
                "SecurityID" to "NWDR",
                "SecurityIDSource" to "8",
                "TradingParty" to mapOf(
                    "NoPartyIDs" to listOf(
                        mapOf(
                            "PartyID" to "NGALL1FX01",
                            "PartyIDSource" to "D",
                            "PartyRole" to "76"
                        ),
                        mapOf(
                            "PartyID" to "0",
                            "PartyIDSource" to "P",
                            "PartyRole" to "3"
                        )
                    )
                ),
                "Account" to "test",
                "OrdType" to "A",
                "TimeInForce" to "0",
                "Side" to "B",
                "Symbol" to "ABC",
                "OrderQty" to "500",
                "Price" to "1000",
                "Unknown" to "500",
                "TransactTime" to "2018-02-05T10:38:08.000008",
                "trailer" to mapOf(
                    "CheckSum" to "191"
                )
            )
        )
    }
}

open class FixNgCodecBenchmark {
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun encodeFixMessage(state: BenchmarkState) {
        state.codec.encode(state.parsedGroup, context)
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun parseFixMessage(state: BenchmarkState) {
        state.codec.decode(state.rawGroup, context)
    }

    companion object {
         private val context = object : IReportingContext {
            override fun warning(message: String) { }
            override fun warnings(messages: Iterable<String>) { }
        }
    }
}