# Multipaz Pass file format

The Multipaz `.mpzpass` file format provides a standardized, lightweight mechanism
for the exchange of low-assurance verifiable credentials.

In scenarios where strict cryptographic device-binding introduces unnecessary
friction — such as when a user expects their digital assets to seamlessly synchronize
across their entire ecosystem of devices — this format offers a pragmatic, portable
solution. It is engineered specifically for use cases where the risk of credential
sharing is negligible, such as event and movie ticketing, transit passes, or generic
membership cards.

## Security Boundary and Anti-Cloning

This format explicitly trades anti-cloning guarantees for portability. Because the
credential data and any associated keys are stored in a highly portable container,
the credential can be trivially copied.

For high-value credentials where cloning or replay attacks are active threat
vectors (e.g., mobile driving licenses or financial instruments), this file format
is inherently unsuitable. In those high-assurance scenarios, issuers must leverage
a robust provisioning protocol like [OpenID4VCI](https://github.com/openid/OpenID4VCI) to ensure secure delivery and
hardware-backed device-binding at the time of issuance.

## Pass Updates

A pass may support lifecycle updates (e.g., renewed validity periods, status
changes, or updated display metadata) by including an `updateUrl` field in
the pass payload.

Each pass defines:
* `uniqueId`: A globally unique identifier assigned by the issuer with at least
  128 bits of entropy.
* `version`: A monotonically increasing integer starting at `0`.

To check for an update, the wallet issues an HTTP `GET` request to `updateUrl`
with an `If-None-Match: "<version>"` header set to the currently installed pass
version.
* If no update is available, the server returns HTTP `304` (Not Modified).
* If an update is available, the server returns HTTP `200` (OK) with the
  response body containing the updated `.mpzpass` file. The new pass must
  contain the same `uniqueId` and a strictly higher `version` number.

Upon receiving an update, the wallet replaces the previous credentials, key
material, and display metadata with the contents of the updated pass.

## Reader Identifiers

To restrict presentation of a pass to authorized relying parties, issuers may
specify `readerIdentifiers`.

When `readerIdentifiers` is present, the wallet ensures the pass is only
disclosed to readers using reader authentication:
* During presentment (e.g., ISO/IEC 18013-5 or OpenID4VP), the reader provides
  its X.509 certificate chain.
* The wallet extracts the Authority Key Identifier (AKI) extension (OID
  `2.5.29.35`) from the certificates in the reader's certificate chain.
* The pass is only matched and presented if at least one AKI in the reader's
  certificate chain matches one of the byte strings in `readerIdentifiers`.

If `readerIdentifiers` is omitted or empty, the pass is accessible to any
requesting reader (subject to platform user authentication and holder consent).

## Pass Sharing

An issuer may indicate whether the pass can be shared or forwarded by the holder
to other users by specifying the `shareable` field.

If `shareable` is present and set to `true`, the pass is considered shareable and
a wallet application may provide a button or option to share the raw `.mpzpass`
file with others (e.g., via messaging apps, Quick Share, or email).

If `shareable` is absent or set to `false`, the pass is assumed to not be
shareable and wallets should not offer sharing functionality for the pass.

## Pass Signatures

To ensure the integrity of the pass container and provide cryptographic proof of
the issuer's identity, an `MpzPass` container may be digitally signed using
`COSE_Sign1` according to RFC 9052.

When a pass is signed:
* The compressed credential bytes (`CompressedCredentialDataBytes`) are signed
  using `COSE_Sign1` and encapsulated as a tagged CBOR item `#6.18(COSE_Sign1)`
  as the second element in the `MpzPass` array.
* The `COSE_Sign1` protected headers MUST contain the signature algorithm
  (`alg`, label 1).
* The certificate containing the public key belonging to the private key used to
  sign the pass shall be included as an `x5chain` element (label 33) as described
  in RFC 9360. It shall be included as a protected header element. The `x5chain`
  element shall include at least one certificate and may contain more.
* The `COSE_Sign1` unprotected headers MUST be empty.
* The payload of `COSE_Sign1` is `CompressedCredentialDataBytes`.

A receiving wallet verifies the signature against the leaf certificate in the
certificate chain and can validate the certificate chain against a trust store
or present the verified issuer identity to the user during import.

## Data format

The data is encoded in [CBOR](https://datatracker.ietf.org/doc/html/rfc8949) conforming to the following [CDDL](https://datatracker.ietf.org/doc/html/rfc8610):

```cddl
; Top-level container.
;
MpzPass = [
  "MpzPass",
  CompressedCredentialDataBytes / SignedCompressedCredentialDataBytes,
]

; Contains CredentialDataBytes compressed using DEFLATE algorithm according
; to [RFC 1951](https://www.ietf.org/rfc/rfc1951.txt).
;
CompressedCredentialDataBytes = bstr

; Contains CompressedCredentialDataBytes signed using COSE_Sign1 according to RFC 9052.
; The payload of COSE_Sign1 is CompressedCredentialDataBytes.
;
SignedCompressedCredentialDataBytes = #6.18(COSE_Sign1)

CredentialDataBytes = bstr .cbor CredentialData

; Credential data.
;
; Each pass has an unique identifier assigned by the issuer which can be used by a wallet
; to check if it has already imported the pass. This identifier also serves as a authentication
; secret which can be used to check for updates. A pass also has a version field which is a
; monotonically increasing number starting at 0 and represents the version of the pass.
;
; The issuer may also include a URL in `updateUrl` for the wallet to check for updates. The
; wallet can issue an HTTP GET request to `updateUrl` with an `If-None-Match: "<version>"`
; header set to the current version. If no update is available, the server responds with
; HTTP status code 304 (Not Modified). If an update is available, the server responds with
; HTTP status code 200 (OK) containing the bytes of the updated pass in the response body.
;
; The issuer may also indicate that user authentication using the platform (e.g. passcode
; or biometrics) must be performed in order to present the pass by setting
; `userAuthenticationRequired` to `true`.
;
; Presentation of a pass can be restricted to a subset of known readers by setting
; `readerIdentifiers`.
;
; The issuer may also indicate whether the pass is shareable by setting `shareable`
; to `true`.
;
CredentialData = {
  "uniqueId": tstr,          ; Unique identifier for the pass, containing only alphanumerical
                             ; and underscore and hyphen characters and contains at least 128
                             ; bit of entropy.
  "version": uint,           ; Version of the pass, monotonically increasing starting at 0.
  ? "updateUrl": tstr,       ; If set, an URL where the wallet can download updates.
  ? "userAuthenticationRequired": bool,  ; If set and true, user authentication using the platform
                                         ; (e.g. passcode or biometrics) must be performed in
                                         ; order to present the pass.
  ? "readerIdentifiers": ReaderIdentifiers, ; If set, restrict access to certain readers.
  ? "shareable": bool,       ; If set and true, the pass may be shared with others.
  "display": Display,
  "credential": Credential,
}

; The pass is only accessible to readers using reader authentication and where a certificate
; in the x5chain for the request contains an AuthorityKeyIdentifier in this list.
;
ReaderIdentifiers = [+ bstr ]

; Display data.
;
Display = {
  ? "name": tstr,         ; Display name, e.g. "Erika's Driving License"
  ? "typeName" : tstr     ; Credential type, e.g. "Utopia Driving License"
  ? "cardArt" bstr,       ; PNG or JPEG with aspect ratio of 1.586 (cf. ID-1 from ISO/IEC 7810)
}

; The data for the credential.
;
; At least one of the credential formats must be present. If both credential formats
; are present they must include identical data.
;
; To protect the holder's privacy and prevent RP collusion, multiple credentials may be
; included for a single format, allowing the wallet to rotate between credentials and/or
; implement policy decisions such as single-use.
;
; When multiple credentials are included, they must contain identical data except for:
; * Key material
; * Minor variances in validity periods
; * Imperceptible noise in image assets (e.g., portrait photos)
; This ensures Relying Parties (RPs) cannot correlate credentials originating from the same batch.
;
Credential = {
  ? "isoMdoc": [+ IsoMdocCredential],
  ? "sdJwtVc": [+ SdJwtVcCredential],
}

SdJwtVcCredential = {
  ; The verifiable credential type.
  ;
  vct: tstr,
  
  ; The private key for the key-binding JWT, if used.
  ;
  ? "deviceKeyPrivate": COSE_Key,

  ; The compact serialization of the SD-JWT VC, according to RFC 9901.
  ;
  "compactSerialization": tstr,
}

IsoMdocCredential = {
  ; The document type.
  ;
  "docType": tstr,

  ; The private key corresponding to DeviceKey in `issuerSigned`.
  ;
  "deviceKeyPrivate": COSE_Key,

  ; IssuerSigned according to ISO/IEC 18013-5:2021 clause 8.3.2.1.2.2
  ;
  "issuerSigned": IssuerSigned,
}
```

## MIME Type and file extension

The MIME type `application/vnd.multipaz.mpzpass` shall be used for data containing credentials
encoded in this format and the file extension `.mpzpass` shall be used for files containing
credentials encoded in this format.

## Examples files

- [Driving license ISO mdoc](https://apps.multipaz.org/mpzpass/mDL.mpzpass)
- [EU PID SD-JWT VC](https://apps.multipaz.org/mpzpass/EuPidSdJwt.mpzpass)
- [Utopia Movie ticket SD-JWT VC w/o key-binding key](https://apps.multipaz.org/mpzpass/MovieTicketSdJwtKeyless.mpzpass)

