/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multipaz.mdoc.credential

import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.multipaz.asn1.OID
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.claim.MdocClaim
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.cose.toCoseLabel
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.openid.claim.OpenID4VCIClaimDescription
import org.multipaz.openid.claim.findDisplayName
import org.multipaz.validation.ValidationResult
import org.multipaz.validation.buildValidationResult

/**
 * An ISO/IEC 18013-5 mdoc credential (either key-bound via [KeyBoundMdocCredential] or
 * non-key-bound via [KeylessMdocCredential]).
 *
 * An object that implements this interface must also be a [Credential].
 *
 * The [issuerProvidedData] for an [MdocCredential] must be the bytes of `IssuerSigned` according
 * to ISO/IEC 18013-5:
 * ```
 * IssuerSigned = {
 *   ? "nameSpaces" : IssuerNameSpaces,
 *   "issuerAuth" : IssuerAuth
 * }
 * ```
 */
interface MdocCredential {
    /**
     * The docType of the credential as defined in ISO/IEC 18013-5.
     */
    val docType: String

    /**
     * The issuer-provided data associated with the credential, see [Credential.issuerProvidedData].
     */
    val issuerProvidedData: ByteString

    /**
     * The `IssuerSigned` data according to ISO/IEC 18013-5.
     */
    val issuerSigned: DataItem

    /**
     * The `IssuerAuth` part of the issuer-provided data.
     *
     * This contains the signed Mobile Security Object as its payload.
     */
    val issuerAuth: CoseSign1

    /**
     * The issuer-signed data elements part of the issuer-provided data.
     */
    val issuerNamespaces: IssuerNamespaces

    /**
     * Convenience property for accessing the [MobileSecurityObject] from [issuerAuth].
     */
    val mso: MobileSecurityObject

    /**
     * Convenience property for accessing the X.509 certificate chain for the issuer signature from [issuerAuth].
     */
    val issuerCertChain: X509CertChain

    /**
     * Gets the claims in the credential, see [Credential.getClaims].
     *
     * @param documentTypeRepository a [DocumentTypeRepository] or `null`.
     * @param locales BCP 47 language tags, most preferred first, used to pick display names from
     *   [org.multipaz.document.Document.openID4VCIClaimDescriptions]. If empty, the current platform
     *   locale is used.
     * @return a list of [MdocClaim] values.
     */
    suspend fun getClaimsImpl(
        documentTypeRepository: DocumentTypeRepository?,
        locales: List<String> = emptyList()
    ): List<MdocClaim> = when (this) {
        is KeyBoundMdocCredential -> getClaims(documentTypeRepository, locales)
        is KeylessMdocCredential -> getClaims(documentTypeRepository, locales)
        else -> getCachedClaimsImpl(MdocCredentialCache(), documentTypeRepository, locales)
    }
}

/**
 * Internal cache for decoded `IssuerSigned`, MSO, and resolved [MdocClaim] lists on certified mdoc credentials.
 */
internal class MdocCredentialCache {
    data class ParsedIssuerData(
        val rawData: ByteString,
        val issuerSigned: DataItem,
        val issuerAuth: CoseSign1,
        val issuerNamespaces: IssuerNamespaces?,
        val mso: MobileSecurityObject,
        val issuerCertChain: X509CertChain,
    )

    data class ClaimsCacheKey(
        val rawData: ByteString,
        val documentType: DocumentType?,
        val locales: List<String>,
        val claimDescriptions: List<OpenID4VCIClaimDescription>,
    )

    @Volatile
    private var parsedData: ParsedIssuerData? = null

    @Volatile
    var cachedClaims: Pair<ClaimsCacheKey, List<MdocClaim>>? = null

    fun getParsed(rawData: ByteString): ParsedIssuerData {
        parsedData?.let { existing ->
            if (existing.rawData == rawData) {
                return existing
            }
        }
        val issuerSigned = Cbor.decode(rawData.toByteArray())
        val issuerAuth = issuerSigned["issuerAuth"].asCoseSign1
        val issuerNamespaces = if (issuerSigned.hasKey("nameSpaces")) {
            IssuerNamespaces.fromDataItem(issuerSigned["nameSpaces"])
        } else {
            null
        }
        val encodedMobileSecurityObject = Cbor.decode(issuerAuth.payload!!).asTagged.asBstr
        val mso = MobileSecurityObject.fromDataItem(Cbor.decode(encodedMobileSecurityObject))
        val issuerCertChain = (issuerAuth.protectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
            ?: issuerAuth.unprotectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel])!!.asX509CertChain
        return ParsedIssuerData(
            rawData = rawData,
            issuerSigned = issuerSigned,
            issuerAuth = issuerAuth,
            issuerNamespaces = issuerNamespaces,
            mso = mso,
            issuerCertChain = issuerCertChain,
        ).also {
            parsedData = it
        }
    }
}

internal suspend fun MdocCredential.getCachedClaimsImpl(
    cache: MdocCredentialCache,
    documentTypeRepository: DocumentTypeRepository?,
    locales: List<String> = emptyList(),
): List<MdocClaim> {
    val credential = this as Credential
    val dt = documentTypeRepository?.getDocumentTypeForMdoc(docType)
        ?: credential.document.getDocumentType(locales)
    val rawData = issuerProvidedData
    val parsed = cache.getParsed(rawData)
    val namespaces = parsed.issuerNamespaces ?: return emptyList()
    val claimDescriptions = credential.document.openID4VCIClaimDescriptions
    val cacheKey = MdocCredentialCache.ClaimsCacheKey(rawData, dt, locales, claimDescriptions)
    cache.cachedClaims?.let { (existingKey, existingClaims) ->
        if (existingKey == cacheKey) {
            return existingClaims
        }
    }
    val ret = mutableListOf<MdocClaim>()
    for ((namespaceName, innerMap) in namespaces.data) {
        for ((dataElementName, issuerSignedItem) in innerMap) {
            val mdocAttr = dt?.mdocDocumentType?.namespaces?.get(namespaceName)?.dataElements?.get(dataElementName)
            val claim = MdocClaim(
                // TODO: findDisplayName() searches claimDescriptions linearly for each claim, making this
                //   O(n^2) in the number of claims. Use a map keyed by path if this becomes a problem.
                displayName = mdocAttr?.attribute?.displayName
                    ?: claimDescriptions.findDisplayName(
                        path = buildJsonArray {
                            add(namespaceName)
                            add(dataElementName)
                        },
                        locales = locales
                    )
                    ?: dataElementName,
                attribute = mdocAttr?.attribute,
                docType = docType,
                namespaceName = namespaceName,
                dataElementName = dataElementName,
                value = issuerSignedItem.dataElementValue
            )
            ret.add(claim)
        }
    }
    val claims = ret.toList()
    if (credential.isCertified) {
        cache.cachedClaims = Pair(cacheKey, claims)
    }
    return claims
}

internal suspend fun MdocCredential.validateMdocImpl(
    issuerProvidedAuthenticationData: ByteString,
    now: Instant?,
): ValidationResult = buildValidationResult {
    val credential = this@validateMdocImpl as Credential
    val issuerSigned = try {
        Cbor.decode(issuerProvidedAuthenticationData.toByteArray())
    } catch (e: Throwable) {
        addError("issuerProvidedAuthenticationData is not valid CBOR: ${e.message}")
        return@buildValidationResult
    }

    if (issuerSigned !is CborMap) {
        addError("issuerProvidedAuthenticationData is not a CBOR map")
        return@buildValidationResult
    }

    if (!issuerSigned.hasKey("nameSpaces")) {
        addError("issuerProvidedAuthenticationData missing 'nameSpaces'")
    }
    if (!issuerSigned.hasKey("issuerAuth")) {
        addError("issuerProvidedAuthenticationData missing 'issuerAuth'")
    }
    val actualKeys = issuerSigned.asMap.keys.mapNotNull {
        try {
            it.asTstr
        } catch (_: Throwable) {
            null
        }
    }.toSet()
    val unexpectedKeys = actualKeys - setOf("nameSpaces", "issuerAuth")
    if (unexpectedKeys.isNotEmpty()) {
        addWarning("issuerProvidedAuthenticationData contains unexpected keys: ${unexpectedKeys.joinToString(", ")}")
    }

    var parsedIssuerNamespaces: IssuerNamespaces? = null
    if (issuerSigned.hasKey("nameSpaces")) {
        val nsDataItem = issuerSigned["nameSpaces"]
        addAll(IssuerNamespaces.validate(nsDataItem))
        try {
            parsedIssuerNamespaces = IssuerNamespaces.fromDataItem(nsDataItem)
            addAll(parsedIssuerNamespaces.validate())
        } catch (e: Throwable) {
            addError("Failed to parse IssuerNamespaces: ${e.message}")
        }
    }

    var parsedMso: MobileSecurityObject? = null
    if (issuerSigned.hasKey("issuerAuth")) {
        val authDataItem = issuerSigned["issuerAuth"]
        val coseSign1 = try {
            authDataItem.asCoseSign1
        } catch (e: Throwable) {
            addError("issuerAuth is not a valid COSE_Sign1: ${e.message}")
            null
        }
        if (coseSign1 != null) {
            val payload = coseSign1.payload
            if (payload == null) {
                addError("issuerAuth payload is null (detached payload is not permitted in IssuerSigned)")
            } else {
                val decodedPayload = try {
                    Cbor.decode(payload)
                } catch (e: Throwable) {
                    addError("Failed to decode issuerAuth payload CBOR: ${e.message}")
                    null
                }
                if (decodedPayload != null) {
                    if (decodedPayload !is Tagged || decodedPayload.tagNumber != Tagged.ENCODED_CBOR) {
                        addError("issuerAuth payload is not tagged with CBOR tag 24")
                    } else if (decodedPayload.taggedItem !is Bstr) {
                        addError("issuerAuth payload tag 24 item is not a byte string")
                    } else {
                        val msoDataItem = try {
                            Cbor.decode(decodedPayload.taggedItem.asBstr)
                        } catch (e: Throwable) {
                            addError("Failed to decode MobileSecurityObject CBOR from issuerAuth payload: ${e.message}")
                            null
                        }
                        if (msoDataItem != null) {
                            addAll(MobileSecurityObject.validate(msoDataItem, now))
                            try {
                                parsedMso = MobileSecurityObject.fromDataItem(msoDataItem)
                            } catch (e: Throwable) {
                                addError("Failed to parse MobileSecurityObject from issuerAuth payload: ${e.message}")
                            }
                        }
                    }
                }
            }

            val algLabel = CoseNumberLabel(Cose.COSE_LABEL_ALG)
            val algNumber = coseSign1.protectedHeaders[algLabel]?.let {
                try {
                    it.asNumber.toInt()
                } catch (_: Throwable) {
                    null
                }
            }
            if (algNumber == null) {
                addError("issuerAuth missing 'alg' in protected headers")
            }
            val signatureAlgorithm = algNumber?.let {
                try {
                    Algorithm.fromCoseAlgorithmIdentifier(it)
                } catch (_: Throwable) {
                    null
                }
            }
            if (algNumber != null && signatureAlgorithm == null) {
                addError("issuerAuth has unsupported algorithm identifier: $algNumber")
            }

            val x5ChainLabel = Cose.COSE_LABEL_X5CHAIN.toCoseLabel
            val x5ChainDataItem = coseSign1.protectedHeaders[x5ChainLabel]
                ?: coseSign1.unprotectedHeaders[x5ChainLabel]
            val certChain = if (x5ChainDataItem != null) {
                try {
                    x5ChainDataItem.asX509CertChain
                } catch (e: Throwable) {
                    addError("issuerAuth has invalid x5chain header: ${e.message}")
                    null
                }
            } else {
                addError("issuerAuth missing x5chain certificate chain")
                null
            }

            if (certChain != null && certChain.certificates.isNotEmpty()) {
                val dsCert = certChain.certificates[0]
                val x5tLabel = Cose.COSE_LABEL_X5T.toCoseLabel
                if (x5tLabel in coseSign1.protectedHeaders && x5tLabel in coseSign1.unprotectedHeaders) {
                    addError("issuerAuth has x5t in both protected and unprotected headers")
                } else {
                    (coseSign1.protectedHeaders[x5tLabel] ?: coseSign1.unprotectedHeaders[x5tLabel])?.let { x5tItem ->
                        try {
                            dsCert.verifyCoseX5t(x5tItem)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            addError("issuerAuth has invalid x5t header: ${e.message}")
                        }
                    }
                }

                try {
                    if (dsCert.keyUsage.isEmpty()) {
                        addWarning("DS certificate in x5chain does not have Key Usage extension")
                    } else {
                        if (!dsCert.keyUsage.contains(X509KeyUsage.DIGITAL_SIGNATURE)) {
                            addError("DS certificate in x5chain does not have DIGITAL_SIGNATURE key usage")
                        }
                        if (dsCert.keyUsage.contains(X509KeyUsage.KEY_CERT_SIGN)) {
                            addWarning("DS certificate in x5chain has KEY_CERT_SIGN key usage (DS certificate should not be a CA)")
                        }
                    }

                    if (dsCert.basicConstraints?.first == true) {
                        addWarning("DS certificate in x5chain has Basic Constraints CA=true (DS certificate should not be a CA)")
                    }

                    if (docType == "org.iso.18013.5.1.mDL") {
                        val ekuExt = dsCert.getExtensionValue(OID.X509_EXTENSION_EXTENDED_KEY_USAGE.oid)
                        if (ekuExt == null) {
                            addWarning("DS certificate in x5chain does not have Extended Key Usage extension (expected mDL DS OID 1.0.18013.5.1.2)")
                        }
                    }

                    if (parsedMso != null) {
                        if (parsedMso.signedAt < dsCert.validityNotBefore) {
                            addError("MSO signed timestamp (${parsedMso.signedAt}) is before DS certificate validity period (notBefore: ${dsCert.validityNotBefore})")
                        }
                        if (parsedMso.signedAt > dsCert.validityNotAfter) {
                            addError("MSO signed timestamp (${parsedMso.signedAt}) is after DS certificate validity period (notAfter: ${dsCert.validityNotAfter})")
                        }
                        if (parsedMso.validUntil > dsCert.validityNotAfter) {
                            addWarning("MSO validUntil (${parsedMso.validUntil}) is after DS certificate validity period (notAfter: ${dsCert.validityNotAfter})")
                        }
                    }

                    if (now != null) {
                        if (now < dsCert.validityNotBefore) {
                            addWarning("DS certificate is not yet valid (notBefore: ${dsCert.validityNotBefore}, current time: $now)")
                        }
                        if (now > dsCert.validityNotAfter) {
                            addWarning("DS certificate is expired (notAfter: ${dsCert.validityNotAfter}, current time: $now)")
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    addError("Failed to parse DS certificate: ${e.message}")
                }

                if (signatureAlgorithm != null) {
                    try {
                        Cose.coseSign1Check(
                            publicKey = dsCert.publicKey,
                            detachedData = null,
                            signature = coseSign1,
                            signatureAlgorithm = signatureAlgorithm
                        )
                    } catch (e: SignatureVerificationException) {
                        addError("Signature on MSO failed to verify: ${e.message}")
                    } catch (e: Throwable) {
                        addError("Error verifying signature on MSO: ${e.message}")
                    }
                }
            }
        }
    }

    if (parsedMso != null) {
        if (docType != parsedMso.docType) {
            addError("Credential docType '$docType' does not match MSO docType '${parsedMso.docType}'")
        }

        if (this@validateMdocImpl is KeyBoundMdocCredential) {
            val kbCred = this@validateMdocImpl
            if (parsedMso.deviceKey == null) {
                addError("Key-bound mdoc credential is missing 'deviceKeyInfo' in MSO")
            } else {
                val credKeyInfo = try {
                    kbCred.secureArea.getKeyInfo(kbCred.alias)
                } catch (e: Throwable) {
                    addError("Failed to get key info from SecureArea for alias '${kbCred.alias}': ${e.message}")
                    null
                }
                if (credKeyInfo != null && credKeyInfo.publicKey != parsedMso.deviceKey) {
                    addError("Credential authentication key in SecureArea does not match deviceKey in MSO (presentations will fail)")
                }
            }
        } else if (this@validateMdocImpl is KeylessMdocCredential) {
            if (parsedMso.deviceKey != null) {
                addError("Keyless mdoc credential contains unexpected 'deviceKeyInfo' in MSO (presentations without deviceSigned will fail)")
            }
        }

        if (credential.isCertified) {
            if (credential.validFrom != parsedMso.validFrom) {
                addError("Credential validFrom (${credential.validFrom}) does not match MSO validFrom (${parsedMso.validFrom})")
            }
            if (credential.validUntil != parsedMso.validUntil) {
                addError("Credential validUntil (${credential.validUntil}) does not match MSO validUntil (${parsedMso.validUntil})")
            }
        }

        if (parsedIssuerNamespaces != null) {
            addAll(parsedIssuerNamespaces.validateAgainstMso(parsedMso))
        }
    }
}

/**
 * Validates candidate issuer-provided static authentication data against this mdoc credential.
 *
 * @param issuerProvidedAuthenticationData candidate issuer-provided static authentication data.
 * @param now reference time for checking expiration and validity intervals, or `null` to skip.
 * @return a [ValidationResult] containing any errors or warnings.
 */
suspend fun MdocCredential.validate(
    issuerProvidedAuthenticationData: ByteString,
    now: Instant? = Clock.System.now()
): ValidationResult = (this as Credential).validate(issuerProvidedAuthenticationData, now)

/**
 * Validates this certified mdoc credential against its current issuer-provided data.
 *
 * @param now reference time for checking expiration and validity intervals, or `null` to skip.
 * @return a [ValidationResult] containing any errors or warnings.
 */
suspend fun MdocCredential.validate(
    now: Instant? = Clock.System.now()
): ValidationResult = (this as Credential).validate(now)
