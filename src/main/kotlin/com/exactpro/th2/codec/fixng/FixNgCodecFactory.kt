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

import com.exactpro.sf.common.messages.structures.loaders.XmlDictionaryStructureLoader
import com.exactpro.th2.codec.api.IPipelineCodec
import com.exactpro.th2.codec.api.IPipelineCodecContext
import com.exactpro.th2.codec.api.IPipelineCodecFactory
import com.exactpro.th2.codec.api.IPipelineCodecSettings
import com.google.auto.service.AutoService

@AutoService(IPipelineCodecFactory::class)
class FixNgCodecFactory : IPipelineCodecFactory {
    private lateinit var context: IPipelineCodecContext
    override val protocols: Set<String>
        get() = PROTOCOLS

    override val settingsClass: Class<out IPipelineCodecSettings> = FixNgCodecSettings::class.java

    override fun init(pipelineCodecContext: IPipelineCodecContext) {
        this.context = pipelineCodecContext
    }

    override fun create(settings: IPipelineCodecSettings?): IPipelineCodec {
        check(::context.isInitialized) { "'codecContext' was not loaded" }
        val codecSettings = requireNotNull(settings as? FixNgCodecSettings) {
            "settings is not an instance of ${FixNgCodecSettings::class.java}: ${settings?.let { it::class.java }}"
        }
        return FixNgCodec(
            context[codecSettings.dictionary].use(XmlDictionaryStructureLoader()::load),
            requireNotNull(codecSettings as? FixNgCodecSettings) {
                "settings is not an instance of ${FixNgCodecSettings::class.java}: $codecSettings"
            }
        )
    }

    companion object {
        const val PROTOCOL = "fix"
        private val PROTOCOLS = setOf(PROTOCOL)
    }
}