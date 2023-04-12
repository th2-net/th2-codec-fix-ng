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

import com.exactpro.sf.common.messages.structures.loaders.XmlDictionaryStructureLoader
import com.exactpro.th2.codec.api.IPipelineCodec
import com.exactpro.th2.codec.api.IPipelineCodecFactory
import com.exactpro.th2.codec.api.IPipelineCodecSettings
import com.exactpro.th2.codec.fixng.FixNgCodec.Companion.toMessages
import com.exactpro.th2.codec.fixng.FixNgCodec.Message
import com.google.auto.service.AutoService
import java.io.InputStream

@AutoService(IPipelineCodecFactory::class)
class FixNgCodecFactory : IPipelineCodecFactory {
    @Deprecated("Please migrate to the protocols property") override val protocol: String = PROTOCOL
    override val settingsClass: Class<out IPipelineCodecSettings> = FixNgCodecSettings::class.java
    private lateinit var messages: List<Message>

    override fun init(dictionary: InputStream) {
        messages = dictionary.use(XmlDictionaryStructureLoader()::load).toMessages()
    }

    override fun create(settings: IPipelineCodecSettings?): IPipelineCodec = FixNgCodec(messages, requireNotNull(settings as? FixNgCodecSettings) {
        "settings is not an instance of ${FixNgCodecSettings::class.java}: $settings"
    })

    companion object {
        const val PROTOCOL = "fix"
    }
}