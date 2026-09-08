package org.multipaz.presentment

import org.multipaz.claim.JsonClaim
import org.multipaz.claim.MdocClaim

internal class PrettyPrinter() {
    private val sb = StringBuilder()
    private var indent = 0

    fun append(line: String) {
        for (n in IntRange(1, indent)) {
            sb.append(" ")
        }
        sb.append(line)
        sb.append("\n")
    }

    fun pushIndent() {
        indent += 2
    }

    fun popIndent() {
        indent -= 2
        check(indent >= 0)
    }

    override fun toString(): String = sb.toString()
}

internal fun CredentialPresentmentSetOptionMemberMatch.print(pp: PrettyPrinter) {
    pp.append("match:")
    pp.pushIndent()
    pp.append("credential:")
    pp.pushIndent()
    pp.append("type: ${credential.credentialType}")
    pp.append("docId: ${credential.document.displayName}")
    pp.append("claims:")
    pp.pushIndent()
    for ((_, claim) in claims) {
        pp.append("claim:")
        pp.pushIndent()
        when (claim) {
            is JsonClaim -> {
                pp.append("path: ${claim.claimPath}")
            }
            is MdocClaim -> {
                pp.append("nameSpace: ${claim.namespaceName}")
                pp.append("dataElement: ${claim.dataElementName}")
            }
        }
        pp.append("displayName: ${claim.displayName}")
        pp.append("value: ${claim.render()}")
        pp.popIndent()
    }
    pp.popIndent()
    pp.popIndent()
    pp.popIndent()
}

internal fun CredentialPresentmentSetOptionMember.print(pp: PrettyPrinter) {
    pp.append("member:")
    pp.pushIndent()
    pp.append("matches:")
    pp.pushIndent()
    if (matches.isEmpty()) {
        pp.append("<empty>")
    } else {
        for (match in matches) {
            match.print(pp)
        }
    }
    pp.popIndent()
    pp.popIndent()
}

internal fun CredentialPresentmentSetOption.print(pp: PrettyPrinter) {
    pp.append("option:")
    pp.pushIndent()
    pp.append("members:")
    pp.pushIndent()
    if (members.isEmpty()) {
        pp.append("<empty>")
    } else {
        for (member in members) {
            member.print(pp)
        }
    }
    pp.popIndent()
    pp.popIndent()
}

internal fun CredentialPresentmentSet.print(pp: PrettyPrinter) {
    pp.append("credentialSet:")
    pp.pushIndent()
    pp.append("optional: $optional")
    pp.append("options:")
    pp.pushIndent()
    if (options.isEmpty()) {
        pp.append("<empty>")
    } else {
        for (option in options) {
            option.print(pp)
        }
    }
    pp.popIndent()
    pp.popIndent()
}

internal fun CredentialQueryResult.prettyPrint(): String {
    val pp = PrettyPrinter()
    pp.append("credentialSets:")
    pp.pushIndent()
    if (credentialSets.isEmpty()) {
        pp.append("<empty>")
    } else {
        for (credentialSet in credentialSets) {
            credentialSet.print(pp)
        }
    }
    pp.popIndent()
    return pp.toString()
}

internal fun CredentialSelection.print(pp: PrettyPrinter): String {
    pp.append("matches:")
    pp.pushIndent()
    if (matches.isEmpty()) {
        pp.append("<empty>")
    } else {
        for (match in matches) {
            match.print(pp)
        }
    }
    pp.popIndent()
    return pp.toString()
}
internal fun CredentialSelection.prettyPrint(): String {
    val pp = PrettyPrinter()
    return print(pp)
}

internal fun List<CredentialSelection>.prettyPrint(): String {
    val pp = PrettyPrinter()
    pp.append("selections:")
    pp.pushIndent()
    if (this.isEmpty()) {
        pp.append("<empty>")
    } else {
        for (element in this) {
            element.print(pp)
        }
    }
    pp.popIndent()
    return pp.toString()
}

internal fun ConsentCredential.print(pp: PrettyPrinter) {
    pp.append("credential:")
    pp.pushIndent()
    pp.append("encryptionRequested: $encryptionRequested")
    pp.append("encryptionTargetTrustMetadata:")
    pp.pushIndent()
    pp.append("displayName: ${encryptionTargetTrustMetadata?.displayName}")
    pp.popIndent()
    match.print(pp)
    pp.popIndent()
}

internal fun ConsentUseCaseSolution.print(pp: PrettyPrinter) {
    pp.append("solution:")
    pp.pushIndent()
    if (credentials.isEmpty()) {
        pp.append("<empty>")
    } else {
        for (credential in credentials) {
            credential.print(pp)
        }
    }
    pp.popIndent()
}

internal fun ConsentUseCase.print(pp: PrettyPrinter) {
    pp.append("useCase:")
    pp.pushIndent()
    pp.append("optional: $optional")
    pp.pushIndent()
    if (solutions.isEmpty()) {
        pp.append("<empty>")
    } else {
        for (solution in solutions) {
            solution.print(pp)
        }
    }
    pp.popIndent()
    pp.popIndent()
}

internal fun ConsentData.prettyPrint(): String {
    val pp = PrettyPrinter()
    pp.append("useCases:")
    pp.pushIndent()
    if (useCases.isEmpty()) {
        pp.append("<empty>")
    } else {
        for (useCase in useCases) {
            useCase.print(pp)
        }
    }
    pp.popIndent()
    return pp.toString()
}