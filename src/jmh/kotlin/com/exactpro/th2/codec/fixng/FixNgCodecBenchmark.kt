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
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageGroup
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

@State(Scope.Benchmark)
open class BenchmarkState {
    lateinit var codec: IPipelineCodec
    lateinit var rawBody: ByteBuf
    lateinit var rawGroup: MessageGroup
    lateinit var parsedGroup: MessageGroup

    @Setup(Level.Trial)
    fun setup() {
        val dictionary: IDictionaryStructure = FixNgCodecTest::class.java.classLoader
            .getResourceAsStream("dictionary.xml")
            .use(XmlDictionaryStructureLoader()::load)

        val parsedMessage = FixNgCodecTest.createParsedMessage()

        codec = FixNgCodec(dictionary, FixNgCodecSettings(dictionary = "", decodeValuesToStrings = false))
        rawBody = Unpooled.wrappedBuffer(FixNgCodecTest.MSG_CORRECT.toByteArray(Charsets.US_ASCII))
        rawGroup = MessageGroup(listOf(RawMessage(id = parsedMessage.id, eventId = parsedMessage.eventId, body = rawBody)))
        parsedGroup = MessageGroup(listOf(parsedMessage))
    }

    @Setup(Level.Invocation)
    fun resetReader() {
        rawBody.resetReaderIndex()
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