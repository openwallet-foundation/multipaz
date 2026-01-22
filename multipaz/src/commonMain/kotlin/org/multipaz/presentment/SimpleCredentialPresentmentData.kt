package org.multipaz.presentment

import org.multipaz.claim.Claim
import org.multipaz.credential.Credential
import org.multipaz.document.Document
import org.multipaz.request.RequestedClaim


/**
 * An implementation of [CredentialPresentmentSet] for simple request.
 *
 * @param credentials a list of credentials and associated claims.
 * @return a [CredentialPresentmentSet] with a single option containing a single member with
 *   the matches contained in [credentials]
 */
class SimpleCredentialPresentmentData(
    private val credentials: List<Pair<Credential, Map<RequestedClaim, Claim>>>
) : CredentialPresentmentData() {
    override val credentialSets: List<CredentialPresentmentSet>
        get() = listOf(object: CredentialPresentmentSet {
            override val optional: Boolean
                get() = false
            override val options: List<CredentialPresentmentSetOption>
                get() = listOf(object: CredentialPresentmentSetOption {
                    override val members: List<CredentialPresentmentSetOptionMember>
                        get() = listOf(object: CredentialPresentmentSetOptionMember {
                            override val matches: List<CredentialPresentmentSetOptionMemberMatch>
                                get() = credentials.map {
                                    object: CredentialPresentmentSetOptionMemberMatch {
                                        override val credential: Credential
                                            get() = it.first
                                        override val claims: Map<RequestedClaim, Claim>
                                            get() = it.second
                                    }
                                }
                        })
                })
        })

    override fun consolidate(): CredentialPresentmentData = this

    override fun select(preselectedDocuments: List<Document>): CredentialPresentmentSelection {
        return CredentialPresentmentSelection(matches = credentials.map {
            object: CredentialPresentmentSetOptionMemberMatch {
                override val credential: Credential
                    get() = it.first
                override val claims: Map<RequestedClaim, Claim>
                    get() = it.second
            }
        })
    }
}
