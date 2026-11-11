package org.multipaz.bthci

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import kotlinx.coroutines.CancellationException
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
 * Linux `AF_BLUETOOTH` / `BTPROTO_HCI` / `HCI_CHANNEL_USER` implementation of [HciPacketIo].
 *
 * Binds exclusively to a Linux Bluetooth controller (`hciN`) using `HCI_CHANNEL_USER`, bypassing
 * the BlueZ `bluetoothd` user-space daemon while reusing the Linux kernel's hardware transport
 * drivers (`btusb` for USB dongles, `hci_uart` / `btbcm` for Raspberry Pi UART controllers, etc.).
 * Requires `net.java.dev.jna:jna` on the runtime classpath.
 *
 * ### Permissions & Operating Modes
 *
 * Because Linux Bluetooth controllers (`hci0`, `hci1`, ...) are kernel network-subsystem objects
 * rather than `/dev/` device nodes, the kernel gates `ioctl(HCIDEVDOWN)` and
 * `bind(..., HCI_CHANNEL_USER)` on `CAP_NET_ADMIN`. Once bound, however, `read()` and `write()` on
 * the `HCI_CHANNEL_USER` file descriptor do **not** require `CAP_NET_ADMIN` or `CAP_NET_RAW`.
 *
 * [LinuxHciSocketPacketIo] therefore supports two operating modes:
 *
 * #### Mode 1: Direct Bind (Process has `CAP_NET_ADMIN` or `uid 0`)
 * When [preOpenedHciFd] is `null` (the default unless `org.multipaz.ble.hciFd` or
 * `MULTIPAZ_BLE_HCI_FD` is set), [open] brings down `hci<N>` via `ioctl(HCIDEVDOWN)` and binds a
 * fresh `HCI_CHANNEL_USER` socket for each session, closing the socket in [close].
 *
 * The controller index `<N>` defaults to `0` (`hci0`) and can be configured via:
 * - JVM system property: `-Dorg.multipaz.ble.hciDeviceIndex=1` (or `hci1`)
 * - Environment variable: `MULTIPAZ_BLE_HCI_DEV=1` (or `hci1`)
 *
 * To run without `root` under `systemd`, grant ambient `CAP_NET_ADMIN`:
 * ```ini
 * [Service]
 * User=multipaz
 * AmbientCapabilities=CAP_NET_ADMIN
 * CapabilityBoundingSet=CAP_NET_ADMIN
 * ```
 *
 * #### Mode 2: Pre-Opened File Descriptor (Unprivileged JVM via Privileged Launcher)
 * When [preOpenedHciFd] is provided (or configured via `-Dorg.multipaz.ble.hciFd=<fd>` or
 * `MULTIPAZ_BLE_HCI_FD=<fd>`), [open] adopts that already-bound `HCI_CHANNEL_USER` file descriptor
 * instead of calling `socket()`, `ioctl(HCIDEVDOWN)`, or `bind()`.
 *
 * To allow the unprivileged JVM process to run multiple BLE transactions over its lifetime without
 * needing `CAP_NET_ADMIN` to re-bind the controller:
 * - [open] drains any stale HCI events left in the kernel socket receive buffer from a prior session
 *   before `BleHciEngine.initializeController()` issues `HCI_Reset`.
 * - [close] wakes the `poll()` loop and writes a best-effort `HCI_Reset` (`01 03 0c 00`) to stop
 *   advertising, scanning, and active connections, **without** calling `close(fd)` on
 *   [preOpenedHciFd] so subsequent BLE sessions can reuse the descriptor.
 *
 * Example minimal C launcher (install with `sudo setcap cap_net_admin=ep hci-launch`):
 * ```c
 * #include <errno.h>
 * #include <stdio.h>
 * #include <stdlib.h>
 * #include <sys/ioctl.h>
 * #include <sys/socket.h>
 * #include <unistd.h>
 *
 * #define AF_BLUETOOTH 31
 * #define BTPROTO_HCI 1
 * #define HCI_CHANNEL_USER 1
 * #define HCIDEVDOWN 0x400448caUL
 *
 * struct sockaddr_hci {
 *     unsigned short hci_family;
 *     unsigned short hci_dev;
 *     unsigned short hci_channel;
 * };
 *
 * int main(int argc, char **argv) {
 *     if (argc < 3) {
 *         fprintf(stderr, "Usage: %s <hci-index> <command> [args...]\n", argv[0]);
 *         return 1;
 *     }
 *     int dev = atoi(argv[1]);
 *     int ctl = socket(AF_BLUETOOTH, SOCK_RAW, BTPROTO_HCI);
 *     if (ctl >= 0) { ioctl(ctl, HCIDEVDOWN, dev); close(ctl); }
 *
 *     // Open without SOCK_CLOEXEC so the fd is inherited across execvp()
 *     int fd = socket(AF_BLUETOOTH, SOCK_RAW, BTPROTO_HCI);
 *     struct sockaddr_hci addr = { AF_BLUETOOTH, (unsigned short)dev, HCI_CHANNEL_USER };
 *     if (fd < 0 || bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
 *         perror("bind(HCI_CHANNEL_USER)");
 *         return 1;
 *     }
 *     char fd_str[16];
 *     snprintf(fd_str, sizeof(fd_str), "%d", fd);
 *     setenv("MULTIPAZ_BLE_HCI_FD", fd_str, 1);
 *     execvp(argv[2], &argv[2]);
 *     perror("execvp");
 *     return 1;
 * }
 * ```
 */
class LinuxHciSocketPacketIo(
    private val hciDeviceIndex: Int = defaultHciDeviceIndex(),
    private val preOpenedHciFd: Int? = defaultPreOpenedHciFd()
) : HciPacketIo {
    companion object {
        private const val TAG = "LinuxHciSocketPacketIo"

        private const val AF_UNIX = 1
        private const val AF_BLUETOOTH = 31
        private const val SOCK_RAW = 3
        private const val SOCK_SEQPACKET = 5
        private const val SOCK_CLOEXEC = 0x80000
        private const val BTPROTO_HCI = 1

        private const val HCI_CHANNEL_USER: Short = 1

        // _IOW('H', 202, int) on Linux
        private const val HCIDEVDOWN = 0x400448caL

        private const val F_GETFD = 1
        private const val F_SETFD = 2
        private const val F_GETFL = 3
        private const val FD_CLOEXEC = 1

        private const val POLLIN: Short = 0x0001
        private const val POLLERR: Short = 0x0008
        private const val POLLHUP: Short = 0x0010
        private const val POLLNVAL: Short = 0x0020

        private const val EINTR = 4
        private const val EAGAIN = 11
        private const val EBUSY = 16
        private const val ENOSYS = 38
        private const val EALREADY = 114

        private const val MAX_HCI_PACKET_SIZE = 65536 + 16

        // H4 HCI_Reset command packet: [0x01 (Command), 0x03, 0x0C (opcode 0x0C03), 0x00 (paramLen=0)]
        private val HCI_RESET_PACKET = byteArrayOf(0x01, 0x03, 0x0C, 0x00)

        private val activePreOpenedFdsLock = Any()
        private val activePreOpenedFds = mutableSetOf<Int>()

        internal fun defaultHciDeviceIndex(): Int {
            System.getProperty("org.multipaz.ble.hciDeviceIndex")?.trim()?.let { prop ->
                prop.removePrefix("hci").toIntOrNull()?.let { return it }
            }
            System.getenv("MULTIPAZ_BLE_HCI_DEV")?.trim()?.let { env ->
                env.removePrefix("hci").toIntOrNull()?.let { return it }
            }
            return 0
        }

        internal fun defaultPreOpenedHciFd(): Int? {
            System.getProperty("org.multipaz.ble.hciFd")?.trim()?.toIntOrNull()?.let { return it }
            System.getenv("MULTIPAZ_BLE_HCI_FD")?.trim()?.toIntOrNull()?.let { return it }
            return null
        }

        internal fun ensureJnaAvailable() {
            try {
                Class.forName("com.sun.jna.Native")
            } catch (e: ClassNotFoundException) {
                throw IllegalStateException(
                    "JNA (net.java.dev.jna:jna) must be on the runtime classpath to use BLE on Linux JVM",
                    e
                )
            }
        }

        internal fun createSocketPairForTesting(): Pair<Int, Int> {
            val sv = IntArray(2)
            LibcHolder.INSTANCE.socketpair(AF_UNIX, SOCK_SEQPACKET or SOCK_CLOEXEC, 0, sv)
            return Pair(sv[0], sv[1])
        }

        internal fun closeFdForTesting(fd: Int) {
            LibcHolder.INSTANCE.close(fd)
        }
    }

    private interface LinuxLibc : Library {
        @Throws(LastErrorException::class)
        fun socket(domain: Int, type: Int, protocol: Int): Int

        @Throws(LastErrorException::class)
        fun socketpair(domain: Int, type: Int, protocol: Int, sv: IntArray): Int

        @Throws(LastErrorException::class)
        fun bind(sockfd: Int, addr: ByteArray, addrlen: Int): Int

        @Throws(LastErrorException::class)
        fun ioctl(fd: Int, request: NativeLong, arg: NativeLong): Int

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
    private var userFd: Int = -1

    @Volatile
    private var wakePipeReadFd: Int = -1

    @Volatile
    private var wakePipeWriteFd: Int = -1

    @Volatile
    private var opened: Boolean = false

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var controllerResetCompleted: Boolean = false

    private var activeIoCount: Int = 0
    private var closeCompleted: Boolean = false
    private var ownsPreOpenedFdClaim: Boolean = false

    private val readMutex = Mutex()
    private val readScratchBuf = ByteArray(MAX_HCI_PACKET_SIZE)
    private val writeMutex = Mutex()
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val closeLock = java.lang.Object()

    override val keepsDescriptorOpenOnClose: Boolean
        get() = preOpenedHciFd != null

    override fun markControllerResetCompleted() {
        controllerResetCompleted = true
    }

    private fun bringControllerDown(libc: LinuxLibc) {
        val ctlFd = try {
            libc.socket(AF_BLUETOOTH, SOCK_RAW or SOCK_CLOEXEC, BTPROTO_HCI)
        } catch (e: LastErrorException) {
            throw IOException(
                "Failed to open AF_BLUETOOTH raw socket (errno=${e.errorCode}: ${formatErrno(libc, e.errorCode)}). " +
                    "Ensure Bluetooth kernel modules are loaded and CAP_NET_RAW/CAP_NET_ADMIN are granted.",
                e
            )
        }
        try {
            libc.ioctl(ctlFd, NativeLong(HCIDEVDOWN), NativeLong(hciDeviceIndex.toLong()))
        } catch (e: LastErrorException) {
            if (e.errorCode != EALREADY) {
                Logger.w(
                    TAG,
                    "ioctl(HCIDEVDOWN, hci$hciDeviceIndex) returned errno=${e.errorCode} " +
                        "(${formatErrno(libc, e.errorCode)}); proceeding to bind HCI_CHANNEL_USER"
                )
            }
        } finally {
            libc.close(ctlFd)
        }
    }

    /**
     * Opens the Linux `HCI_CHANNEL_USER` socket (or adopts `preOpenedHciFd` if configured) and
     * prepares the non-blocking poll/wake-pipe loop.
     *
     * @throws IOException if opening or binding the HCI socket fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun open() = withContext(Dispatchers.IO) {
        ensureJnaAvailable()
        val osName = System.getProperty("os.name") ?: ""
        if (!osName.contains("Linux", ignoreCase = true)) {
            throw IllegalStateException(
                "Direct HCI BLE transport requires Linux (current OS: $osName)"
            )
        }
        synchronized(closeLock) {
            if (closed) {
                throw IOException("LinuxHciSocketPacketIo is already closed")
            }
            check(!opened) { "LinuxHciSocketPacketIo.open() has already been called" }
            opened = true
        }

        val libc = LibcHolder.INSTANCE
        val pipeFds = intArrayOf(-1, -1)
        try {
            try {
                libc.pipe2(pipeFds, SOCK_CLOEXEC)
            } catch (e: LastErrorException) {
                if (e.errorCode == ENOSYS) {
                    libc.pipe(pipeFds)
                    for (fd in pipeFds) {
                        val fdFlags = libc.fcntl(fd, F_GETFD, 0)
                        libc.fcntl(fd, F_SETFD, fdFlags or FD_CLOEXEC)
                    }
                } else {
                    throw e
                }
            }
        } catch (e: LastErrorException) {
            if (pipeFds[0] >= 0) libc.close(pipeFds[0])
            if (pipeFds[1] >= 0) libc.close(pipeFds[1])
            throw IOException("pipe2() failed: ${formatErrno(libc, e.errorCode)}", e)
        }
        synchronized(closeLock) {
            if (closed) {
                libc.close(pipeFds[0])
                libc.close(pipeFds[1])
                throw IOException("LinuxHciSocketPacketIo is already closed")
            }
            wakePipeReadFd = pipeFds[0]
            wakePipeWriteFd = pipeFds[1]
        }

        try {
            val inheritedFd = preOpenedHciFd
            if (inheritedFd != null) {
                if (inheritedFd < 0) {
                    throw IOException("Invalid pre-opened HCI file descriptor: $inheritedFd")
                }
                val claimedPreOpened = synchronized(activePreOpenedFdsLock) {
                    activePreOpenedFds.add(inheritedFd)
                }
                if (!claimedPreOpened) {
                    throw IOException(
                        "Pre-opened HCI file descriptor $inheritedFd is already in use by another active BLE session"
                    )
                }
                val claimedBeforeClose = synchronized(closeLock) {
                    if (closed) {
                        false
                    } else {
                        ownsPreOpenedFdClaim = true
                        true
                    }
                }
                if (!claimedBeforeClose) {
                    synchronized(activePreOpenedFdsLock) {
                        activePreOpenedFds.remove(inheritedFd)
                    }
                    throw IOException("LinuxHciSocketPacketIo was closed during open()")
                }
                try {
                    libc.fcntl(inheritedFd, F_GETFL, 0)
                } catch (e: LastErrorException) {
                    throw IOException(
                        "Pre-opened HCI file descriptor $inheritedFd is not valid " +
                            "(errno=${e.errorCode}: ${formatErrno(libc, e.errorCode)}). " +
                            "Ensure it was opened without SOCK_CLOEXEC before exec.",
                        e
                    )
                }
                drainPendingInput(libc, inheritedFd)
                synchronized(closeLock) {
                    if (closed) {
                        throw IOException("LinuxHciSocketPacketIo was closed during open()")
                    }
                    userFd = inheritedFd
                }
                Logger.i(TAG, "Adopted pre-opened HCI_CHANNEL_USER socket (fd=$inheritedFd)")
                return@withContext
            }

            // Bring down hciN first so bluetoothd detaches and releases the controller for HCI_CHANNEL_USER.
            bringControllerDown(libc)

            // Bind exclusive HCI_CHANNEL_USER socket on hciN. Retry briefly if bluetoothd is still releasing it.
            val addrBytes = packSockaddrHci(
                family = AF_BLUETOOTH.toShort(),
                dev = hciDeviceIndex.toShort(),
                channel = HCI_CHANNEL_USER
            )
            var boundFd = -1
            var lastError: LastErrorException? = null
            for (attempt in 1..10) {
                val fd = libc.socket(AF_BLUETOOTH, SOCK_RAW or SOCK_CLOEXEC, BTPROTO_HCI)
                try {
                    libc.bind(fd, addrBytes, addrBytes.size)
                    boundFd = fd
                    break
                } catch (e: LastErrorException) {
                    libc.close(fd)
                    lastError = e
                    if (e.errorCode == EBUSY && attempt < 10) {
                        delay(50.milliseconds)
                        bringControllerDown(libc)
                    } else {
                        break
                    }
                }
            }
            if (boundFd < 0) {
                val err = lastError!!
                throw IOException(
                    "Failed to bind HCI_CHANNEL_USER on hci$hciDeviceIndex " +
                        "(errno=${err.errorCode}: ${formatErrno(libc, err.errorCode)}). " +
                        "Ensure CAP_NET_ADMIN is granted and hci$hciDeviceIndex exists.",
                    err
                )
            }
            synchronized(closeLock) {
                if (closed) {
                    libc.close(boundFd)
                    throw IOException("LinuxHciSocketPacketIo was closed during open()")
                }
                userFd = boundFd
            }
            Logger.i(TAG, "Opened HCI_CHANNEL_USER socket on hci$hciDeviceIndex (fd=$boundFd)")
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun drainPendingInput(libc: LinuxLibc, fd: Int) {
        val pollFds = ByteArray(8)
        val pollBuf = ByteBuffer.wrap(pollFds).order(ByteOrder.nativeOrder())
        val discardBuf = ByteArray(MAX_HCI_PACKET_SIZE)
        while (true) {
            pollBuf.clear()
            pollBuf.putInt(fd)
            pollBuf.putShort(POLLIN)
            pollBuf.putShort(0)
            val ready = try {
                libc.poll(pollFds, NativeLong(1), 0)
            } catch (e: LastErrorException) {
                if (e.errorCode == EINTR) continue else break
            }
            if (ready <= 0) break
            val revents = pollBuf.getShort(6).toInt()
            if ((revents and POLLIN.toInt()) == 0) break
            try {
                val n = libc.read(fd, discardBuf, NativeLong(discardBuf.size.toLong())).toInt()
                if (n <= 0) break
            } catch (e: LastErrorException) {
                if (e.errorCode == EINTR) continue else break
            }
        }
    }

    private fun awaitHciResetCommandComplete(libc: LinuxLibc, fd: Int, maxWaitMs: Int = 100) {
        val pollFds = ByteArray(8)
        val pollBuf = ByteBuffer.wrap(pollFds).order(ByteOrder.nativeOrder())
        val buf = ByteArray(512)
        val deadlineNanos = System.nanoTime() + maxWaitMs * 1_000_000L
        while (true) {
            val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).toInt()
            if (remainingMs <= 0) break
            pollBuf.clear()
            pollBuf.putInt(fd)
            pollBuf.putShort(POLLIN)
            pollBuf.putShort(0)
            val ready = try {
                libc.poll(pollFds, NativeLong(1), remainingMs)
            } catch (e: LastErrorException) {
                if (e.errorCode == EINTR) continue else break
            }
            if (ready <= 0) break
            val revents = pollBuf.getShort(6).toInt()
            if ((revents and POLLIN.toInt()) == 0) break
            val n = try {
                libc.read(fd, buf, NativeLong(buf.size.toLong())).toInt()
            } catch (e: LastErrorException) {
                if (e.errorCode == EINTR) continue else break
            }
            if (n <= 0) break
            // H4 Event (0x04), Command Complete (0x0E), paramLen >= 3, numCmdPkts, opcode = 0x0C03 (03 0C)
            if (n >= 6 &&
                buf[0] == 0x04.toByte() &&
                buf[1] == 0x0E.toByte() &&
                buf[4] == 0x03.toByte() &&
                buf[5] == 0x0C.toByte()
            ) {
                break
            }
        }
    }

    @Throws(IOException::class, CancellationException::class)
    override suspend fun readPacket(): ByteArray? = readMutex.withLock {
        withContext(Dispatchers.IO) {
            val libc = LibcHolder.INSTANCE
            val pollFds = ByteArray(16)
            val pollBuf = ByteBuffer.wrap(pollFds).order(ByteOrder.nativeOrder())

            val (fd, wakeFd) = synchronized(closeLock) {
                if (closed || userFd < 0 || wakePipeReadFd < 0) {
                    return@withContext null
                }
                activeIoCount++
                Pair(userFd, wakePipeReadFd)
            }

            try {
                while (!closed) {
                    pollBuf.clear()
                    pollBuf.putInt(fd)
                    pollBuf.putShort(POLLIN)
                    pollBuf.putShort(0)
                    pollBuf.putInt(wakeFd)
                    pollBuf.putShort(POLLIN)
                    pollBuf.putShort(0)

                    try {
                        libc.poll(pollFds, NativeLong(2), -1)
                    } catch (e: LastErrorException) {
                        if (e.errorCode == EINTR) {
                            continue
                        }
                        if (closed) {
                            return@withContext null
                        }
                        throw IOException("poll() failed on hci$hciDeviceIndex: ${formatErrno(libc, e.errorCode)}", e)
                    }

                    if (closed) {
                        return@withContext null
                    }

                    val userRevents = pollBuf.getShort(6).toInt()
                    val wakeRevents = pollBuf.getShort(14).toInt()
                    if ((wakeRevents and POLLIN.toInt()) != 0) {
                        return@withContext null
                    }
                    if ((userRevents and (POLLERR.toInt() or POLLHUP.toInt() or POLLNVAL.toInt())) != 0) {
                        if (closed) return@withContext null
                        throw IOException(
                            "HCI_CHANNEL_USER socket on hci$hciDeviceIndex reported error/hangup " +
                                "(revents=0x${userRevents.toString(16)})"
                        )
                    }
                    if ((userRevents and POLLIN.toInt()) != 0) {
                        val bytesRead = try {
                            libc.read(fd, readScratchBuf, NativeLong(readScratchBuf.size.toLong())).toInt()
                        } catch (e: LastErrorException) {
                            if (e.errorCode == EINTR || e.errorCode == EAGAIN) {
                                continue
                            }
                            if (closed) return@withContext null
                            throw IOException(
                                "read() failed on hci$hciDeviceIndex: ${formatErrno(libc, e.errorCode)}",
                                e
                            )
                        }
                        if (bytesRead <= 0) {
                            return@withContext null
                        }
                        return@withContext readScratchBuf.copyOfRange(0, bytesRead)
                    }
                }
                null
            } finally {
                releaseActiveIo()
            }
        }
    }

    @Throws(IOException::class, CancellationException::class)
    override suspend fun writePacket(packet: ByteArray) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val fd = synchronized(closeLock) {
                    if (closed) {
                        throw IOException("HCI socket on hci$hciDeviceIndex is closed")
                    }
                    if (userFd < 0) {
                        throw IOException("HCI socket on hci$hciDeviceIndex is not open")
                    }
                    activeIoCount++
                    userFd
                }
                try {
                    val libc = LibcHolder.INSTANCE
                    while (true) {
                        try {
                            val written = libc.write(fd, packet, NativeLong(packet.size.toLong())).toInt()
                            if (written != packet.size) {
                                throw IOException(
                                    "Short write on hci$hciDeviceIndex: wrote $written of ${packet.size} bytes"
                                )
                            }
                            break
                        } catch (e: LastErrorException) {
                            if (e.errorCode == EINTR) {
                                continue
                            }
                            throw IOException(
                                "write() failed on hci$hciDeviceIndex: ${formatErrno(libc, e.errorCode)}",
                                e
                            )
                        }
                    }
                } finally {
                    releaseActiveIo()
                }
            }
        }
    }

    private data class ClaimedFds(
        val userFd: Int,
        val wakeReadFd: Int,
        val wakeWriteFd: Int,
        val releasePreOpenedFd: Int?
    )

    private fun claimFdsLocked(): ClaimedFds {
        val releasePreOpened = if (ownsPreOpenedFdClaim) preOpenedHciFd else null
        ownsPreOpenedFdClaim = false
        val claimed = ClaimedFds(
            userFd = userFd,
            wakeReadFd = wakePipeReadFd,
            wakeWriteFd = wakePipeWriteFd,
            releasePreOpenedFd = releasePreOpened
        )
        userFd = -1
        wakePipeReadFd = -1
        wakePipeWriteFd = -1
        return claimed
    }

    private fun finalizeCloseFds(claimed: ClaimedFds) {
        try {
            val hasOpenFds = claimed.userFd >= 0 || claimed.wakeReadFd >= 0 || claimed.wakeWriteFd >= 0
            if (!hasOpenFds) return
            val libc = try {
                LibcHolder.INSTANCE
            } catch (_: UnsatisfiedLinkError) {
                null
            } catch (_: Exception) {
                null
            } ?: return

            if (claimed.userFd >= 0) {
                if (preOpenedHciFd != null) {
                    // Leave pre-opened fd open so subsequent BLE sessions can reuse it without CAP_NET_ADMIN.
                    // If BleHciEngine did not already complete an orderly HCI_Reset prior to close(),
                    // send a best-effort HCI_Reset now and wait briefly for Command_Complete(0x0C03).
                    if (!controllerResetCompleted) {
                        try {
                            libc.write(
                                claimed.userFd,
                                HCI_RESET_PACKET,
                                NativeLong(HCI_RESET_PACKET.size.toLong())
                            )
                            awaitHciResetCommandComplete(libc, claimed.userFd)
                        } catch (_: LastErrorException) {
                        } catch (_: Exception) {
                        }
                    }
                } else {
                    libc.close(claimed.userFd)
                }
            }
            if (claimed.wakeReadFd >= 0) {
                libc.close(claimed.wakeReadFd)
            }
            if (claimed.wakeWriteFd >= 0) {
                libc.close(claimed.wakeWriteFd)
            }
        } finally {
            claimed.releasePreOpenedFd?.let { fd ->
                synchronized(activePreOpenedFdsLock) {
                    activePreOpenedFds.remove(fd)
                }
            }
        }
    }

    private fun releaseActiveIo() {
        synchronized(closeLock) {
            activeIoCount--
            if (activeIoCount == 0) {
                closeLock.notifyAll()
            }
        }
    }

    override fun close() {
        var fdsToCloseNow: ClaimedFds? = null
        var interrupted = false
        synchronized(closeLock) {
            if (closed) {
                while (!closeCompleted) {
                    try {
                        closeLock.wait()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt()
                }
                return
            }
            closed = true
            val wakeWrite = wakePipeWriteFd
            if (wakeWrite >= 0) {
                try {
                    LibcHolder.INSTANCE.write(wakeWrite, byteArrayOf(1), NativeLong(1))
                } catch (_: LastErrorException) {
                } catch (_: UnsatisfiedLinkError) {
                } catch (_: Exception) {
                }
            }
            while (activeIoCount > 0) {
                try {
                    closeLock.wait()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            fdsToCloseNow = claimFdsLocked()
        }
        try {
            fdsToCloseNow?.let { finalizeCloseFds(it) }
        } finally {
            synchronized(closeLock) {
                closeCompleted = true
                closeLock.notifyAll()
            }
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun packSockaddrHci(family: Short, dev: Short, channel: Short): ByteArray {
        val bytes = ByteArray(6)
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).apply {
            putShort(family)
            putShort(dev)
            putShort(channel)
        }
        return bytes
    }

    private fun formatErrno(libc: LinuxLibc, errno: Int): String {
        return try {
            libc.strerror(errno) ?: "errno $errno"
        } catch (_: Exception) {
            "errno $errno"
        }
    }
}
