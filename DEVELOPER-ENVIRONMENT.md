# Developer Environment

We use Android Studio for development which can be downloaded from
https://developer.android.com/studio.

## JDK 21

Building Multipaz requires **JDK 21** (configured via `jvmToolchain(21)` in Gradle).

- **JVM Targets (`jvmMain`, server modules, and `multipazctl`)** compile for **JVM 21**. This is required by server-side dependencies such as `com.android.keyattestation:keyattestation`, which is published as Java 21 bytecode (`org.gradle.jvm.version=21`).
- **Android Targets (`androidTarget` and `android { compileOptions }`)** explicitly target **Java 17** (`JvmTarget.JVM_17` / `JavaVersion.VERSION_17`) so that published Multipaz Android AARs remain drop-in compatible with consuming Android applications that still target Java 17.
- In Android Studio, ensure **Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK** is set to a JDK 21 distribution (such as the bundled JetBrains Runtime 21).

# Mac OS

The preferred developer environment is Mac OS since this also allows building the iOS variant
of our Kotlin Multiplatform codebase. You will need a recent version of Xcode installed along
with Xcode command-line tools. See https://developer.apple.com/xcode/resources/ for more
information.

## Xcode

Xcode is used mainly just to launch and debug Testapp, all development usually happens
in Android Studio. If you're just making a library change in common code it's likely
enough to just rely on unit tests or the Android version of TestApp for testing. In other
words there is rarely a need to use Xcode at all.

Download Xcode (version 26.x or later) and set it as the default:

```shell
sudo xcode-select -s /Applications/Xcode.app   # set default xcode

xcode-select -p   # check whether set succesfully or not

/Applications/Xcode.app/Contents/Developer   # expected to return
```

Note that while Xcode 26.x can be used to build the project, **Xcode 27.3 or later** is required
if you need OpenID4VP Digital Credentials support on iOS. The codebase currently uses temporary
`#if compiler(>=6.4)` compile-time checks around the iOS 27 SDK APIs to maintain compatibility
with Xcode 26.x; these checks will be removed once Multipaz has a hard dependency on Xcode 27.3
or later.

To build TestApp in Xcode you need local Apple signing settings in
`samples/testapp/iosApp/DeveloperConfig.xcconfig`. This file is intentionally
gitignored because each developer uses their own Apple Developer Team, bundle ID,
and App Group. The project includes a template at
`samples/testapp/iosApp/DeveloperConfig.xcconfig.template`.

```shell
cp samples/testapp/iosApp/DeveloperConfig.xcconfig.template samples/testapp/iosApp/DeveloperConfig.xcconfig && \
$EDITOR samples/testapp/iosApp/DeveloperConfig.xcconfig
```

In the opened editor, set:

```xcconfig
DEVELOPMENT_TEAM = YOUR_APPLE_TEAM_ID
LOCAL_BUNDLE_ID = your.registered.testapp.bundle.id
APP_GROUP_ID = group.your.registered.testapp.app.group
SWIFT_TESTAPP_BUNDLE_ID = your.registered.swift.testapp.bundle.id
SWIFT_TESTAPP_APP_GROUP_ID = group.your.registered.swift.testapp.app.group
```

`LOCAL_BUNDLE_ID` is used as the TestApp bundle identifier. The
DocumentProviderExtension target appends `.DocumentProviderExtension` to the same
value, so register both bundle identifiers in your Apple Developer account. Both
targets use `APP_GROUP_ID` for their App Groups entitlement and for the shared
iOS storage container, so the App Group must be enabled for both App IDs.

For example, if `LOCAL_BUNDLE_ID` is `org.example.testapp`, the extension bundle
identifier is `org.example.testapp.DocumentProviderExtension`.

`SWIFT_TESTAPP_BUNDLE_ID` and `SWIFT_TESTAPP_APP_GROUP_ID` do the same for
`samples/SwiftTestApp/SwiftTestApp.xcodeproj`. The Swift extension target
appends `.IdentityDocumentProviderExtension`, so register both SwiftTestApp
bundle identifiers and enable the Swift App Group for both of them.

Then open `samples/testapp/iosApp/TestApp.xcodeproj` in Xcode, select the
TestApp scheme and a target device, and run the app.

# Linux and Windows

We also support building on Linux and Windows. In this setup, iOS libraries are not built
but non-iOS artifacts for other platforms will work fine.
