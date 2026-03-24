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

## Data format

The data is encoded in [CBOR](https://datatracker.ietf.org/doc/html/rfc8949) conforming to the following [CDDL](https://datatracker.ietf.org/doc/html/rfc8610):

```cddl
; Top-level container.
;
MpzPass = [
  "MpzPass",
  CompressedCredentialDataBytes,
]

; Contains CredentialDataBytes compressed using DEFLATE algorithm according
; to [RFC 1951](https://www.ietf.org/rfc/rfc1951.txt).
;
CompressedCredentialDataBytes = bstr

CredentialDataBytes = bstr .cbor CredentialData

CredentialData = {
  "display": Display,
  "credential": Credential,
}

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
; implement policy decisions such as single-use. If multiple credentials are included,
; they must all include the same data except for key material and slight variances in
; the validity period necessary to avoid RPs being able to correlate credentials from the
; same batch.
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

