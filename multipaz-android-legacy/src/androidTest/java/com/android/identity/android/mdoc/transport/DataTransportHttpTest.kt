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
package com.android.identity.android.mdoc.transport

import android.os.ConditionVariable
import androidx.test.platform.app.InstrumentationRegistry
import org.multipaz.util.fromHex
import org.junit.Assert
import org.junit.Test
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodHttp
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class DataTransportHttpTest {

    // TODO: add tests for TLS support
    @Test
    fun uriParsing() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val options = DataTransportOptions.Builder().build()
        var cm = MdocConnectionMethodHttp("http://www.example.com/mdlReader/session123")
        var transport = DataTransport.fromConnectionMethod(
            appContext, cm, DataTransport.Role.MDOC, options
        ) as DataTransportHttp
        Assert.assertEquals("www.example.com", transport.host)
        Assert.assertEquals("/mdlReader/session123", transport.path)
        Assert.assertEquals(80, transport.port.toLong())
        Assert.assertFalse(transport.useTls)
        cm = MdocConnectionMethodHttp("http://www.example2.com:1234/mdlVerifier/session456")
        transport = DataTransport.fromConnectionMethod(
            appContext, cm, DataTransport.Role.MDOC, options
        ) as DataTransportHttp
        Assert.assertEquals("www.example2.com", transport.host)
        Assert.assertEquals("/mdlVerifier/session456", transport.path)
        Assert.assertEquals(1234, transport.port.toLong())
        Assert.assertFalse(transport.useTls)
        cm = MdocConnectionMethodHttp("https://www.example3.net/mdocreader/s42")
        transport = DataTransport.fromConnectionMethod(
            appContext, cm, DataTransport.Role.MDOC, options
        ) as DataTransportHttp
        Assert.assertEquals("www.example3.net", transport.host)
        Assert.assertEquals("/mdocreader/s42", transport.path)
        Assert.assertEquals(443, transport.port.toLong())
        Assert.assertTrue(transport.useTls)
        cm = MdocConnectionMethodHttp("https://www.example.com:8080/mdocreader/s43")
        transport = DataTransport.fromConnectionMethod(
            appContext, cm, DataTransport.Role.MDOC, options
        ) as DataTransportHttp
        Assert.assertEquals("www.example.com", transport.host)
        Assert.assertEquals("/mdocreader/s43", transport.path)
        Assert.assertEquals(8080, transport.port.toLong())
        Assert.assertTrue(transport.useTls)
        cm = MdocConnectionMethodHttp("unsupported://www.example.com/mdocreader/s43")
        try {
            DataTransport.fromConnectionMethod(
                appContext, cm, DataTransport.Role.MDOC, options
            ) as DataTransportHttp
            Assert.fail()
        } catch (e: IllegalArgumentException) {
            // expected path
        }
    }

    @Test
    fun connectAndListen() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val verifier = DataTransportHttp(
            appContext,
            DataTransport.Role.MDOC_READER,
            null,
            DataTransportOptions.Builder().build()
        )
        val messageSentByVerifier = "010203".fromHex()
        val messageSentByProver = "0405".fromHex()
        val messageReceivedByProver = arrayOf<ByteArray?>(null)
        val messageReceivedByVerifier = arrayOf<ByteArray?>(null)
        val proverMessageReceivedCondVar = ConditionVariable()
        val proverPeerConnectedCondVar = ConditionVariable()
        val proverPeerDisconnectedCondVar = ConditionVariable()
        val verifierMessageReceivedCondVar = ConditionVariable()
        val verifierPeerConnectedCondVar = ConditionVariable()
        val executor: Executor = Executors.newSingleThreadExecutor()
        verifier.setListener(object : DataTransport.Listener {
            override fun onConnecting() {
                Assert.fail()
            }

            override fun onConnected() {
                verifierPeerConnectedCondVar.open()
            }

            override fun onDisconnected() {
                Assert.fail()
            }

            override fun onTransportSpecificSessionTermination() {
                Assert.fail()
            }

            override fun onError(error: Throwable) {
                Assert.fail()
            }

            override fun onMessageReceived() {
                val data = verifier.getMessage()
                messageReceivedByVerifier[0] = data!!.clone()
                verifierMessageReceivedCondVar.open()
            }
        }, executor)
        verifier.connect()
        val verifierConnectionMethod = verifier.connectionMethodForTransport as MdocConnectionMethodHttp
        val prover = DataTransportHttp(
            appContext,
            DataTransport.Role.MDOC,
            verifierConnectionMethod,
            DataTransportOptions.Builder().build()
        )
        prover.setListener(object : DataTransport.Listener {
            override fun onConnecting() {}
            override fun onConnected() {
                proverPeerConnectedCondVar.open()
            }

            override fun onDisconnected() {
                proverPeerDisconnectedCondVar.open()
            }

            override fun onTransportSpecificSessionTermination() {
                Assert.fail()
            }

            override fun onError(error: Throwable) {
                throw AssertionError(error)
            }

            override fun onMessageReceived() {
                val data = prover.getMessage()
                messageReceivedByProver[0] = data!!.clone()
                proverMessageReceivedCondVar.open()
            }
        }, executor)
        prover.connect()
        Assert.assertTrue(proverPeerConnectedCondVar.block(5000))
        prover.sendMessage(messageSentByProver)
        Assert.assertTrue(verifierPeerConnectedCondVar.block(5000))
        verifier.sendMessage(messageSentByVerifier)
        Assert.assertTrue(verifierMessageReceivedCondVar.block(5000))
        Assert.assertTrue(proverMessageReceivedCondVar.block(5000))
        Assert.assertArrayEquals(messageSentByProver, messageReceivedByVerifier[0])
        Assert.assertArrayEquals(messageSentByVerifier, messageReceivedByProver[0])
    }
}
