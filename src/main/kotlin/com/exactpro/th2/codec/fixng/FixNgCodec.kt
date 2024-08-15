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
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.EnumMap
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Message as CommonMessage

class FixNgCodec(dictionary: IDictionaryStructure, settings: FixNgCodecSettings) : IPipelineCodec {
    private val beginString = settings.beginString
    private val charset = settings.charset
    private val isDirtyMode = settings.dirtyMode
    private val isDecodeToStrings = settings.decodeValuesToStrings

    private val fieldsEncode = convertToFieldsByName(dictionary.fields, true)
    private val fieldsDecode = convertToFieldsByTag(dictionary.fields)
    private val messagesByTypeForDecode: Map<String, Message>
    private val messagesByNameForEncode: Map<String, Message>

    private val headerDef: Message
    private val trailerDef: Message

    init {
        val messagesForEncode = dictionary.toMessages(isForEncode = true)
        val messagesForDecode = dictionary.toMessages(isForEncode = false)
        messagesByNameForEncode = messagesForEncode.associateBy(Message::name)
        messagesByTypeForDecode = messagesForDecode.associateBy(Message::type)

        val messagesByNameForDecode = messagesForDecode.associateBy(Message::name)
        headerDef = messagesByNameForDecode[HEADER] ?: error("Header is not defined in dictionary")
        trailerDef = messagesByNameForDecode[TRAILER] ?: error("Trailer is not defined in dictionary")
    }

    override fun encode(messageGroup: MessageGroup, context: IReportingContext): MessageGroup {
        val messages = mutableListOf<CommonMessage<*>>()

        for (message in messageGroup.messages) {
            if (message !is ParsedMessage || message.protocol.run { isNotEmpty() && this != PROTOCOL }) {
                messages += message
                continue
            }

            val isDirty = isDirtyMode || (message.metadata[ENCODE_MODE_PROPERTY_NAME] == DIRTY_ENCODE_MODE)
            val messageDef = messagesByNameForEncode[message.type] ?: error("Unknown message name: ${message.type}")

            val messageFields = message.body
            @Suppress("UNCHECKED_CAST")
            val headerFields = messageFields[HEADER] as? Map<String, *> ?: mapOf<String, Any>()
            @Suppress("UNCHECKED_CAST")
            val trailerFields = messageFields[TRAILER] as? Map<String, *> ?: mapOf<String, Any>()

            val body = Unpooled.buffer(1024)
            val prefix = Unpooled.buffer(32)

            prefix.writeField(TAG_BEGIN_STRING, beginString, charset)
            body.writeField(TAG_MSG_TYPE, messageDef.type, charset)

            headerDef.encode(headerFields, body, isDirty, fieldsEncode, context)
            messageDef.encode(messageFields, body, isDirty, fieldsEncode, context, FIELDS_NOT_IN_BODY)
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

            val isDirty = isDirtyMode || (message.metadata[ENCODE_MODE_PROPERTY_NAME] == DIRTY_ENCODE_MODE)
            val buffer = message.body

            val beginString = buffer.readField(TAG_BEGIN_STRING, charset, isDirty) { "Message starts with $it tag instead of BeginString ($TAG_BEGIN_STRING)" }
            val bodyLengthString = buffer.readField(TAG_BODY_LENGTH, charset, isDirty) { "BeginString ($TAG_BEGIN_STRING) is followed by $it tag instead of BodyLength ($TAG_BODY_LENGTH)" }
            val bodyLength = bodyLengthString.toIntOrNull() ?: handleError(isDirty, context, "Wrong number value in integer field 'BodyLength'. Value: $bodyLengthString.", bodyLengthString)
            val msgType = buffer.readField(TAG_MSG_TYPE, charset, isDirty) { "BodyLength ($TAG_BODY_LENGTH) is followed by $it tag instead of MsgType ($TAG_MSG_TYPE)" }
            val messageDef = messagesByTypeForDecode[msgType] ?: error("Unknown message type: $msgType")

            val header = headerDef.decode(buffer, messageDef, isDirty, fieldsDecode, context)
            val body = messageDef.decode(buffer, messageDef, isDirty, fieldsDecode, context)
            val trailer = trailerDef.decode(buffer, messageDef, isDirty, fieldsDecode, context)

            if (buffer.isReadable) {
                // this should never happen in dirty mode
                val errorMessage = if (isDirty) "Field was not processed in dirty mode. Tag: ${buffer.readTag()}" else "Tag appears out of order: ${buffer.readTag()}"
                error(errorMessage)
            }

            header["BeginString"] = beginString
            header["BodyLength"] = if (isDecodeToStrings) bodyLengthString else bodyLength
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

    private fun handleError(isDirty: Boolean, context: IReportingContext, errorMessageText: String, value: Any = Unit) = if (isDirty) {
        context.warning(DIRTY_MODE_WARNING_PREFIX + errorMessageText)
        value
    } else {
        error(errorMessageText)
    }

    private fun Field.decode(
        source: ByteBuf,
        target: MutableMap<String, Any>,
        value: String,
        tag: Int,
        isDirty: Boolean,
        context: IReportingContext
    ) {
        val decodedValue: Any = when {
            value.isEmpty() -> handleError(isDirty, context, "Empty value in the field '$name'.", value)
            containsNonPrintableChars(value) -> handleError(isDirty, context, "Non printable characters in the field '$name'. Value: $value", value)
            else -> when (this) {
                is Primitive -> decode(value, isDirty, context)
                is Group -> decode(
                    source,
                    value.toIntOrNull() ?: error("Invalid $name group counter ($tag) value: $value"),
                    isDirty,
                    context
                )

                else -> error("Unsupported field type: $this")
            }
        }

        // in dirty mode we'll use tag as field name if we have field duplication
        val fieldName = if (isDirty && target.contains(name)) {
            tag.toString()
        } else {
            name
        }

        val previous = target.put(fieldName, decodedValue)

        // but even in dirty mode we can't write field if it's duplicated more than once
        // because we use Map and it cant contain duplicates
        check(previous == null) { "Duplicate $name field ($tag) with value: $value (previous: $previous)" }
    }

    private val prereadHeaderFields = arrayOf("BeginString", "BodyLength", "MsgType")

    private fun Message.decode(source: ByteBuf, bodyDef: Message, isDirty: Boolean, dictionaryFields: Map<Int, Field>, context: IReportingContext): MutableMap<String, Any> = mutableMapOf<String, Any>().also { map ->
        source.forEachField(charset, isDirty) { tag, value ->
            val field = get(tag) ?: if (isDirty) {
                when (this) {
                    headerDef -> bodyDef[tag] ?: trailerDef[tag]
                    trailerDef -> null
                    else -> trailerDef[tag]
                }?.let { return@forEachField false } // we reached next part of the message

                val dictField = dictionaryFields[tag]
                if (dictField != null) {
                    context.warning(DIRTY_MODE_WARNING_PREFIX + "Unexpected field in message. Field name: ${dictField.name}. Field value: $value.")
                    dictField
                } else {
                    context.warning(DIRTY_MODE_WARNING_PREFIX + "Field does not exist in dictionary. Field tag: $tag. Field value: $value.")
                    Primitive(false, tag.toString(), String::class.java, emptySet(), tag)
                }
            } else {
                // we reached next part of the message
                return@forEachField false
            }

            field.decode(source, map, value, tag, isDirty, context)
            return@forEachField true
        }

        for (field in fields.values) {
            if (field.isRequired && !map.contains(field.name) && field.name !in prereadHeaderFields) {
                handleError(isDirty, context, "Required field missing. Field name: ${field.name}.")
            }
        }
    }

    private fun Primitive.decode(value: String, isDirty: Boolean, context: IReportingContext): Any {
        if (values.isNotEmpty() && !values.contains(value)) {
            handleError(isDirty, context, "Invalid value in enum field $name. Actual: $value. Valid values $values.")
        }

        val decodedValue = try {
            when (primitiveType) {
                java.lang.String::class.java -> value
                java.lang.Character::class.java -> {
                    if (value.length != 1) {
                        handleError(isDirty, context, "Wrong value in character field '$name'. Value: $value", value)
                    } else {
                        value[0]
                    }
                }

                java.lang.Integer::class.java -> value.toInt()
                java.math.BigDecimal::class.java -> value.toBigDecimal()
                java.lang.Long::class.java -> value.toLong()
                java.lang.Short::class.java -> value.toShort()
                java.lang.Byte::class.java -> value.toByte()
                java.lang.Float::class.java -> value.toFloat()
                java.lang.Double::class.java -> value.toDouble()

                java.time.LocalDateTime::class.java -> LocalDateTime.parse(value, dateTimeFormatter)
                java.time.LocalDate::class.java -> LocalDate.parse(value, dateFormatter)
                java.time.LocalTime::class.java -> LocalTime.parse(value, timeFormatter)

                java.lang.Boolean::class.java -> when (value) {
                    "Y" -> true
                    "N" -> false
                    else -> handleError(isDirty, context, "Wrong value in boolean field '$name'. Value: $value.", value)
                }

                else -> error("Unsupported type: $primitiveType.")
            }
        } catch (e: NumberFormatException) {
            handleError(isDirty, context, "Wrong number value in ${primitiveType.name} field '$name'. Value: $value.", value)
        } catch (e: DateTimeParseException) {
            handleError(isDirty, context, "Wrong date/time value in ${primitiveType.name} field '$name'. Value: $value.", value)
        }

        return if (isDecodeToStrings) {
            if (primitiveType == java.time.LocalDateTime::class.java || primitiveType == java.time.LocalDate::class.java || primitiveType == java.time.LocalTime::class.java) {
                decodedValue.toString()
            } else {
                value
            }
        } else {
            decodedValue
        }
     }

    private fun Group.decode(source: ByteBuf, count: Int, isDirty: Boolean, context: IReportingContext): List<Map<String, Any>> = ArrayList<Map<String, Any>>().also { list ->
        var map: MutableMap<String, Any>? = null
        val tags: MutableSet<Int> = hashSetOf()

        source.forEachField(charset, isDirty) { tag, value ->
            val field = get(tag) ?: return@forEachField false

            val group = if (tag == delimiter || !tags.add(tag) || map == null) {
                if (tag != delimiter) {
                    handleError(isDirty, context, "Field ${field.name} ($tag) appears before delimiter ($delimiter)")
                }

                tags.clear()
                mutableMapOf<String, Any>().also {
                    list.add(it)
                    map = it
                }
            } else {
                map ?: error("Group entry map can't be null.")
            }

            field.decode(source, group, value, tag, isDirty, context)
            return@forEachField true
        }

        if (list.size != count) {
            val errorText = "Unexpected group $name count: ${list.size} (expected: $count)"
            handleError(isDirty, context, errorText)
        }
    }

    private fun encodeField(field: Field, value: Any, target: ByteBuf, isDirty: Boolean, dictionaryFields: Map<String, Field>, context: IReportingContext) {
        when {
            field is Primitive -> {
                if (!isCompatibleType(value.javaClass, field.primitiveType)) {
                    if (value is String) {
                        field.decode(value, isDirty, context) // validate if String value could be parsed to required type
                        target.writeField(field.tag, value, charset)
                        return
                    } else {
                        handleError(isDirty, context, "Wrong type value in field ${field.name}. Actual: ${value.javaClass} (value: $value). Expected ${field.primitiveType}")
                    }
                }

                val stringValue = when (value) {
                    is java.lang.Boolean -> if (value.booleanValue()) "Y" else "N"
                    is LocalDateTime -> value.format(dateTimeFormatter)
                    is LocalDate -> value.format(dateFormatter)
                    is LocalTime -> value.format(timeFormatter)
                    else -> value.toString()
                }

                when {
                    stringValue.isEmpty() -> handleError(isDirty, context, "Empty value in the field '${field.name}'.")
                    containsNonPrintableChars(stringValue) -> handleError(isDirty, context, "Non-printable characters in the field '${field.name}'. Value: $value")
                    field.values.isNotEmpty() && !field.values.contains(stringValue) -> handleError(isDirty, context, "Invalid value in enum field ${field.name}. Actual: $value. Valid values ${field.values}.")
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

    private fun FieldMap.encode(source: Map<String, *>, target: ByteBuf, isDirty: Boolean, dictionaryFields: Map<String, Field>, context: IReportingContext, fieldsToSkip: Set<String> = emptySet()) {
        fields.forEach { (name, field) ->
            if (field is Primitive && field.tag in calculatedFields) return@forEach
            val value = source[name]
            if (value != null) {
                encodeField(field, value, target, isDirty, dictionaryFields, context)
            } else if (field.isRequired && this !== headerDef) {
                handleError(isDirty, context, "Required field missing. Field name: $name.")
            }
        }

        source.filter { fields[it.key] == null && it.key !in fieldsToSkip}.forEach { (fieldName, value) ->
            if (!isDirty) {
                error("Unexpected field in message. Field name: $fieldName. Field value: $value. Message body: $source")
            }

            val field = dictionaryFields[fieldName]

            if (field != null) {
                context.warning(DIRTY_MODE_WARNING_PREFIX + "Unexpected field in message. Field name: $fieldName. Field value: $value. Message body: $source")
                encodeField(field, value ?: "", target, true, dictionaryFields, context)
            } else {
                val tag = fieldName.toIntOrNull()
                if(tag != null && tag > 0) {
                    if (value is List<*>) { // TODO: do we need this check?
                        error("List value with unspecified name. tag = $tag")
                    } else {
                        context.warning(DIRTY_MODE_WARNING_PREFIX + "Tag instead of field name. Field name: $fieldName. Field value: $value. Message body: $source")
                        target.writeField(tag, value, charset)
                    }
                } else {
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
        val values: Set<String>,
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
        private val FIELDS_NOT_IN_BODY = setOf(HEADER, TRAILER)
        private const val ENCODE_MODE_PROPERTY_NAME = "encode-mode"
        private const val DIRTY_ENCODE_MODE = "dirty"
        private const val TAG_BEGIN_STRING = 8
        private const val TAG_BODY_LENGTH = 9
        private const val TAG_CHECKSUM = 10
        private const val TAG_MSG_TYPE = 35
        private const val DIRTY_MODE_WARNING_PREFIX = "Dirty mode WARNING: "

        private fun containsNonPrintableChars(stringValue: String) = stringValue.any { it !in ' ' .. '~' }
        private val calculatedFields = intArrayOf(TAG_BEGIN_STRING, TAG_BODY_LENGTH, TAG_MSG_TYPE, TAG_CHECKSUM)

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
            values.values.map<IAttributeStructure, String> { it.getCastValue<Any>().toString() }.toSet(),
            tag
        )

        private fun convertToFieldsByName(fields: Map<String, IFieldStructure>, isForEncode: Boolean): Map<String, Field> = linkedMapOf<String, Field>().apply {
            fields.forEach { (name, field) ->
                when {
                    field !is IMessageStructure -> this[name] = field.toPrimitive()
                    field.isGroup -> this[name] = field.toGroup(isForEncode)
                    field.isComponent -> if (isForEncode) {
                        this[name] = field.toMessage(true)
                    } else {
                        this += convertToFieldsByName(field.fields, false)
                    }
                }
            }
        }

        private fun convertToFieldsByTag(fields: Map<String, IFieldStructure>): Map<Int, Field>  = linkedMapOf<Int, Field>().apply {
            fields.values.forEach { field ->
                when {
                    field !is IMessageStructure -> this[field.tag] = field.toPrimitive()
                    field.isGroup -> this[field.tag] = field.toGroup(false)
                    field.isComponent -> this += convertToFieldsByTag(field.fields)
                }
            }
        }

        private fun IMessageStructure.toMessage(isForEncode: Boolean): Message = Message(
            name = name,
            type = StructureUtils.getAttributeValue(this, FIELD_MESSAGE_TYPE) ?: name,
            fields = convertToFieldsByName(this.fields, isForEncode),
            isRequired = isRequired
        )

        private fun getFirstTag(message: IMessageStructure): Int = message.fields.values.first().let {
            if (it is IMessageStructure) getFirstTag(it) else it.tag
        }

        private fun IMessageStructure.toGroup(isForEncode: Boolean): Group = Group(
            name = name,
            counter = tag,
            delimiter = getFirstTag(this),
            fields = convertToFieldsByName(this.fields, isForEncode),
            isRequired = isRequired
        )

        fun IDictionaryStructure.toMessages(isForEncode: Boolean): List<Message> = messages.values
            .filterNot { it.isGroup || it.isComponent }
            .map { it.toMessage(isForEncode) }
    }
}