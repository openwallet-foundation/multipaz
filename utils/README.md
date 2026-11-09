# Developer Utilities

This directory contains helper scripts and utilities for developing, testing, and running Multipaz components across platforms.

## `launch-samples-testapp-with-hci`

[`launch-samples-testapp-with-hci`](launch-samples-testapp-with-hci) builds and runs the Compose Multiplatform desktop test application (`samples/testapp`) on Linux with direct Bluetooth Low Energy (BLE) hardware access via `HCI_CHANNEL_USER`.

### Background

On Linux, Multipaz's JVM BLE transport (`LinuxHciSocketPacketIo`) bypasses the BlueZ `bluetoothd` daemon and binds directly to a kernel HCI controller (`hci0`, `hci1`, etc.) using an exclusive `AF_BLUETOOTH` / `BTPROTO_HCI` socket with `HCI_CHANNEL_USER`. Bringing the controller down (`ioctl(HCIDEVDOWN)`) and binding `HCI_CHANNEL_USER` requires the Linux `CAP_NET_ADMIN` capability.

Granting `CAP_NET_ADMIN` to a Gradle-launched desktop JVM application has two common pitfalls:
1. **Running `sudo ./gradlew :samples:testapp:run`** starts a `root`-owned Gradle daemon, creates `root`-owned build artifacts in `build/` and `.gradle/`, and loses the user's X11/Wayland/XDG desktop session environment variables.
2. **Running `setcap cap_net_admin+ep /path/to/java`** causes the Linux kernel to mark the `java` binary `AT_SECURE=1`, which makes `glibc`'s dynamic linker (`ld-linux.so`) strip `$ORIGIN` from `RPATH` (breaking `libjli.so` loading) and grants `CAP_NET_ADMIN` to every Java process on the machine.

### How It Works

`launch-samples-testapp-with-hci` avoids both issues:
1. **Builds unprivileged:** Runs `./gradlew :samples:testapp:prepareJvmRun -Pdisable.web.targets=true` as your normal user to compile the app and write the toolchain `java` binary path and classpath `@argfile` under `samples/testapp/build/jvm-run/`.
2. **Drops back to `$USER` with ambient `CAP_NET_ADMIN`:** Uses `sudo` and `/usr/sbin/capsh` (`--keep=1 --noenv --user="$USER" --caps="cap_net_admin+eip" --addamb="cap_net_admin"`) while preserving desktop display and XDG environment variables, sets `MULTIPAZ_BLE_HCI_DEV` to the chosen controller index, and `exec`s the JVM as your normal user.

### Usage

```bash
# Show help and list detected HCI controllers under /sys/class/bluetooth
./utils/launch-samples-testapp-with-hci --help

# Build and launch samples/testapp using hci0
./utils/launch-samples-testapp-with-hci hci0

# Launch with debug logging forced on via MULTIPAZ_DEBUG
MULTIPAZ_DEBUG=1 ./utils/launch-samples-testapp-with-hci hci0

# Launch using hci1 without re-running Gradle
./utils/launch-samples-testapp-with-hci --no-build hci1
```
