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

import com.exactpro.th2.codec.api.IPipelineCodecSettings
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import java.nio.charset.Charset

data class FixNgCodecSettings(
    val beginString: String = "FIXT.1.1",
    val dictionary: String,
    @JsonDeserialize(using = CharsetDeserializer::class)
    val charset: Charset = Charsets.US_ASCII,
    val decodeDelimiter: Char = SOH_CHAR,
    val dirtyMode: Boolean = false,
    val decodeValuesToStrings: Boolean = true,
    val decodeComponentsToNestedMaps: Boolean = true
) : IPipelineCodecSettings

object CharsetDeserializer : JsonDeserializer<Charset>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Charset = Charset.forName(p.valueAsString)
}