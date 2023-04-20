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

import com.exactpro.sf.common.messages.structures.DictionaryConstants.FIELD_MESSAGE_TYPE
import com.exactpro.sf.common.messages.structures.IDictionaryStructure
import com.exactpro.sf.common.messages.structures.IFieldStructure
import com.exactpro.sf.common.messages.structures.IMessageStructure
import com.exactpro.sf.common.messages.structures.StructureUtils
import com.exactpro.th2.codec.api.IPipelineCodec
import com.exactpro.th2.codec.fixng.FixNgCodecFactory.Companion.PROTOCOL
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageGroup
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.ParsedMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Message as CommonMessage

class FixNgCodec(messages: List<Message>, settings: FixNgCodecSettings) : IPipelineCodec {
    private val beginString = settings.beginString
    private val charset = settings.charset

    private val messagesByType = messages.associateBy(Message::type)
    private val messagesByName = messages.associateBy(Message::name)

    private val headerDef = messagesByName[HEADER] ?: error("Header is not defined in dictionary")
    private val trailerDef = messagesByName[TRAILER] ?: error("Trailer is not defined in dictionary")

    override fun encode(messageGroup: MessageGroup): MessageGroup {
        val messages = mutableListOf<CommonMessage<*>>()

        for (message in messageGroup.messages) {
            if (message !is ParsedMessage || message.protocol.run { isNotEmpty() && this != PROTOCOL }) {
                messages += message
                continue
            }

            val messageDef = messagesByName[message.type] ?: error("Unknown message name: ${message.type}")

            val messageFields = message.body
            val headerFields = messageFields.remove(HEADER) as? Map<*, *> ?: mapOf<Any, Any>()
            val trailerFields = messageFields.remove(TRAILER) as? Map<*, *> ?: mapOf<Any, Any>()

            val body = Unpooled.buffer(1024)
            val prefix = Unpooled.buffer(32)
            val buffer = Unpooled.wrappedBuffer(prefix, body)

            prefix.writeField(8, beginString, charset)
            body.writeField(35, messageDef.type, charset)

            headerDef.encode(headerFields, body)
            messageDef.encode(messageFields, body)
            trailerDef.encode(trailerFields, body)

            prefix.writeField(9, body.readableBytes(), charset)
            buffer.writeChecksum()

            messages += RawMessage(
                id = message.id,
                eventId = message.eventId,
                metadata = message.metadata,
                protocol = PROTOCOL,
                body = buffer
            )
        }

        return MessageGroup(messages)
    }

    override fun decode(messageGroup: MessageGroup): MessageGroup {
        val messages = mutableListOf<CommonMessage<*>>()

        for (message in messageGroup.messages) {
            if (message !is RawMessage || message.protocol.run { isNotEmpty() && this != PROTOCOL }) {
                messages += message
                continue
            }

            val buffer = message.body

            val beginString = buffer.readField(8, charset) { "Message starts with $it tag instead of BeginString (8)" }
            val bodyLength = buffer.readField(9, charset) { "BeginString (8) is followed by $it tag instead of BodyLength (9)" }
            val msgType = buffer.readField(35, charset) { "BodyLength (9) is followed by $it tag instead of MsgType (35)" }

            val messageDef = messagesByType[msgType] ?: error("Unknown message type: $msgType")

            val header = headerDef.decode(buffer)
            val body = messageDef.decode(buffer)
            val trailer = trailerDef.decode(buffer)

            if (buffer.isReadable) error("Tag appears out of order: ${buffer.readTag()}")

            header["BeginString"] = beginString
            header["BodyLength"] = bodyLength
            header["MsgType"] = msgType

            body[HEADER] = header
            body[TRAILER] = trailer

            messages += ParsedMessage(
                id = message.id,
                eventId = message.eventId,
                metadata = message.metadata,
                protocol = PROTOCOL,
                type = messageDef.name,
                body = body,
            )
        }

        return MessageGroup(messages)
    }

    private fun Field.decode(source: ByteBuf, target: MutableMap<String, Any>, value: String, tag: Int) {
        val previous = when (this) {
            is Primitive -> target.put(name, value)
            is Group -> target.put(name, decode(source, value.toIntOrNull() ?: error("Invalid $name group counter ($tag) value: $value")))
            else -> error("Unsupported field type: $this")
        }

        check(previous == null) { "Duplicate $name field ($tag) with value: $value (previous: $previous)" }
    }

    private fun Message.decode(source: ByteBuf): MutableMap<String, Any> = mutableMapOf<String, Any>().also { map ->
        source.forEachField(charset) { tag, value ->
            val field = get(tag) ?: return@forEachField false
            field.decode(source, map, value, tag)
            return@forEachField true
        }
    }

    private fun Group.decode(source: ByteBuf, count: Int): List<Map<String, Any>> = ArrayList<Map<String, Any>>().also { list ->
        var map: MutableMap<String, Any>? = null

        source.forEachField(charset) { tag, value ->
            val field = get(tag) ?: return@forEachField false
            if (tag == delimiter) map = mutableMapOf<String, Any>().also(list::add)
            val group = checkNotNull(map) { "Field ${field.name} ($tag) appears before delimiter ($delimiter)" }
            field.decode(source, group, value, tag)
            return@forEachField true
        }

        check(list.size == count) { "Unexpected group $name count: ${list.size} (expected: $count)" }
    }

    private fun FieldMap.encode(source: Map<*, *>, target: ByteBuf) = fields.forEach { (name, field) ->
        val value = source[name] ?: when {
            field.isRequired -> error("Missing required field: $name")
            else -> return@forEach
        }

        when {
            field is Primitive -> if (field.tag != 8 && field.tag != 9 && field.tag != 10 && field.tag != 35) target.writeField(field.tag, value, charset)
            field is Group && value is List<*> -> field.encode(value, target)
            else -> error("Unsupported value in ${field.name} field: $value")
        }
    }

    private fun Group.encode(source: List<*>, target: ByteBuf) {
        target.writeField(counter, source.size, charset)

        source.forEach { group ->
            check(group is Map<*, *>) { "Unsupported value in $name group: $group" }
            encode(group, target)
        }
    }

    interface Field {
        val isRequired: Boolean
        val name: String
    }

    data class Primitive(
        override val isRequired: Boolean,
        override val name: String,
        val tag: Int,
    ) : Field

    abstract class FieldMap {
        abstract val name: String
        abstract val fields: Map<String, Field>

        private val tags: Map<Int, Field> by lazy {
            fields.values.associateBy { field ->
                when (field) {
                    is Primitive -> field.tag
                    is Group -> field.counter
                    else -> error("Field map $name contains unsupported field: $field")
                }
            }
        }

        operator fun get(tag: Int): Field? = tags[tag]
        operator fun get(field: String): Field? = fields[field]
    }

    data class Message(
        override val isRequired: Boolean,
        override val name: String,
        val type: String,
        override val fields: Map<String, Field>,
    ) : Field, FieldMap()

    data class Group(
        override val isRequired: Boolean,
        override val name: String,
        val counter: Int,
        val delimiter: Int,
        override val fields: Map<String, Field>,
    ) : Field, FieldMap()

    companion object {
        private const val HEADER = "header"
        private const val TRAILER = "trailer"

        private val IMessageStructure.entityType: String
            get() = StructureUtils.getAttributeValue(this, "entity_type")

        private val IMessageStructure.isGroup: Boolean
            get() = entityType == "Group"

        private val IMessageStructure.isComponent: Boolean
            get() = entityType == "Component"

        private val IFieldStructure.tag: Int
            get() = StructureUtils.getAttributeValue(this, "tag")

        private fun IFieldStructure.toPrimitive(): Primitive = Primitive(isRequired, name, tag)

        private fun IMessageStructure.toFields(): Map<String, Field> = linkedMapOf<String, Field>().apply {
            fields.forEach { (name, field) ->
                when {
                    field !is IMessageStructure -> this[name] = field.toPrimitive()
                    field.isGroup -> this[name] = field.toGroup()
                    field.isComponent -> this += field.toFields()
                }
            }
        }

        private fun IMessageStructure.toMessage(): Message = Message(
            name = name,
            type = StructureUtils.getAttributeValue(this, FIELD_MESSAGE_TYPE) ?: name,
            fields = toFields(),
            isRequired = isRequired
        )

        private fun IMessageStructure.toGroup(): Group = Group(
            name = name,
            counter = tag,
            delimiter = fields.values.first().tag,
            fields = toFields(),
            isRequired = isRequired,
        )

        fun IDictionaryStructure.toMessages(): List<Message> = messages.values
            .filterNot { it.isGroup || it.isComponent }
            .map { it.toMessage() }
    }
}