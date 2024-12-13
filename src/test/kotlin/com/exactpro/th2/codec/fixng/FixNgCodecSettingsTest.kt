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

import com.exactpro.th2.common.schema.factory.CommonFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FixNgCodecSettingsTest {

    @Test
    fun `decode settings`() {
        val dictionary = "fix_dictionary.xml"
        val settings: FixNgCodecSettings = CommonFactory.MAPPER.readValue("""
            {
                "beginString": "FIXT.1.1",
                "dictionary": "$dictionary",
                "charset": "US-ASCII",
                "decodeDelimiter": "\u0001",
                "dirtyMode": false,
                "decodeValuesToStrings": true,
                "decodeComponentsToNestedMaps": true
            }
        """.trimIndent(), FixNgCodecSettings::class.java)

        assertEquals(FixNgCodecSettings(dictionary = dictionary), settings)
    }
}