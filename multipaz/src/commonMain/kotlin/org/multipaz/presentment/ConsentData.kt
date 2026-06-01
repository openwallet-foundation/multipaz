package org.multipaz.presentment

import org.multipaz.crypto.X509CertChain
import org.multipaz.request.Requester
import org.multipaz.util.generateAllPaths

private const val TAG = "ConsentData"

/**
 * Data to show in a consent prompt.
 *
 * This contains data from a [CredentialQueryResult] organized in a way so it's easy
 * to display in a user interface.
 *
 * @property useCases the use-cases in the transaction, contains at least one.
 * @property credentialQueryResult the [CredentialQueryResult] the [ConsentData] was created from.
 */
@ConsistentCopyVisibility
data class ConsentData private constructor(
    val useCases: List<ConsentUseCase>,
    val credentialQueryResult: CredentialQueryResult
) {

    /**
     * Calculates a [CredentialSelection] from a list of selections.
     *
     * @param selections the solution selected for each use-case or -1 if not selecting an optional use-case.
     * @return a [CredentialSelection].
     */
    fun toCredentialSelection(
        selections: List<Int>
    ): CredentialSelection {
        require(selections.size == useCases.size) {
            "Expected selectionPerUseCase length to be that of useCases"
        }
        val matches = mutableListOf<CredentialPresentmentSetOptionMemberMatch>()
        useCases.forEachIndexed { index, useCase ->
            val selection = selections[index]
            if (selection < 0) {
                require(useCase.optional) { "Cannot un-select non-optional use-case at index $index" }
            } else {
                require(selection < useCase.solutions.size) { "Cannot select out-of-bound use-case at index $index" }
                val solution = useCase.solutions[selection]
                solution.credentials.forEach { credential ->
                    // TODO: In the future, if encryption is requested, we may need to pass that
                    // information along in the selection or a separate structure.
                    matches.add(credential.match)
                }
            }
        }
        return CredentialSelection(matches)
    }

    companion object {
        /**
         * Creates a [ConsentData] from a [CredentialQueryResult].
         *
         * The given [PresentmentSource] is used to look up [TrustMetaData] for keys used
         * to encrypt data to.
         *
         * @param credentialQueryResult the [CredentialQueryResult] to use.
         * @param source a [PresentmentSource].
         */
        suspend fun fromCredentialQueryResult(
            credentialQueryResult: CredentialQueryResult,
            source: PresentmentSource,
        ): ConsentData {
            return ConsentData(
                useCases = credentialQueryResult.credentialSets.map { credentialSet ->
                    val solutions = mutableListOf<ConsentUseCaseSolution>()
                    credentialSet.options.forEach { option ->
                        val paths = option.members.map { it.matches.size }.generateAllPaths()
                        paths.forEach { path ->
                            val credentials = mutableListOf<ConsentCredential>()
                            path.forEachIndexed { index, n ->
                                val match = option.members[index].matches[n]
                                when (match.source) {
                                    is CredentialMatchSourceIso18013 -> {
                                        val docRequestInfo = match.source.docRequest.docRequestInfo
                                        val encryptionTargetCertificationChain =
                                            docRequestInfo?.docResponseEncryption?.recipientCertificates?.let {
                                                if (it.isNotEmpty()) {
                                                    X509CertChain(it)
                                                } else {
                                                    null
                                                }
                                            }
                                        val encryptionTargetTrustMetadata = encryptionTargetCertificationChain?.let {
                                            source.resolveTrust(Requester(certChain = it))
                                        }
                                        credentials.add(
                                            ConsentCredential(
                                                match = match,
                                                encryptionRequested = docRequestInfo?.docResponseEncryption != null,
                                                encryptionTargetCertificationChain = encryptionTargetCertificationChain,
                                                encryptionTargetTrustMetadata = encryptionTargetTrustMetadata
                                            )
                                        )
                                    }
                                    is CredentialMatchSourceOpenID4VP -> {
                                        // Note: OpenID4VP does not support encrypting separate credentials in the vpToken.
                                        credentials.add(
                                            ConsentCredential(
                                                match = match,
                                                encryptionRequested = false,
                                                encryptionTargetCertificationChain = null,
                                                encryptionTargetTrustMetadata = null
                                            )
                                        )
                                    }
                                }
                            }
                            solutions.add(ConsentUseCaseSolution(credentials))
                        }
                    }
                    ConsentUseCase(
                        optional = credentialSet.optional,
                        solutions = solutions
                    )
                },
                credentialQueryResult = credentialQueryResult
            )
        }
    }
}