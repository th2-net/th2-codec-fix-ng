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

import com.exactpro.sf.common.impl.messages.xml.configuration.JavaType
import com.exactpro.sf.common.messages.structures.DictionaryConstants.FIELD_MESSAGE_TYPE
import com.exactpro.sf.common.messages.structures.IAttributeStructure
import com.exactpro.sf.common.messages.structures.IDictionaryStructure
import com.exactpro.sf.common.messages.structures.IFieldStructure
import com.exactpro.sf.common.messages.structures.IMessageStructure
import com.exactpro.sf.common.messages.structures.StructureUtils
import com.exactpro.th2.codec.api.IPipelineCodec
import com.exactpro.th2.codec.api.IReportingContext
import com.exactpro.th2.codec.fixng.FixNgCodecFactory.Companion.PROTOCOL
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageGroup
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.ParsedMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.EnumMap
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Message as CommonMessage

class FixNgCodec(dictionary: IDictionaryStructure, settings: FixNgCodecSettings) : IPipelineCodec {
    private val beginString = settings.beginString
    private val charset = settings.charset

    private val fieldsEncode = convertToFields(dictionary.fields, true)
    private val messagesByTypeForEncode: Map<String, Message>
    private val messagesByTypeForDecode: Map<String, Message>
    private val messagesByNameForEncode: Map<String, Message>
    private val messagesByNameForDecode: Map<String, Message>

    init {
        val messagesForEncode = dictionary.toMessages(true)
        val messagesForDecode = dictionary.toMessages(false)
        messagesByTypeForEncode = messagesForEncode.associateBy(Message::type)
        messagesByTypeForDecode = messagesForDecode.associateBy(Message::type)
        messagesByNameForEncode = messagesForEncode.associateBy(Message::name)
        messagesByNameForDecode = messagesForDecode.associateBy(Message::name)
    }

    private val headerDef = messagesByNameForDecode[HEADER] ?: error("Header is not defined in dictionary")
    private val trailerDef = messagesByNameForDecode[TRAILER] ?: error("Trailer is not defined in dictionary")

    override fun encode(messageGroup: MessageGroup, context: IReportingContext): MessageGroup {
        val messages = mutableListOf<CommonMessage<*>>()

        for (message in messageGroup.messages) {
            if (message !is ParsedMessage || message.protocol.run { isNotEmpty() && this != PROTOCOL }) {
                messages += message
                continue
            }

            val isDirty = message.metadata[ENCODE_MODE_PROPERTY_NAME] == DIRTY_ENCODE_MODE
            val messageDef = messagesByNameForEncode[message.type] ?: error("Unknown message name: ${message.type}")

            val messageFields = message.body as MutableMap
            @Suppress("UNCHECKED_CAST")
            val headerFields = messageFields.remove(HEADER) as? Map<String, *> ?: mapOf<String, Any>()
            @Suppress("UNCHECKED_CAST")
            val trailerFields = messageFields.remove(TRAILER) as? Map<String, *> ?: mapOf<String, Any>()

            val body = Unpooled.buffer(1024)
            val prefix = Unpooled.buffer(32)

            prefix.writeField(TAG_BEGIN_STRING, beginString, charset)
            body.writeField(TAG_MSG_TYPE, messageDef.type, charset)

            headerDef.encode(headerFields, body, isDirty, fieldsEncode, context)
            messageDef.encode(messageFields, body, isDirty, fieldsEncode, context)
            trailerDef.encode(trailerFields, body, isDirty, fieldsEncode, context)

            prefix.writeField(TAG_BODY_LENGTH, body.readableBytes(), charset)
            val buffer = Unpooled.wrappedBuffer(prefix, body)
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

    override fun decode(messageGroup: MessageGroup, context: IReportingContext): MessageGroup {
        val messages = mutableListOf<CommonMessage<*>>()

        for (message in messageGroup.messages) {
            if (message !is RawMessage || message.protocol.run { isNotEmpty() && this != PROTOCOL }) {
                messages += message
                continue
            }

            val buffer = message.body

            val beginString = buffer.readField(TAG_BEGIN_STRING, charset) { "Message starts with $it tag instead of BeginString ($TAG_BEGIN_STRING)" }
            val bodyLength = buffer.readField(TAG_BODY_LENGTH, charset) { "BeginString ($TAG_BEGIN_STRING) is followed by $it tag instead of BodyLength ($TAG_BODY_LENGTH)" }
            val msgType = buffer.readField(TAG_MSG_TYPE, charset) { "BodyLength ($TAG_BODY_LENGTH) is followed by $it tag instead of MsgType ($TAG_MSG_TYPE)" }

            val messageDef = messagesByTypeForDecode[msgType] ?: error("Unknown message type: $msgType")

            val header = headerDef.decode(buffer, context)
            val body = messageDef.decode(buffer, context)
            val trailer = trailerDef.decode(buffer, context)

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

    private fun Field.decode(source: ByteBuf, target: MutableMap<String, Any>, value: String, tag: Int, context: IReportingContext) {
        val previous = when (this) {
            is Primitive -> {
                val decodedValue = when (primitiveType) {
                    java.lang.String::class.java -> value
                    java.lang.Character::class.java -> {
                        check(value.length == 1) { "Wrong value" }
                        value[0]
                    }
                    java.lang.Integer::class.java -> value.toInt()
                    java.math.BigDecimal::class.java -> value.toBigDecimal()
                    java.lang.Long::class.java -> value.toLong()
                    java.lang.Short::class.java -> value.toShort()
                    java.lang.Byte::class.java -> value.toByte()
                    java.lang.Boolean::class.java -> value.toBoolean()
                    java.lang.Float::class.java -> value.toFloat()
                    java.lang.Double::class.java -> value.toDouble()
                    java.time.LocalDateTime::class.java -> LocalDateTime.parse(value, dateTimeFormatter)
                    java.time.LocalDate::class.java -> LocalDate.parse(value, dateFormatter)
                    java.time.LocalTime::class.java -> LocalTime.parse(value, timeFormatter)
                    else -> error("Unsupported type: ${this.primitiveType}")
                }

                target.put(name, decodedValue)
            }
            is Group -> target.put(name, decode(source, value.toIntOrNull() ?: error("Invalid $name group counter ($tag) value: $value"), context))
            else -> error("Unsupported field type: $this")
        }

        check(previous == null) { "Duplicate $name field ($tag) with value: $value (previous: $previous)" }
    }

    private fun Message.decode(source: ByteBuf, context: IReportingContext): MutableMap<String, Any> = mutableMapOf<String, Any>().also { map ->
        source.forEachField(charset) { tag, value ->
            val field = get(tag) ?: return@forEachField false
            field.decode(source, map, value, tag, context)
            return@forEachField true
        }
    }

    private fun Group.decode(source: ByteBuf, count: Int, context: IReportingContext): List<Map<String, Any>> = ArrayList<Map<String, Any>>().also { list ->
        var map: MutableMap<String, Any>? = null

        source.forEachField(charset) { tag, value ->
            val field = get(tag) ?: return@forEachField false
            if (tag == delimiter) map = mutableMapOf<String, Any>().also(list::add)
            val group = checkNotNull(map) { "Field ${field.name} ($tag) appears before delimiter ($delimiter)" }
            field.decode(source, group, value, tag, context)
            return@forEachField true
        }

        check(list.size == count) { "Unexpected group $name count: ${list.size} (expected: $count)" }
    }

    private fun encodeField(field: Field, value: Any, target: ByteBuf, isDirty: Boolean, dictionaryFields: Map<String, Field>, context: IReportingContext) {
        when {
            field is Primitive -> if (
                field.tag != TAG_BEGIN_STRING &&
                field.tag != TAG_BODY_LENGTH &&
                field.tag != TAG_CHECKSUM &&
                field.tag != TAG_MSG_TYPE
            ) {
                if (!isCompatibleType(value::class.java, field.primitiveType)) {
                    // TODO: 2.2
                    if (isDirty) {
                        context.warning("Dirty mode WARNING: Wrong type value in field ${field.name}. Actual: ${value.javaClass} (value: $value). Expected ${field.primitiveType}")
                    } else {
                        error("Wrong type value in field ${field.name}. Actual: ${value.javaClass}. Expected ${field.primitiveType}")
                    }
                }

                if (field.values.isNotEmpty() && !field.values.contains(value)) {
                    // TODO: 2.1
                    if (isDirty) {
                        context.warning("Dirty mode WARNING: Wrong value in field ${field.name}. Actual: $value. Expected ${field.values}.")
                    } else {
                        error("Wrong value in field ${field.name}. Expected ${field.values}. Actual: $value")
                    }
                }

                val stringValue = when (value) {
                    is java.lang.Boolean -> if (value.booleanValue()) "Y" else "N"
                    is LocalDateTime -> value.format(dateTimeFormatter)
                    is LocalDate -> value.format(dateFormatter)
                    is LocalTime -> value.format(timeFormatter)
                    else -> value.toString()
                }

                if (stringValue.isEmpty()) {
                    if (isDirty) {
                        context.warning("Dirty mode WARNING: Empty value in the field '${field.name}'.")
                    } else {
                        error("Empty value in the field '${field.name}'")
                    }
                }

                if (!stringValue.asSequence().all { it in ' ' .. '~' }) {
                    if (isDirty) {
                        context.warning("Dirty mode WARNING: Non printable characters in the field '${field.name}'. Value: $value")
                    } else {
                        error("Dirty mode WARNING: Non printable characters in the field '${field.name}'. Value: $value")
                    }
                }

                target.writeField(field.tag, stringValue, charset)
            }

            field is Group && value is List<*> -> field.encode(value, target, isDirty, dictionaryFields, context)
            field is Message && value is Map<*,*> -> {
                @Suppress("UNCHECKED_CAST")
                val messageValue = value as Map<String, *>
                field.encode(messageValue, target, isDirty, dictionaryFields, context)
            }
            else -> error("Unsupported value in ${field.name} field: $value")
        }
    }

    private fun FieldMap.encode(source: Map<String, *>, target: ByteBuf, isDirty: Boolean, dictionaryFields: Map<String, Field>, context: IReportingContext) {
        fields.forEach { (name, field) ->
            val value = source[name]
            if (value != null) {
                encodeField(field, value, target, isDirty, dictionaryFields, context)
            } else if (field.isRequired) {
                if (isDirty) {
                    // TODO: C1 (1.5.1)
                    context.warning("Dirty mode WARNING: Required field missing. Field name: $name. Message body: $source")
                } else {
                    error("Required field missing: $name. Message body: $source")
                }
            }
        }

        source.filter { fields[it.key] == null }.forEach { (fieldName, value) ->
            if (!isDirty) {
                error("Unexpected field in message. Field name: $fieldName. Field value: $value. Message body: $source")
            }

            val field = dictionaryFields[fieldName]

            if (field != null) {
                // TODO: A1 (1.1.1)
                context.warning("Dirty mode WARNING: Unexpected field in message. Field name: $fieldName. Field value: $value. Message body: $source")
                encodeField(field, value ?: "", target, true, dictionaryFields, context)
            } else {
                val tag = fieldName.toIntOrNull()
                if(tag != null && tag > 0) {
                    // TODO: A3 (1.1.3)
                    if (value is List<*>) { // TODO: do we need this check?
                        error("List value with unspecified name. tag = $tag")
                    } else {
                        context.warning("Dirty mode WARNING: Tag instead of field name. Field name: $fieldName. Field value: $value. Message body: $source")
                        target.writeField(tag, value, charset)
                    }
                } else {
                    // TODO: A2 (1.1.2)
                    error("Field does not exist in dictionary. Field name: $fieldName. Field value: $value. Message body: $source")
                }
            }
        }
    }

    private fun Group.encode(source: List<*>, target: ByteBuf, isDirty: Boolean, dictionaryFields: Map<String, Field>, context: IReportingContext) {
        target.writeField(counter, source.size, charset)

        source.forEach { group ->
            check(group is Map<*, *>) { "Unsupported value in $name group: $group" }
            @Suppress("UNCHECKED_CAST")
            val groupMap = group as Map<String, *>
            encode(groupMap, target, isDirty, dictionaryFields, context)
        }
    }

    interface Field {
        val isRequired: Boolean
        val name: String
    }

    data class Primitive(
        override val isRequired: Boolean,
        override val name: String,
        val primitiveType: Class<*>,
        val values: Set<Any>,
        val tag: Int
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
        private const val ENCODE_MODE_PROPERTY_NAME = "encode-mode"
        private const val DIRTY_ENCODE_MODE = "dirty"

        private const val TAG_BEGIN_STRING = 8
        private const val TAG_BODY_LENGTH = 9
        private const val TAG_CHECKSUM = 10
        private const val TAG_MSG_TYPE = 35

        private val dateTimeFormatter = DateTimeFormatterBuilder()
            .appendPattern("yyyyMMdd-HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter()

        private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        private val timeFormatter = DateTimeFormatterBuilder()
            .appendPattern("HH:mm:ss")
            .appendFraction(ChronoField.MILLI_OF_SECOND, 0, 9, true)
            .toFormatter()

        private val javaTypeToClass = EnumMap<JavaType, Class<*>>(JavaType::class.java).apply {
            for (type in JavaType.values()) {
                put(type, Class.forName(type.value()))
            }
            withDefault { error("Unsupported java type: $it") }
        }

        private val typeSizes = mapOf(
            java.lang.Byte::class.java to 1,
            java.lang.Short::class.java to 2,
            java.lang.Integer::class.java to 3,
            java.lang.Long::class.java to 4,
            BigDecimal:: class.java to 5
        )

        private fun isCompatibleType(from: Class<*>, to: Class<*>): Boolean {
            if (from == to) return true
            val fromSize = typeSizes[from] ?: return false
            val toSize = typeSizes[to] ?: return false
            return fromSize < toSize
        }

        private val IMessageStructure.entityType: String
            get() = StructureUtils.getAttributeValue(this, "entity_type")

        private val IMessageStructure.isGroup: Boolean
            get() = entityType == "Group"

        private val IMessageStructure.isComponent: Boolean
            get() = entityType == "Component"

        private val IFieldStructure.tag: Int
            get() = StructureUtils.getAttributeValue(this, "tag")

        private fun IFieldStructure.toPrimitive(): Primitive = Primitive(
            isRequired,
            name,
            javaTypeToClass.getValue(javaType),
            values.values.map<IAttributeStructure, Any> { it.getCastValue() }.toSet(),
            tag
        )

        private fun convertToFields(fields: Map<String, IFieldStructure>, isForEncode: Boolean): Map<String, Field> = linkedMapOf<String, Field>().apply {
            fields.forEach { (name, field) ->
                when {
                    field !is IMessageStructure -> this[name] = field.toPrimitive()
                    field.isGroup -> this[name] = field.toGroup(isForEncode)
                    field.isComponent -> if (isForEncode) {
                        this[name] = field.toMessage(true)
                    } else {
                        this += convertToFields(field.fields, false)
                    }
                }
            }
        }

        private fun IMessageStructure.toMessage(isForEncode: Boolean): Message = Message(
            name = name,
            type = StructureUtils.getAttributeValue(this, FIELD_MESSAGE_TYPE) ?: name,
            fields = convertToFields(this.fields, isForEncode),
            isRequired = isRequired
        )

        private fun IMessageStructure.toGroup(isForEncode: Boolean): Group = Group(
            name = name,
            counter = tag,
            delimiter = fields.values.first().tag,
            fields = convertToFields(this.fields, isForEncode),
            isRequired = isRequired
        )

        fun IDictionaryStructure.toMessages(isForEncode: Boolean): List<Message> = messages.values
            .filterNot { it.isGroup || it.isComponent }
            .map { it.toMessage(isForEncode) }
    }
}