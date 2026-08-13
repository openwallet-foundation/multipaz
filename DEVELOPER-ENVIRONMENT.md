# Developer Environment

We use Android Studio for development which can be downloaded from
https://developer.android.com/studio.

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

Download Xcode and set Xcode 26.1.1 as the default

```shell
sudo xcode-select -s /Applications/Xcode.app   # set default xcode

xcode-select -p   # check whether set succesfully or not

/Applications/Xcode.app   # expected to return 
```

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
