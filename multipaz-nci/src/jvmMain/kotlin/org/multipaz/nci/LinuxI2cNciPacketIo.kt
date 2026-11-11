package org.multipaz.nci

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.multipaz.util.Logger
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

/**
 * Linux I2C (`/dev/i2c-*`) + GPIO character device v2 (`/dev/gpiochip*`) implementation of
 * [NciPacketIo] for NXP PN7160 / PN7150 NFC controllers (such as the NXP OM27160A1EVK board
 * on Raspberry Pi 5).
 *
 * Requires `net.java.dev.jna:jna` on the runtime classpath and read/write access to the configured
 * `/dev/i2c-*` and `/dev/gpiochip*` device nodes (on Raspberry Pi OS, add the user to the `i2c`
 * and `gpio` groups).
 *
 * @param i2cDevicePath Path to the Linux I2C controller character device (e.g. `"/dev/i2c-1"`).
 * @param i2cAddress 7-bit I2C target address of the NFC controller (e.g. `0x28` for NXP PN7160).
 * @param gpioChipPath Path to the Linux GPIO chip character device (e.g. `"/dev/gpiochip0"`).
 * @param irqGpioPin GPIO line offset for the active-high `IRQ` pin (e.g. `23` on OM27160A1EVK).
 * @param venGpioPin GPIO line offset for the active-high `VEN` reset/enable pin (e.g. `24` on OM27160A1EVK).
 */
class LinuxI2cNciPacketIo(
    val i2cDevicePath: String = defaultI2cDevicePath(),
    val i2cAddress: Int = defaultI2cAddress(),
    val gpioChipPath: String = defaultGpioChipPath(),
    val irqGpioPin: Int = defaultIrqGpioPin(),
    val venGpioPin: Int = defaultVenGpioPin(),
) : NciPacketIo {

    companion object {
        private const val TAG = "LinuxI2cNciPacketIo"

        // open() / fcntl() flags
        private const val O_RDWR = 0x0002
        private const val O_NONBLOCK = 0x0800
        private const val O_CLOEXEC = 0x80000
        private const val F_GETFL = 3
        private const val F_SETFL = 4

        // Linux I2C ioctl (<linux/i2c-dev.h>)
        private const val I2C_SLAVE = 0x0703L

        // Linux GPIO v2 ioctls (<linux/gpio.h>)
        // _IOWR(0xB4, 0x07, struct gpio_v2_line_request [592 bytes])
        private const val GPIO_V2_GET_LINE_IOCTL = 0xC250B407L
        // _IOWR(0xB4, 0x0E, struct gpio_v2_line_values [16 bytes])
        private const val GPIO_V2_LINE_GET_VALUES_IOCTL = 0xC010B40EL
        // _IOWR(0xB4, 0x0F, struct gpio_v2_line_values [16 bytes])
        private const val GPIO_V2_LINE_SET_VALUES_IOCTL = 0xC010B40FL

        // Linux GPIO v2 line flags (<linux/gpio.h>: _BITULL(2)=INPUT, _BITULL(3)=OUTPUT, _BITULL(4)=EDGE_RISING)
        private const val GPIO_V2_LINE_FLAG_INPUT = 1L shl 2
        private const val GPIO_V2_LINE_FLAG_OUTPUT = 1L shl 3
        private const val GPIO_V2_LINE_FLAG_EDGE_RISING = 1L shl 4
        private const val GPIO_V2_LINE_FLAG_BIAS_PULL_DOWN = 1L shl 9

        // Linux GPIO v2 attribute IDs
        private const val GPIO_V2_LINE_ATTR_ID_OUTPUT_VALUES = 2

        private const val GPIO_V2_LINE_REQUEST_SIZE = 592
        private const val GPIO_V2_LINE_VALUES_SIZE = 16
        private const val GPIO_V2_LINE_EVENT_SIZE = 48

        // poll() events
        private const val POLLIN: Short = 0x0001

        // errno values
        private const val EINTR = 4
        private const val EIO = 5
        private const val ENXIO = 6
        private const val EAGAIN = 11
        private const val ENOSYS = 38
        private const val EREMOTEIO = 121

        /**
         * Resolves the default I2C device path from `-Dorg.multipaz.nfc.i2cDevice`,
         * `MULTIPAZ_NFC_I2C_DEV`, or `"/dev/i2c-1"`.
         */
        fun defaultI2cDevicePath(): String {
            System.getProperty("org.multipaz.nfc.i2cDevice")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            System.getenv("MULTIPAZ_NFC_I2C_DEV")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            return "/dev/i2c-1"
        }

        /**
         * Resolves the default 7-bit I2C address from `-Dorg.multipaz.nfc.i2cAddress`,
         * `MULTIPAZ_NFC_I2C_ADDR`, or `0x28`.
         */
        fun defaultI2cAddress(): Int {
            parseHexOrDecInt(System.getProperty("org.multipaz.nfc.i2cAddress"))?.let { return it }
            parseHexOrDecInt(System.getenv("MULTIPAZ_NFC_I2C_ADDR"))?.let { return it }
            return 0x28
        }

        /**
         * Resolves the default GPIO chip path from `-Dorg.multipaz.nfc.gpioChip`,
         * `MULTIPAZ_NFC_GPIO_CHIP`, or `"/dev/gpiochip0"`.
         */
        fun defaultGpioChipPath(): String {
            System.getProperty("org.multipaz.nfc.gpioChip")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            System.getenv("MULTIPAZ_NFC_GPIO_CHIP")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            return "/dev/gpiochip0"
        }

        /**
         * Resolves the default `IRQ` GPIO line offset from `-Dorg.multipaz.nfc.irqPin`,
         * `MULTIPAZ_NFC_IRQ_PIN`, or `23` (Raspberry Pi physical pin 16 on NXP OM27160A1EVK).
         */
        fun defaultIrqGpioPin(): Int {
            System.getProperty("org.multipaz.nfc.irqPin")?.trim()?.toIntOrNull()?.let { return it }
            System.getenv("MULTIPAZ_NFC_IRQ_PIN")?.trim()?.toIntOrNull()?.let { return it }
            return 23
        }

        /**
         * Resolves the default `VEN` GPIO line offset from `-Dorg.multipaz.nfc.venPin`,
         * `MULTIPAZ_NFC_VEN_PIN`, or `24` (Raspberry Pi physical pin 18 on NXP OM27160A1EVK).
         */
        fun defaultVenGpioPin(): Int {
            System.getProperty("org.multipaz.nfc.venPin")?.trim()?.toIntOrNull()?.let { return it }
            System.getenv("MULTIPAZ_NFC_VEN_PIN")?.trim()?.toIntOrNull()?.let { return it }
            return 24
        }

        private fun parseHexOrDecInt(raw: String?): Int? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if (trimmed.startsWith("0x", ignoreCase = true)) {
                trimmed.substring(2).toIntOrNull(16)
            } else {
                trimmed.toIntOrNull()
            }
        }

        internal fun ensureJnaAvailable() {
            try {
                Class.forName("com.sun.jna.Native")
            } catch (e: ClassNotFoundException) {
                throw IllegalStateException(
                    "JNA (net.java.dev.jna:jna) must be on the runtime classpath to use LinuxI2cNciPacketIo",
                    e
                )
            }
        }
    }

    private interface LinuxLibc : Library {
        @Throws(LastErrorException::class)
        fun open(pathname: String, flags: Int): Int

        @Throws(LastErrorException::class)
        fun ioctl(fd: Int, request: NativeLong, arg: NativeLong): Int

        @Throws(LastErrorException::class)
        fun ioctl(fd: Int, request: NativeLong, argp: ByteArray): Int

        @Throws(LastErrorException::class)
        fun fcntl(fd: Int, cmd: Int, arg: Int): Int

        @Throws(LastErrorException::class)
        fun read(fd: Int, buf: ByteArray, count: NativeLong): NativeLong

        @Throws(LastErrorException::class)
        fun write(fd: Int, buf: ByteArray, count: NativeLong): NativeLong

        @Throws(LastErrorException::class)
        fun poll(fds: ByteArray, nfds: NativeLong, timeout: Int): Int

        @Throws(LastErrorException::class)
        fun pipe(pipefd: IntArray): Int

        @Throws(LastErrorException::class)
        fun pipe2(pipefd: IntArray, flags: Int): Int

        fun close(fd: Int): Int

        fun strerror(errnum: Int): String?
    }

    private object LibcHolder {
        val INSTANCE: LinuxLibc by lazy {
            Native.load("c", LinuxLibc::class.java)
        }
    }

    @Volatile
    private var i2cFd: Int = -1

    @Volatile
    private var irqLineFd: Int = -1

    @Volatile
    private var venLineFd: Int = -1

    @Volatile
    private var wakePipeReadFd: Int = -1

    @Volatile
    private var wakePipeWriteFd: Int = -1

    @Volatile
    private var closed: Boolean = false

    private val ioMutex = Mutex()
    private val closeLock = Any()

    private fun ensureOpen() {
        synchronized(closeLock) {
            if (closed) {
                throw IOException("LinuxI2cNciPacketIo is closed")
            }
            if (i2cFd >= 0 && irqLineFd >= 0 && venLineFd >= 0 && wakePipeReadFd >= 0) {
                return
            }
            ensureJnaAvailable()
            val libc = LibcHolder.INSTANCE

            // 1. Open wakeup pipe for unblocking poll() on close()
            val pipeFds = IntArray(2)
            try {
                libc.pipe2(pipeFds, O_CLOEXEC or O_NONBLOCK)
            } catch (e: LastErrorException) {
                if (e.errorCode == ENOSYS) {
                    libc.pipe(pipeFds)
                } else {
                    throw IOException("pipe2() failed (errno=${e.errorCode}: ${formatErrno(libc, e.errorCode)})", e)
                }
            }
            wakePipeReadFd = pipeFds[0]
            wakePipeWriteFd = pipeFds[1]

            try {
                // 2. Open I2C bus device and bind target slave address
                val openedI2cFd = try {
                    libc.open(i2cDevicePath, O_RDWR or O_CLOEXEC)
                } catch (e: LastErrorException) {
                    throw IOException(
                        "Failed to open I2C device '$i2cDevicePath' (errno=${e.errorCode}: ${formatErrno(libc, e.errorCode)}). " +
                            "Ensure I2C is enabled (e.g. 'sudo dtparam i2c_arm=on' on Raspberry Pi) and the user is in the 'i2c' group.",
                        e
                    )
                }
                i2cFd = openedI2cFd

                try {
                    libc.ioctl(openedI2cFd, NativeLong(I2C_SLAVE), NativeLong(i2cAddress.toLong()))
                } catch (e: LastErrorException) {
                    throw IOException(
                        "Failed to set I2C_SLAVE=0x${i2cAddress.toString(16)} on '$i2cDevicePath' " +
                            "(errno=${e.errorCode}: ${formatErrno(libc, e.errorCode)})",
                        e
                    )
                }

                // 3. Open GPIO chip and request IRQ (input + rising edge) and VEN (output) lines
                val chipFd = try {
                    libc.open(gpioChipPath, O_RDWR or O_CLOEXEC)
                } catch (e: LastErrorException) {
                    throw IOException(
                        "Failed to open GPIO chip '$gpioChipPath' (errno=${e.errorCode}: ${formatErrno(libc, e.errorCode)}). " +
                            "Ensure the user is in the 'gpio' group.",
                        e
                    )
                }
                try {
                    irqLineFd = requestGpioLine(
                        libc = libc,
                        chipFd = chipFd,
                        lineOffset = irqGpioPin,
                        flags = GPIO_V2_LINE_FLAG_INPUT or GPIO_V2_LINE_FLAG_EDGE_RISING or GPIO_V2_LINE_FLAG_BIAS_PULL_DOWN,
                        initialOutputValue = null,
                        consumer = "multipaz-nci-irq"
                    )
                    // Make irqLineFd non-blocking so we can drain all queued rising-edge events at once
                    try {
                        val fl = libc.fcntl(irqLineFd, F_GETFL, 0)
                        libc.fcntl(irqLineFd, F_SETFL, fl or O_NONBLOCK)
                    } catch (_: LastErrorException) {
                    }
                    venLineFd = requestGpioLine(
                        libc = libc,
                        chipFd = chipFd,
                        lineOffset = venGpioPin,
                        flags = GPIO_V2_LINE_FLAG_OUTPUT,
                        initialOutputValue = 1,
                        consumer = "multipaz-nci-ven"
                    )
                } finally {
                    libc.close(chipFd)
                }
            } catch (e: Throwable) {
                closeDescriptorsLocked(libc)
                throw e
            }
        }
    }

    private fun requestGpioLine(
        libc: LinuxLibc,
        chipFd: Int,
        lineOffset: Int,
        flags: Long,
        initialOutputValue: Int?,
        consumer: String
    ): Int {
        val req = ByteArray(GPIO_V2_LINE_REQUEST_SIZE)
        val buf = ByteBuffer.wrap(req).order(ByteOrder.nativeOrder())
        // offsets[0] = lineOffset
        buf.putInt(0, lineOffset)
        // consumer[32] at offset 256
        val consumerBytes = consumer.encodeToByteArray()
        System.arraycopy(
            consumerBytes,
            0,
            req,
            256,
            minOf(consumerBytes.size, 31)
        )
        // config.flags at offset 288
        buf.putLong(288, flags)
        if (initialOutputValue != null) {
            // config.num_attrs = 1 at offset 296
            buf.putInt(296, 1)
            // config.attrs[0].attr.id = GPIO_V2_LINE_ATTR_ID_OUTPUT_VALUES at offset 320
            buf.putInt(320, GPIO_V2_LINE_ATTR_ID_OUTPUT_VALUES)
            // config.attrs[0].attr.values = initialOutputValue at offset 328
            buf.putLong(328, (initialOutputValue and 1).toLong())
            // config.attrs[0].mask = 1L at offset 336
            buf.putLong(336, 1L)
        }
        // num_lines = 1 at offset 560
        buf.putInt(560, 1)

        try {
            libc.ioctl(chipFd, NativeLong(GPIO_V2_GET_LINE_IOCTL), req)
        } catch (e: LastErrorException) {
            throw IOException(
                "Failed to request GPIO line $lineOffset on '$gpioChipPath' ($consumer) " +
                    "(errno=${e.errorCode}: ${formatErrno(libc, e.errorCode)})",
                e
            )
        }
        val lineFd = buf.getInt(588)
        if (lineFd < 0) {
            throw IOException("GPIO_V2_GET_LINE_IOCTL returned invalid fd=$lineFd for GPIO line $lineOffset")
        }
        return lineFd
    }

    private fun setVenLevel(level: Int) {
        val fd = venLineFd
        if (fd < 0) throw IOException("VEN GPIO line is not open")
        val values = ByteArray(GPIO_V2_LINE_VALUES_SIZE)
        val buf = ByteBuffer.wrap(values).order(ByteOrder.nativeOrder())
        buf.putLong(0, (level and 1).toLong()) // bits
        buf.putLong(8, 1L) // mask = line 0
        try {
            LibcHolder.INSTANCE.ioctl(fd, NativeLong(GPIO_V2_LINE_SET_VALUES_IOCTL), values)
        } catch (e: LastErrorException) {
            throw IOException(
                "Failed to set VEN GPIO line $venGpioPin to $level (errno=${e.errorCode})",
                e
            )
        }
    }

    private fun getIrqLevel(): Int {
        val fd = irqLineFd
        if (fd < 0) return 0
        val values = ByteArray(GPIO_V2_LINE_VALUES_SIZE)
        val buf = ByteBuffer.wrap(values).order(ByteOrder.nativeOrder())
        buf.putLong(8, 1L) // mask = line 0
        return try {
            LibcHolder.INSTANCE.ioctl(fd, NativeLong(GPIO_V2_LINE_GET_VALUES_IOCTL), values)
            (buf.getLong(0) and 1L).toInt()
        } catch (_: LastErrorException) {
            0
        }
    }

    override suspend fun resetHardware() {
        withContext(Dispatchers.IO) {
            ensureOpen()
        }
        setVenLevel(0)
        delay(10.milliseconds)
        setVenLevel(1)
        delay(20.milliseconds)
    }

    private fun drainIrqEvents(libc: LinuxLibc, fd: Int, eventBuf: ByteArray) {
        if (fd < 0) return
        // Drain up to 16 queued GPIO rising-edge events in non-blocking mode
        for (i in 0 until 16) {
            try {
                val n = libc.read(fd, eventBuf, NativeLong(GPIO_V2_LINE_EVENT_SIZE.toLong())).toInt()
                if (n <= 0) break
            } catch (_: LastErrorException) {
                break
            }
        }
    }

    override suspend fun readPacket(): ByteArray? = withContext(Dispatchers.IO) {
        ensureOpen()
        val libc = LibcHolder.INSTANCE
        val pollFds = ByteArray(16)
        val pollBuf = ByteBuffer.wrap(pollFds).order(ByteOrder.nativeOrder())
        val eventBuf = ByteArray(GPIO_V2_LINE_EVENT_SIZE * 4)
        val headerBuf = ByteArray(3)

        while (!closed) {
            if (getIrqLevel() == 0) {
                val currentIrqFd = irqLineFd
                val currentWakeFd = wakePipeReadFd
                if (closed || currentIrqFd < 0 || currentWakeFd < 0) {
                    return@withContext null
                }
                pollBuf.putInt(0, currentIrqFd)
                pollBuf.putShort(4, POLLIN)
                pollBuf.putShort(6, 0)
                pollBuf.putInt(8, currentWakeFd)
                pollBuf.putShort(12, POLLIN)
                pollBuf.putShort(14, 0)

                val ready = try {
                    libc.poll(pollFds, NativeLong(2L), 250)
                } catch (e: LastErrorException) {
                    if (e.errorCode == EINTR) continue
                    if (closed) return@withContext null
                    throw IOException("poll() on NFC IRQ failed (errno=${e.errorCode})", e)
                }
                if (closed) return@withContext null
                if (ready > 0) {
                    val wakeRevents = pollBuf.getShort(14).toInt()
                    if ((wakeRevents and POLLIN.toInt()) != 0) {
                        return@withContext null
                    }
                    val irqRevents = pollBuf.getShort(6).toInt()
                    if ((irqRevents and POLLIN.toInt()) != 0) {
                        drainIrqEvents(libc, currentIrqFd, eventBuf)
                    }
                }
                if (getIrqLevel() == 0) {
                    continue
                }
            } else {
                // IRQ was already asserted high when entering readPacket(): drain any stale
                // rising-edge events from irqLineFd so a future poll() does not spin on them.
                drainIrqEvents(libc, irqLineFd, eventBuf)
            }

            // IRQ is asserted high: read 3-byte NCI header over I2C, then payloadLen bytes
            val packet = ioMutex.withLock {
                if (closed || getIrqLevel() == 0) {
                    null
                } else {
                    val currentI2cFd = i2cFd
                    if (currentI2cFd < 0) return@withContext null
                    val headerRead = try {
                        libc.read(currentI2cFd, headerBuf, NativeLong(3L)).toInt()
                    } catch (e: LastErrorException) {
                        if (e.errorCode == EINTR || e.errorCode == EAGAIN ||
                            e.errorCode == EIO || e.errorCode == EREMOTEIO || e.errorCode == ENXIO
                        ) {
                            -1
                        } else {
                            throw IOException("I2C read header failed (errno=${e.errorCode})", e)
                        }
                    }
                    if (headerRead != 3 || (headerBuf[0].toInt() and 0xFF) == 0xFF) {
                        null
                    } else {
                        val payloadLen = headerBuf[2].toInt() and 0xFF
                        val fullPacket = ByteArray(3 + payloadLen)
                        headerBuf.copyInto(fullPacket, 0, 0, 3)
                        if (payloadLen > 0) {
                            val payloadBuf = ByteArray(payloadLen)
                            val payloadRead = try {
                                libc.read(currentI2cFd, payloadBuf, NativeLong(payloadLen.toLong())).toInt()
                            } catch (e: LastErrorException) {
                                throw IOException("I2C read payload ($payloadLen bytes) failed (errno=${e.errorCode})", e)
                            }
                            if (payloadRead != payloadLen) {
                                throw IOException("Short I2C payload read: expected $payloadLen bytes, got $payloadRead")
                            }
                            payloadBuf.copyInto(fullPacket, destinationOffset = 3)
                        }
                        // Drain any rising-edge event that arrived before or during this read
                        drainIrqEvents(libc, irqLineFd, eventBuf)
                        fullPacket
                    }
                }
            }
            if (packet != null) {
                Logger.dHex(TAG, "NCI RX", packet)
                return@withContext packet
            }
        }
        null
    }

    override suspend fun writePacket(packet: ByteArray) {
        withContext(Dispatchers.IO) {
            ensureOpen()
        }
        Logger.dHex(TAG, "NCI TX", packet)
        var lastError: LastErrorException? = null
        for (attempt in 1..3) {
            val succeeded = ioMutex.withLock {
                withContext(Dispatchers.IO) {
                    val currentI2cFd = i2cFd
                    if (closed || currentI2cFd < 0) {
                        throw IOException("LinuxI2cNciPacketIo is closed")
                    }
                    try {
                        val written = LibcHolder.INSTANCE.write(
                            currentI2cFd,
                            packet,
                            NativeLong(packet.size.toLong())
                        ).toInt()
                        if (written != packet.size) {
                            throw IOException("Short I2C write: expected ${packet.size} bytes, wrote $written")
                        }
                        true
                    } catch (e: LastErrorException) {
                        if (attempt < 3 && (e.errorCode == EIO || e.errorCode == EREMOTEIO || e.errorCode == ENXIO)) {
                            lastError = e
                            false
                        } else {
                            throw IOException(
                                "I2C write failed on '$i2cDevicePath' addr 0x${i2cAddress.toString(16)} " +
                                    "(errno=${e.errorCode}: ${formatErrno(LibcHolder.INSTANCE, e.errorCode)})",
                                e
                            )
                        }
                    }
                }
            }
            if (succeeded) {
                return
            }
            // PN7160 may NACK the first I2C frame while waking its internal oscillator
            delay(2.milliseconds)
        }
        throw IOException("I2C write failed after 3 attempts", lastError)
    }

    override fun close() {
        synchronized(closeLock) {
            if (closed) return
            closed = true
            try {
                val libc = LibcHolder.INSTANCE
                if (wakePipeWriteFd >= 0) {
                    try {
                        libc.write(wakePipeWriteFd, byteArrayOf(1), NativeLong(1L))
                    } catch (_: Throwable) {
                    }
                }
                if (venLineFd >= 0) {
                    try {
                        setVenLevel(0)
                    } catch (_: Throwable) {
                    }
                }
                closeDescriptorsLocked(libc)
            } catch (_: Throwable) {
            }
        }
    }

    private fun closeDescriptorsLocked(libc: LinuxLibc) {
        if (i2cFd >= 0) {
            libc.close(i2cFd)
            i2cFd = -1
        }
        if (irqLineFd >= 0) {
            libc.close(irqLineFd)
            irqLineFd = -1
        }
        if (venLineFd >= 0) {
            libc.close(venLineFd)
            venLineFd = -1
        }
        if (wakePipeReadFd >= 0) {
            libc.close(wakePipeReadFd)
            wakePipeReadFd = -1
        }
        if (wakePipeWriteFd >= 0) {
            libc.close(wakePipeWriteFd)
            wakePipeWriteFd = -1
        }
    }

    private fun formatErrno(libc: LinuxLibc, errno: Int): String {
        return try {
            libc.strerror(errno) ?: "errno $errno"
        } catch (_: Throwable) {
            "errno $errno"
        }
    }
}
