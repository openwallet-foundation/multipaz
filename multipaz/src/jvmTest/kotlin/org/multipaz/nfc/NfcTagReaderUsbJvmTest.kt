package org.multipaz.nfc

import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NfcTagReaderUsbJvmTest {

    @Test
    fun resolveLinuxUsbBusAndAddressSupportsUsbfsSymlinksAndSysfsPorts() {
        val tempRoot = Files.createTempDirectory("multipaz_usb_test_").toFile()
        try {
            val devBusUsb = File(tempRoot, "dev/bus/usb")
            val bus1Dir = File(devBusUsb, "001").apply { mkdirs() }
            val node4 = File(bus1Dir, "004").apply { writeBytes(ByteArray(0)) }
            val node5 = File(bus1Dir, "005").apply { writeBytes(ByteArray(0)) }

            // 1. Direct usbfs node path
            assertEquals(
                1 to 4,
                resolveLinuxUsbBusAndAddress(
                    devicePath = node4.absolutePath,
                    devBusUsbRoot = devBusUsb
                )
            )

            // 2. udev symlink to usbfs node + hotplug symlink target update
            val symlinkPath = File(tempRoot, "dev/nfc-reader-front").toPath()
            Files.createSymbolicLink(symlinkPath, node4.toPath())
            assertEquals(
                1 to 4,
                resolveLinuxUsbBusAndAddress(
                    devicePath = symlinkPath.toString(),
                    devBusUsbRoot = devBusUsb
                )
            )
            Files.delete(symlinkPath)
            Files.createSymbolicLink(symlinkPath, node5.toPath())
            assertEquals(
                1 to 5,
                resolveLinuxUsbBusAndAddress(
                    devicePath = symlinkPath.toString(),
                    devBusUsbRoot = devBusUsb
                )
            )

            // 3. Bare sysfs port name ("1-1.2") and symlinked sysfs port path
            val realSysfsPort = File(tempRoot, "sys/devices/pci0000:00/usb2/2-1/2-1.3").apply { mkdirs() }
            File(realSysfsPort, "busnum").writeText("2\n")
            File(realSysfsPort, "devnum").writeText("19\n")
            val sysfsDevicesRoot = File(tempRoot, "sys/bus/usb/devices").apply { mkdirs() }
            val sysfsPortLink = File(sysfsDevicesRoot, "2-1.3").toPath()
            Files.createSymbolicLink(sysfsPortLink, realSysfsPort.toPath())

            assertEquals(
                2 to 19,
                resolveLinuxUsbBusAndAddress(
                    devicePath = "2-1.3",
                    sysfsRoot = sysfsDevicesRoot,
                    devBusUsbRoot = devBusUsb
                )
            )
            assertEquals(
                2 to 19,
                resolveLinuxUsbBusAndAddress(
                    devicePath = sysfsPortLink.toString(),
                    sysfsRoot = sysfsDevicesRoot,
                    devBusUsbRoot = devBusUsb
                )
            )

            // 4. Rejects missing paths, non-usbfs files, and out-of-range bus/dev numbers
            assertFailsWith<IOException> {
                resolveLinuxUsbBusAndAddress(
                    devicePath = File(tempRoot, "dev/missing").absolutePath,
                    sysfsRoot = sysfsDevicesRoot,
                    devBusUsbRoot = devBusUsb
                )
            }
            val unrelatedFile = File(tempRoot, "not_usb_node").apply { writeText("x") }
            assertFailsWith<IOException> {
                resolveLinuxUsbBusAndAddress(
                    devicePath = unrelatedFile.absolutePath,
                    sysfsRoot = sysfsDevicesRoot,
                    devBusUsbRoot = devBusUsb
                )
            }
            File(realSysfsPort, "devnum").writeText("200\n")
            assertFailsWith<IOException> {
                resolveLinuxUsbBusAndAddress(
                    devicePath = "2-1.3",
                    sysfsRoot = sysfsDevicesRoot,
                    devBusUsbRoot = devBusUsb
                )
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun companionFactoryValidationAndLifecycle() = runTest {
        assertFailsWith<IllegalArgumentException> {
            NfcTagReaderUsb.create(vendorId = -1, productId = 0x223B)
        }
        assertFailsWith<IllegalArgumentException> {
            NfcTagReaderUsb.create(vendorId = 0x10000, productId = 0x223B)
        }
        assertFailsWith<IllegalArgumentException> {
            NfcTagReaderUsb.create(vendorId = 0x072F, productId = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NfcTagReaderUsb.create(vendorId = 0x072F, productId = 0x223B, interfaceIndex = 256)
        }
        assertFailsWith<IllegalArgumentException> {
            NfcTagReaderUsb.create(
                vendorId = 0x072F,
                productId = 0x223B,
                disconnectDelay = (-1).milliseconds
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NfcTagReaderUsb.create(devicePath = "   ")
        }
        assertFailsWith<IllegalArgumentException> {
            NfcTagReaderUsb.createFromFileDescriptor(fileDescriptor = -1)
        }

        val byVidPid = NfcTagReaderUsb.create(
            vendorId = 0x072F,
            productId = 0x223B,
            disconnectDelay = 2.seconds
        )
        assertTrue(byVidPid.external)
        byVidPid.close()
        assertFailsWith<IllegalStateException> {
            byVidPid.scan<String>(message = null, tagInteractionFunc = { "ok" })
        }

        val byPath = NfcTagReaderUsb.create(devicePath = "/dev/bus/usb/001/004")
        byPath.close()

        val byFd = NfcTagReaderUsb.createFromFileDescriptor(fileDescriptor = 42, interfaceIndex = 0)
        byFd.close()
    }
}
