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
package org.multipaz.mdoc.engagement

import kotlinx.coroutines.test.runTest
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.origininfo.OriginInfo
import org.multipaz.mdoc.origininfo.OriginInfoDomain
import org.multipaz.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngagementGeneratorTest {
    @Test
    fun testNoConnectionMethodsOrOriginInfos() = runTest {
        val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eg = EngagementGenerator(
            eSenderKey.publicKey,
            EngagementGenerator.ENGAGEMENT_VERSION_1_0
        )
        val encodedEngagement = eg.generate()
        val parser = EngagementParser(encodedEngagement)
        val engagement = parser.parse()
        assertEquals(engagement.eSenderKey, eSenderKey.publicKey)
        assertEquals(EngagementGenerator.ENGAGEMENT_VERSION_1_0, engagement.version)
        assertEquals(0, engagement.connectionMethods.size.toLong())
        assertEquals(0, engagement.originInfos.size.toLong())
    }

    @Test
    @Throws(Exception::class)
    fun testDeviceEngagementQrBleCentralClientMode() = runTest {
        val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val uuid = UUID.randomUUID()
        val eg = EngagementGenerator(
            eSenderKey.publicKey,
            EngagementGenerator.ENGAGEMENT_VERSION_1_0
        )
        val connectionMethods = mutableListOf<MdocConnectionMethod>()
        connectionMethods.add(
            MdocConnectionMethodBle(
                false,
                true,
                null,
                uuid
            )
        )
        eg.addConnectionMethods(connectionMethods)
        val encodedEngagement = eg.generate()
        val parser = EngagementParser(encodedEngagement)
        val engagement = parser.parse()
        assertEquals(engagement.eSenderKey, eSenderKey.publicKey)
        assertEquals(EngagementGenerator.ENGAGEMENT_VERSION_1_0, engagement.version)
        assertEquals(1, engagement.connectionMethods.size.toLong())
        val cm = engagement.connectionMethods[0] as MdocConnectionMethodBle
        assertFalse(cm.supportsPeripheralServerMode)
        assertTrue(cm.supportsCentralClientMode)
        assertNull(cm.peripheralServerModeUuid)
        assertEquals(uuid, cm.centralClientModeUuid)
        assertEquals(0, engagement.originInfos.size.toLong())
    }
}
