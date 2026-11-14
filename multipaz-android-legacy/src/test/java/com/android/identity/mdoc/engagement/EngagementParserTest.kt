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
package com.android.identity.mdoc.engagement

import com.android.identity.mdoc.connectionmethod.MdocConnectionMethodBle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.multipaz.util.fromHex
import org.multipaz.util.toHex

class EngagementParserTest {
    @Test
    fun testDeviceRequestEngagementWithVectors() {
        val deviceEngagementHex =
            "a30063312e30018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2" +
                "e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a" +
                "8cfcf3de6aa672fc60a557aa32fc670281830201a300f401f50b5045efef742b2c4837a9a" +
                "3b0e1d05a6917"
        val expectedEDeviceKeyBytesHex =
            "d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa" +
                "444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60" +
                "a557aa32fc67"
        val parser = EngagementParser(deviceEngagementHex.fromHex())
        val engagement = parser.parse()
        assertEquals("1.0", engagement.version)
        val connectionMethods = engagement.connectionMethods
        assertEquals(1, connectionMethods.size.toLong())
        assertTrue(connectionMethods[0] is MdocConnectionMethodBle)
        val cmBle = connectionMethods[0] as MdocConnectionMethodBle
        assertFalse(cmBle.supportsPeripheralServerMode)
        assertTrue(cmBle.supportsCentralClientMode)
        assertNull(cmBle.peripheralServerModeUuid)
        assertEquals(
            "45efef74-2b2c-4837-a9a3-b0e1d05a6917",
            cmBle.centralClientModeUuid.toString()
        )
        assertEquals(expectedEDeviceKeyBytesHex, engagement.eSenderKeyBytes.toHex())
    }
}
