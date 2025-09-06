/*
 * Copyright 2022 The Android Open Source Project
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
import org.multipaz.util.Logger

/**
 * A class representing the OriginInfo structure exchanged by the mdoc and the mdoc reader.
 */
abstract class OriginInfo {
    abstract fun toDataItem(): DataItem

    companion object {
        private const val TAG = "OriginInfo"

        fun fromDataItem(dataItem: DataItem): OriginInfo? =
            dataItem["type"].asNumber.let { type ->
                if (type == OriginInfoDomain.TYPE) {
                    return OriginInfoDomain.fromDataItem(dataItem)
                }
                Logger.w(TAG, "Unsupported type $type")
                null
            }
    }
}