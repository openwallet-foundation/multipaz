package org.multipaz.mdoc.nfc

import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.NfcCommandFailedException
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.ResponseApdu
import org.multipaz.util.UUID
import org.multipaz.util.fromHex
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Unit tests for [MdocNfcEngagementHelper] (wallet-side NDEF engagement) and [mdocReaderNfcHandover]
 * (reader-side NDEF handover) according to ISO/IEC 18013-5:2021.
 *
 * ### How ISO 18013-5 NDEF Engagement is structured and tested here
 * In ISO/IEC 18013-5:2021, NFC engagement emulates an NFC Forum Type 4 Tag (AID `D2760000850101`)
 * exposing a Capability Container (`E103`) and an NDEF file (`E104`):
 * 1. **Static Handover**:
 *    - The Capability Container marks the NDEF file as read-only (`0xFF`).
 *    - When the reader reads `E104`, [MdocNfcEngagementHelper] returns a `Handover Select` (`Hs`)
 *      NDEF message containing the `mdoc` `DeviceEngagement` record and carrier configuration records
 *      (BLE OOB and/or NFC data transfer) for all `staticHandoverMethods`.
 *    - The reader parses the `Hs` message and selects one of the offered connection methods.
 * 2. **Negotiated Handover**:
 *    - The Capability Container marks the NDEF file as read/write (`0x00`), and the initial NDEF file
 *      contains a Tag NDEF Exchange Protocol (TNEP) `Service Parameter` (`Tp`) record for
 *      `urn:nfc:sn:handover`.
 *    - The reader writes a TNEP `Service Select` (`Ts`) record via `UPDATE BINARY` and reads back a
 *      `TNEP Status` (`Te`) record (`status = 0x00`).
 *    - The reader writes a `Handover Request` (`Hr`) NDEF message containing its `ReaderEngagement`
 *      and offered connection methods (e.g. BLE Central Client Mode with optional L2CAP PSM, BLE
 *      Peripheral Server Mode, NFC data transfer).
 *    - [MdocNfcEngagementHelper] invokes `negotiatedHandoverPicker` to pick a single connection method,
 *      builds `DeviceEngagement` (omitting the BLE UUID in the NDEF record since it was already
 *      provided by the reader's `Hr` message, while [mdocReaderNfcHandover] supplements the reader's
 *      UUID back onto the returned [MdocConnectionMethodBle]), and returns the `Handover Select` (`Hs`)
 *      message for the reader to read via `READ BINARY`.
 *
 * ### Test doubles used in this file
 * - [LoopbackIsoTag]: In-memory [NfcIsoTag] that routes every [CommandApdu] from [mdocReaderNfcHandover]
 *   directly into [MdocNfcEngagementHelper.processApdu] and records the full command/response APDU
 *   transcript for byte-for-byte verification against expected ISO 7816-4 / Type 4 Tag flows.
 */
class MdocNfcEngagementHelperTest {

    /**
     * In-memory [NfcIsoTag] that forwards [transceive] calls to [MdocNfcEngagementHelper.processApdu]
     * and records a complete APDU transcript.
     */
    class LoopbackIsoTag(val engagementHelper: MdocNfcEngagementHelper): NfcIsoTag() {
        val transcript = StringBuilder()

        override val maxTransceiveLength: Int
            get() = 65536

        override suspend fun close() {
            transcript.appendLine("close")
        }

        override suspend fun updateDialogMessage(message: String) {}

        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            transcript.appendLine("${command}")
            val response = engagementHelper.processApdu(command)
            transcript.appendLine("${response}")
            return response
        }
    }

    private fun getConnectionMethods(): List<MdocConnectionMethod> {
        // Include all ConnectionMethods that can exist in OOB data. Use static identifiers
        // to ensure we get the same transcripts every time.
        val bleUuid = UUID.fromString("b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6")
        return listOf(
            MdocConnectionMethodBle(
                supportsPeripheralServerMode = false,
                supportsCentralClientMode = true,
                peripheralServerModeUuid = null,
                centralClientModeUuid = bleUuid
            ),
            MdocConnectionMethodBle(
                supportsPeripheralServerMode = true,
                supportsCentralClientMode = false,
                peripheralServerModeUuid = bleUuid,
                centralClientModeUuid = null
            ),
            MdocConnectionMethodNfc(
                commandDataFieldMaxLength = 0xffff,
                responseDataFieldMaxLength = 0x10000
            )
        )
    }

    private fun getEDeviceKeyPub(): EcPublicKey {
        // Use static key to ensure we get the same transcripts every time.
        return EcPublicKeyDoubleCoordinate(
            curve = EcCurve.P256,
            x = "7104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d".fromHex(),
            y = "f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba2".fromHex()
        )
    }

    /**
     * Verifies end-to-end NFC Static Handover between [mdocReaderNfcHandover] and
     * [MdocNfcEngagementHelper] offering BLE Central Client Mode, BLE Peripheral Server Mode, and NFC
     * data transfer, checking both the disambiguated connection methods and the exact Type 4 Tag APDU
     * transcript (`SELECT AID` -> `SELECT CC` -> `READ BINARY CC` -> `SELECT NDEF` -> `READ BINARY NDEF`).
     */
    @Test
    fun testStaticHandover() = runTest {
        val staticHandoverConnectionMethods = getConnectionMethods()

        val eDeviceKeyPub = getEDeviceKeyPub()
        val engagementHelper = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKeyPub,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                assertEquals(staticHandoverConnectionMethods, connectionMethods)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            staticHandoverMethods = staticHandoverConnectionMethods,
        )

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = getConnectionMethods(),
        )
        assertNotNull(handoverResult)
        assertEquals(staticHandoverConnectionMethods, handoverResult.connectionMethods)

        assertEquals(
            """
                CommandApdu(cla=0, ins=164, p1=4, p2=0, payload=ByteString(size=7 hex=d2760000850101), le=0)
                ResponseApdu(status=36864, payload=ByteString(size=0))
                CommandApdu(cla=0, ins=164, p1=0, p2=12, payload=ByteString(size=2 hex=e103), le=0)
                ResponseApdu(status=36864, payload=ByteString(size=0))
                CommandApdu(cla=0, ins=176, p1=0, p2=0, payload=ByteString(size=0), le=15)
                ResponseApdu(status=36864, payload=ByteString(size=15 hex=000f207fff7fff0406e1047fff00ff))
                CommandApdu(cla=0, ins=164, p1=0, p2=12, payload=ByteString(size=2 hex=e104), le=0)
                ResponseApdu(status=36864, payload=ByteString(size=0))
                CommandApdu(cla=0, ins=176, p1=0, p2=0, payload=ByteString(size=0), le=2)
                ResponseApdu(status=36864, payload=ByteString(size=2 hex=0106))
                CommandApdu(cla=0, ins=176, p1=0, p2=2, payload=ByteString(size=0), le=262)
                ResponseApdu(status=36864, payload=ByteString(size=262 hex=91021f487315910209616301013001046d646f6351020b616301036e666301046d646f631c1e600469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a40063312e31018201d818584ba4010220012158207104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d225820f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba2058006a203f504f51a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c031107b66eef55ee782ea2514bb6a1c42ad5b35c110a0369736f2e6f72673a31383031333a6e66636e6663010301ffff0402010000))
            """.trimIndent().trim(),
            tag.transcript.toString().trim()
        )
    }

    /**
     * Verifies end-to-end NFC Negotiated Handover between [mdocReaderNfcHandover] and
     * [MdocNfcEngagementHelper], checking the full TNEP service selection (`Tp` -> `Ts` -> `Te`) and
     * handover (`Hr` -> `Hs`) APDU transcript as well as BLE UUID supplementation on the reader side.
     */
    @Test
    fun testNegotiatedHandover() = runTest {
        val negotiatedHandoverConnectionMethods = getConnectionMethods()

        val eDeviceKeyPub = getEDeviceKeyPub()
        val engagementHelper = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKeyPub,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                assertEquals(1, connectionMethods.size)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            negotiatedHandoverPicker = { methods -> methods.first() }
        )

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = negotiatedHandoverConnectionMethods,
        )
        assertNotNull(handoverResult)
        assertEquals(1, handoverResult.connectionMethods.size)
        assertEquals(
            negotiatedHandoverConnectionMethods.first(),
            handoverResult.connectionMethods.first()
        )

        assertEquals(
            """
                CommandApdu(cla=0, ins=164, p1=4, p2=0, payload=ByteString(size=7 hex=d2760000850101), le=0)
                ResponseApdu(status=36864, payload=ByteString(size=0))
                CommandApdu(cla=0, ins=164, p1=0, p2=12, payload=ByteString(size=2 hex=e103), le=0)
                ResponseApdu(status=36864, payload=ByteString(size=0))
                CommandApdu(cla=0, ins=176, p1=0, p2=0, payload=ByteString(size=0), le=15)
                ResponseApdu(status=36864, payload=ByteString(size=15 hex=000f207fff7fff0406e1047fff0000))
                CommandApdu(cla=0, ins=164, p1=0, p2=12, payload=ByteString(size=2 hex=e104), le=0)
                ResponseApdu(status=36864, payload=ByteString(size=0))
                CommandApdu(cla=0, ins=176, p1=0, p2=0, payload=ByteString(size=0), le=2)
                ResponseApdu(status=36864, payload=ByteString(size=2 hex=001f))
                CommandApdu(cla=0, ins=176, p1=0, p2=2, payload=ByteString(size=0), le=31)
                ResponseApdu(status=36864, payload=ByteString(size=31 hex=d1021a5470101375726e3a6e66633a736e3a68616e646f76657200000fffff))
                CommandApdu(cla=0, ins=214, p1=0, p2=0, payload=ByteString(size=27 hex=0019d1021454731375726e3a6e66633a736e3a68616e646f766572), le=0)
                ResponseApdu(status=36864, payload=ByteString(size=0))
                CommandApdu(cla=0, ins=176, p1=0, p2=0, payload=ByteString(size=0), le=2)
                ResponseApdu(status=36864, payload=ByteString(size=2 hex=0006))
                CommandApdu(cla=0, ins=176, p1=0, p2=2, payload=ByteString(size=0), le=6)
                ResponseApdu(status=36864, payload=ByteString(size=6 hex=d10201546500))
                CommandApdu(cla=0, ins=214, p1=0, p2=0, payload=ByteString(size=170 hex=00a8910215487215910204616301013000510206616301036e6663001c1e060a69736f2e6f72673a31383031333a726561646572656e676167656d656e746d646f63726561646572a10063312e301a2015016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c031107b66eef55ee782ea2514bb6a1c42ad5b35c110a0369736f2e6f72673a31383031333a6e66636e6663010301ffff0402010000), le=0)
                ResponseApdu(status=36864, payload=ByteString(size=0))
                CommandApdu(cla=0, ins=176, p1=0, p2=0, payload=ByteString(size=0), le=2)
                ResponseApdu(status=36864, payload=ByteString(size=2 hex=00ba))
                CommandApdu(cla=0, ins=176, p1=0, p2=2, payload=ByteString(size=0), le=186)
                ResponseApdu(status=36864, payload=ByteString(size=186 hex=91020f487315d10209616301013001046d646f631c1e580469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a20063312e30018201d818584ba4010220012158207104f7e2c2e95ca76482c0c963d454b7e5d053c5b59ce89d00ff7c7d7ab6ff7d225820f442821292c2453ec67c75233ea56e1734c211ae26b259fdf232b5b3d82b1ba25a2003016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c01))
            """.trimIndent().trim(),
            tag.transcript.toString().trim()
        )
    }

    /**
     * Verifies that when [MdocConnectionMethodBle] entries for BLE Central Client Mode and BLE
     * Peripheral Server Mode have different UUIDs (which cannot be combined into a single BLE OOB NDEF
     * carrier configuration record), [MdocNfcEngagementHelper] reports the error via `onError` and
     * responds with `6F00` during Static Handover.
     */
    @Test
    fun testStaticBleDifferentUUIDs() = runTest {
        val bleUuid1 = UUID.fromString("b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6")
        val bleUuid2 = UUID.fromString("b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb7")
        val staticHandoverConnectionMethods = listOf(
            MdocConnectionMethodBle(
                supportsPeripheralServerMode = false,
                supportsCentralClientMode = true,
                peripheralServerModeUuid = null,
                centralClientModeUuid = bleUuid1
            ),
            MdocConnectionMethodBle(
                supportsPeripheralServerMode = true,
                supportsCentralClientMode = false,
                peripheralServerModeUuid = bleUuid2,
                centralClientModeUuid = null
            ),
        )

        var errorFromEngagementHelper: Throwable? = null
        val eDeviceKeyPub = getEDeviceKeyPub()
        val engagementHelper = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKeyPub,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                assertEquals(staticHandoverConnectionMethods, connectionMethods)
            },
            onError = { error ->
                errorFromEngagementHelper = error
            },
            staticHandoverMethods = staticHandoverConnectionMethods,
        )

        val e = assertFailsWith<NfcCommandFailedException> {
            val tag = LoopbackIsoTag(engagementHelper)
            val result = mdocReaderNfcHandover(
                tag = tag,
                negotiatedHandoverConnectionMethods = staticHandoverConnectionMethods,
            )
        }
        assertEquals("Error selecting file, status 6f00", e.message)

        assertNotNull(errorFromEngagementHelper)
        assertEquals("Error processing APDU: UUIDs for both BLE modes are not the same", errorFromEngagementHelper.message)
    }

    /**
     * Verifies that [mdocReaderNfcHandover] throws [IllegalArgumentException] when asked to combine
     * Negotiated Handover BLE connection methods whose Central Client Mode and Peripheral Server Mode
     * UUIDs do not match.
     */
    @Test
    fun testNegotiatedBleDifferentUUIDs() = runTest {
        val bleUuid1 = UUID.fromString("b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6")
        val bleUuid2 = UUID.fromString("b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb7")
        val connectionMethods = listOf(
            MdocConnectionMethodBle(
                supportsPeripheralServerMode = false,
                supportsCentralClientMode = true,
                peripheralServerModeUuid = null,
                centralClientModeUuid = bleUuid1
            ),
            MdocConnectionMethodBle(
                supportsPeripheralServerMode = true,
                supportsCentralClientMode = false,
                peripheralServerModeUuid = bleUuid2,
                centralClientModeUuid = null
            ),
        )

        val eDeviceKeyPub = getEDeviceKeyPub()
        val engagementHelper = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKeyPub,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                assertEquals(1, connectionMethods.size)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            negotiatedHandoverPicker = { methods -> methods.first() }
        )

        // This should throw b/c the UUIDs are different..
        val tag = LoopbackIsoTag(engagementHelper)
        val e = assertFailsWith(IllegalArgumentException::class) {
            mdocReaderNfcHandover(
                tag = tag,
                negotiatedHandoverConnectionMethods = connectionMethods,
            )
        }
        assertEquals("UUIDs for both BLE modes are not the same", e.message)
    }

    /**
     * Verifies that an L2CAP PSM (`peripheralServerModePsm`) advertised by the mdoc in BLE Peripheral
     * Server Mode is preserved across NFC Static Handover.
     */
    @Test
    fun testStaticHandoverBlePsm() = runTest {
        val bleUuid = UUID.fromString("b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6")

        val bleCc =  MdocConnectionMethodBle(
            supportsPeripheralServerMode = false,
            supportsCentralClientMode = true,
            peripheralServerModeUuid = null,
            centralClientModeUuid = bleUuid
        )
        val blePs = MdocConnectionMethodBle(
            supportsPeripheralServerMode = true,
            supportsCentralClientMode = false,
            peripheralServerModeUuid = bleUuid,
            centralClientModeUuid = null,
            peripheralServerModePsm = 192,
        )

        val eDeviceKeyPub = getEDeviceKeyPub()
        val engagementHelper = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKeyPub,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                assertEquals(listOf(bleCc, blePs), connectionMethods)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            staticHandoverMethods = listOf(bleCc, blePs),
        )

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = getConnectionMethods(),
        )
        assertNotNull(handoverResult)
        assertEquals(listOf(bleCc, blePs), handoverResult.connectionMethods)
    }

    /**
     * Verifies that an L2CAP PSM (`peripheralServerModePsm`) offered by the reader in BLE Central
     * Client Mode during NFC Negotiated Handover is conveyed to the mdoc and preserved on the selected
     * connection method (and omitted when BLE Peripheral Server Mode is selected instead).
     */
    @Test
    fun testNegotiatedHandoverBlePsm() = runTest {
        val bleUuid = UUID.fromString("b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6")

        val bleCc =  MdocConnectionMethodBle(
            supportsPeripheralServerMode = false,
            supportsCentralClientMode = true,
            peripheralServerModeUuid = null,
            centralClientModeUuid = bleUuid,
            peripheralServerModePsm = 192,
        )
        val blePs = MdocConnectionMethodBle(
            supportsPeripheralServerMode = true,
            supportsCentralClientMode = false,
            peripheralServerModeUuid = bleUuid,
            centralClientModeUuid = null
        )

        val eDeviceKeyPub = getEDeviceKeyPub()
        val engagementHelper = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKeyPub,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                assertEquals(1, connectionMethods.size)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            negotiatedHandoverPicker = { methods -> methods.first() }
        )

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = listOf(bleCc, blePs),
        )
        assertNotNull(handoverResult)
        assertEquals(1, handoverResult.connectionMethods.size)
        assertEquals(
            bleCc,
            handoverResult.connectionMethods.first()
        )

        // Check that there's no PSM if we select the second method
        val engagementHelper2 = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKeyPub,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                assertEquals(1, connectionMethods.size)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            negotiatedHandoverPicker = { methods -> methods[1] }
        )

        val tag2 = LoopbackIsoTag(engagementHelper2)
        val handoverResult2 = mdocReaderNfcHandover(
            tag = tag2,
            negotiatedHandoverConnectionMethods = listOf(bleCc, blePs),
        )
        assertNotNull(handoverResult2)
        assertEquals(1, handoverResult2.connectionMethods.size)
        assertEquals(
            blePs,
            handoverResult2.connectionMethods.first()
        )

        // Also check that if the holder (e.g., multipaz-android-legacy or any standard ISO 18013-5
        // holder that does not support L2CAP in engagement) returns a BLE Central Client Mode
        // Handover Select record with peripheralServerModePsm = null, mdocReaderNfcHandover still
        // supplements the reader's peripheralServerModePsm so the pre-warmed reader transport matches.
        val engagementHelper3 = MdocNfcEngagementHelper(
            eDeviceKey = eDeviceKeyPub,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                assertEquals(1, connectionMethods.size)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            negotiatedHandoverPicker = { _ ->
                MdocConnectionMethodBle(
                    supportsPeripheralServerMode = false,
                    supportsCentralClientMode = true,
                    peripheralServerModeUuid = null,
                    centralClientModeUuid = bleUuid,
                    peripheralServerModePsm = null,
                )
            }
        )

        val tag3 = LoopbackIsoTag(engagementHelper3)
        val handoverResult3 = mdocReaderNfcHandover(
            tag = tag3,
            negotiatedHandoverConnectionMethods = listOf(bleCc, blePs),
        )
        assertNotNull(handoverResult3)
        assertEquals(1, handoverResult3.connectionMethods.size)
        assertEquals(
            bleCc,
            handoverResult3.connectionMethods.first()
        )
    }

    private suspend fun testNfcEngagementHelper(
        testBlock: suspend (tag: NfcIsoTag) -> Unit
    ): Pair<Boolean, Throwable?> {
        var handoverSuccess = false
        var handoverError: Throwable? = null
        val helper = MdocNfcEngagementHelper(
            eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover -> handoverSuccess = true },
            onError = { error -> handoverError = error },
            staticHandoverMethods = getConnectionMethods(),
        )
        testBlock(LoopbackIsoTag(helper))
        return Pair(handoverSuccess, handoverError)
    }

    /**
     * Verifies that selecting an application ID other than [Nfc.NDEF_APPLICATION_ID] fails with
     * [NfcCommandFailedException] (`6A82`) and invokes `onError` on [MdocNfcEngagementHelper].
     */
    @Test
    fun testWrongApplicationIdSelected() = runTest {
        var (handoverSuccess, handoverError) = testNfcEngagementHelper { tag ->
            // Off by one from the NDEF AID
            assertFailsWith(NfcCommandFailedException::class) {
                tag.selectApplication(ByteString("D2760000850102".fromHex()))
            }
        }
        assertFalse(handoverSuccess)
        assertNotNull(handoverError)
        assertEquals(
            "SelectApplication: Expected NDEF AID but got d2760000850102",
            handoverError.message
        )
    }

    /**
     * Verifies that attempting to `SELECT FILE` before `SELECT APPLICATION` (`D2760000850101`) fails
     * and invokes `onError` on [MdocNfcEngagementHelper].
     */
    @Test
    fun testFiledIdBeforeApplicationSelectSelected() = runTest {
        var (handoverSuccess, handoverError) = testNfcEngagementHelper { tag ->
            // Fails because application is not yet selected
            assertFailsWith(NfcCommandFailedException::class) {
                tag.selectFile(Nfc.NDEF_CAPABILITY_CONTAINER_FILE_ID)
            }
        }
        assertFalse(handoverSuccess)
        assertNotNull(handoverError)
        assertEquals(
            "Error processing APDU: NDEF application not yet selected",
            handoverError.message
        )
    }

    /**
     * Verifies that selecting an unknown file ID (neither Capability Container `E103` nor NDEF file
     * `E104`) fails with `6A82` and invokes `onError` on [MdocNfcEngagementHelper].
     */
    @Test
    fun testUnexpectedFileIdSelected() = runTest {
        var (handoverSuccess, handoverError) = testNfcEngagementHelper { tag ->
            tag.selectApplication(Nfc.NDEF_APPLICATION_ID)
            // Fails because the FileID is unknown
            assertFailsWith(NfcCommandFailedException::class) {
                tag.selectFile(42)
            }
        }
        assertFalse(handoverSuccess)
        assertNotNull(handoverError)
        assertEquals(
            "SelectFile: Unexpected File ID 42",
            handoverError.message
        )
    }

    // TODO: add more tests to exercise implementation of READ_BINARY and UPDATE_BINARY

    /**
     * Verifies constructor precondition checks on [MdocNfcEngagementHelper] (requiring either
     * `staticHandoverMethods` or `negotiatedHandoverPicker`, prohibiting both simultaneously, and
     * requiring `staticHandoverMethods` to be non-empty when specified).
     */
    @Test
    fun testConstructor() = runTest {
        assertFailsWith(
            IllegalStateException::class,
            "Must use either static or negotiated handover, none are selected"
        ) {
            MdocNfcEngagementHelper(
                eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
                onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover -> },
                onError = { error -> }
            )
        }

        assertFailsWith(
            IllegalStateException::class,
            "Can't use both static and negotiated handover at the same time"
        ) {
            MdocNfcEngagementHelper(
                eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
                onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover -> },
                onError = { error -> },
                staticHandoverMethods = getConnectionMethods(),
                negotiatedHandoverPicker = { methods -> methods.first() }
            )
        }


        assertFailsWith(
            IllegalStateException::class,
            "Must have at least one ConnectionMethod for static handover"
        ) {
            MdocNfcEngagementHelper(
                eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
                onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover -> },
                onError = { error -> },
                staticHandoverMethods = listOf()
            )
        }

        MdocNfcEngagementHelper(
            eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover -> },
            onError = { error -> },
            negotiatedHandoverPicker = { methods -> methods.first() }
        )

        MdocNfcEngagementHelper(
            eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover -> },
            onError = { error -> },
            staticHandoverMethods = getConnectionMethods()
        )
    }

    /**
     * Verifies that if a reader re-selects the NDEF file (`E104`) after already reading it during Static
     * Handover, [MdocNfcEngagementHelper] serves the cached NDEF payload without firing a duplicate
     * `onHandoverComplete` callback or raising an error.
     */
    @Test
    fun testStaticHandoverReselectNdefFile() = runTest {
        val staticHandoverConnectionMethods = getConnectionMethods()
        var handoverCompleteCalls = 0
        val engagementHelper = MdocNfcEngagementHelper(
            eDeviceKey = getEDeviceKeyPub(),
            onHandoverComplete = { connectionMethods, _, _ ->
                handoverCompleteCalls += 1
                assertEquals(staticHandoverConnectionMethods, connectionMethods)
            },
            onError = { error ->
                fail("onError should not be called with $error")
            },
            staticHandoverMethods = staticHandoverConnectionMethods,
        )

        val tag = LoopbackIsoTag(engagementHelper)
        tag.selectApplication(Nfc.NDEF_APPLICATION_ID)
        tag.selectFile(Nfc.NDEF_CAPABILITY_CONTAINER_FILE_ID)
        tag.readBinary(0, 15)
        tag.selectFile(0xe104)
        val firstLengthBytes = tag.readBinary(0, 2)
        assertEquals(1, handoverCompleteCalls)

        // Re-select file 0xe104 within the same engagement without raising an error or duplicate callback
        tag.selectFile(0xe104)
        val secondLengthBytes = tag.readBinary(0, 2)
        assertEquals(1, handoverCompleteCalls)
        assertContentEquals(firstLengthBytes, secondLengthBytes)
    }
}