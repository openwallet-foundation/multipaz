/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multipaz.mdoc.origininfo

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap

data class OriginInfoDomain(val url: String) : OriginInfo() {
    override fun toDataItem() = buildCborMap {
        put("cat", CAT)
        put("type", TYPE)
        putCborMap("details") {
            put("domain", url)
        }
    }

    companion object {
        const val CAT = 1L
        const val TYPE = 1L

        fun fromDataItem(dataItem: DataItem): OriginInfoDomain? {
            val cat = dataItem["cat"].asNumber
            val type = dataItem["type"].asNumber
            require(cat == CAT && type == TYPE) {
                "This CBOR object has the wrong category or type. Expected cat = $CAT, " +
                        "type = $TYPE but got cat = $cat, type = $type"
            }
            val details = dataItem["details"]
            return OriginInfoDomain(details["domain"].asTstr)
        }
    }
}
