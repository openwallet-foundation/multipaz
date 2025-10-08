# Multipaz

This repository contains libraries and applications for working with real-world
identity. The initial focus for this work was mdoc/mDL according to [ISO/IEC 18013-5:2021](https://www.iso.org/standard/69084.html)
and related standards but the current scope also include other credential formats and
presentment protocols.

## Multipaz Libraries

The project provides libraries written in [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html):

- `multipaz` provides the core building blocks it works on Android,
  iOS, and in server-side environments. The library includes support 
  for ISO mdoc and IETF SD-JWT VC credential formats and also implements
  proximity presentment using ISO/IEC 18013-5:2021 (for ISO mdoc credentials)
  and presentment to applications using the W3C Digital Credentials API
  according to ISO/IEC 18013-7:2025 and OpenID4VP 1.0.
- `multipaz-compose` provides rich UI elements to be used in Compose
  applications.
- `multipaz-doctypes` contains known credential document types (for example
  ISO/IEC 18013-5:2021 mDL and EU PID) along with human-readable descriptions
  of claims / data elements, sample data, and sample requests. This is
  packaged separately from the core `multipaz` library because its size is
  non-negligible and not all applications need this or they may bring their
  own.
- `multipaz-longfellow` bundles the [Google Longfellow-ZK](https://github.com/google/longfellow-zk) library
  and integrates with the core `multipaz` for Zero-Knowledge Proofs
  according to latest available [ISO/IEC 18013-5 Second Edition draft](https://github.com/ISOWG10/ISO-18013).
- `multipaz-swift` contains Swift and [SwiftUI](https://developer.apple.com/swiftui/)
  functionality to easier use Multipaz in Swift applications on iOS.

## Command-line tool

A command-line tool `multipazctl` is also included which can be used to generate
ISO/IEC 18013-5:2021 IACA certificates among other things. Use
`./gradlew --quiet runMultipazCtl --args "help"` for documentation on supported
verbs and options. To set up a wrapper, first build the fat jar

```shell
$ ./gradlew multipazctl:buildFatJar
```

then create a wrapper like this
```shell
#!/bin/sh
MAIN_CLASS="org.multipaz.multipazctl.MultipazCtl"
CLASSPATH="/Users/davidz/StudioProjects/identity-credential/multipazctl/build/libs/multipazctl-all.jar"
JVM_OPTS="-Xms256m -Xmx512m"
exec java $JVM_OPTS -cp "$CLASSPATH" "$MAIN_CLASS" "$@"
```

in e.g. `~/bin/multipazctl` adjusting paths as needed. With this you can now
invoke `multipazctl` like any other system tool.

## Library releases, Versioning, and Stability

Libraries are released on [GMaven](https://maven.google.com/) with a two-month cadence
and [Semantic Versioning](https://en.wikipedia.org/wiki/Software_versioning#Semantic_versioning)
is used. At this time we're in pre-1.0 territory but we expect to hit 1.0 around
early 2026.

We are also making Multipaz available as a [Swift package](https://github.com/openwallet-foundation/multipaz/blob/main/Package.swift)
which includes an the `multipaz`, `multipaz-doctypes`, `multipaz-doctypes`,
`multipaz-longfellow`, and `multipaz-swift` libraries. This is built using
[SKIE](https://skie.touchlab.co/). Be careful relying on this as Swift/Kotlin
interop technology might change in the near future.

At this point both API interfaces and data stored on disk is subject to change
but we expect to provide stability guarantees post 1.0. We only expect minor changes
for example conversion from `ByteArray` to `ByteString` and similar things.

## Getting involved

We have resources for people already involved and people wishing to contribute
to the Multipaz project
- [CONTRIBUTING.md](CONTRIBUTING.md) for how to get involved with the project and send PRs.
- [CODE-OF-CONDUCT.md](CODE-OF-CONDUCT.md) for the policies and rules around collaboration.
- [CODING-STYLE.md](CODING-STYLE.md) for guidelines on writing code to be included in the project.
- [TESTING.md](TESTING.md) explains our approach to unit and manual testing.
- [DEVELOPER-ENVIRONMENT.md](DEVELOPER-ENVIRONMENT.md) for how to set up your system for building Multipaz.

Note: If you're just looking to use the Multipaz libraries you do not need to build
the entire Multipaz project from source. Instead, just use our released libraries,
see the next section for an example of this.

## Examples / Samples

For a fully-fledged mDL wallet, our current answer is to use the `samples/testapp`
module which works on both Android and iOS. This application is intended for
developers and as such has a lot of options and settings. It's intended to
exercise all code in the libraries. Prebuilt APKs are available from
https://apps.multipaz.org.

For a fully-featured proximity reader app using Multipaz, see
[MpzIdentityReader](https://github.com/openwallet-foundation/multipaz-identity-reader/).
Prebuilt APKs are available from https://apps.multipaz.org.

For an over-the-Internet verifier supporting OpenID4VP (both W3C DC API and
URI schemes) and ISO/IEC 18013-7 Annex C see https://verifier.multipaz.org.

To see how to use the Multipaz in a 3rd party project, see
https://github.com/openwallet-foundation/multipaz-samples/ which includes
a number of samples for different platforms.

## Developer Resources

[developer.multipaz.org](developer.multipaz.org) is a comprehensive resource for developers. **The entire developer website is open source** and contributions are welcome!

### Documentation

- [Getting Started](https://developer.multipaz.org/docs/getting-started): Provides foundational knowledge for new users.
- [API Reference](https://developer.multipaz.org/kdocs/index.html): Comprehensive documentation for every API endpoint, providing thorough understanding of the available functionalities.
- [Codelabs](https://developer.multipaz.org/codelabs): Guided tutorials on key identity credential concepts, along with sample apps that provide tangible examples to accelerate development.
- [Showcase Apps](https://developer.multipaz.org/showcase): Real-world applications built leveraging the Multipaz framework.

### Applications

- [Android Apps](https://apps.multipaz.org/)
- [Web Verifier App](https://verifier.multipaz.org/)
- [Issuer Portal](https://issuer.multipaz.org/)
- [Identity Reader Backend](http://identityreader.multipaz.org/)

### Community & Contribution

- [Contributing Guide](https://developer.multipaz.org/contributing/contributing)
- [Website Contribution Guide](https://github.com/openwallet-foundation/multipaz-developer-website)
- [Blog](https://developer.multipaz.org/blog)
- [Discord](https://discord.com/invite/openwalletfoundation)

## Note

This is not an official or supported Google product.

