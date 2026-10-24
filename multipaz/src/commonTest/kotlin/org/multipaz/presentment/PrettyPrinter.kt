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

internal fun PresentmentCredentialMatch.print(pp: PrettyPrinter) {
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

internal fun PresentmentSelection.print(pp: PrettyPrinter): String {
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
internal fun PresentmentSelection.prettyPrint(): String {
    val pp = PrettyPrinter()
    return print(pp)
}

internal fun List<PresentmentSelection>.prettyPrint(): String {
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

internal fun PresentmentCredentialMatch.printPresentmentCredential(pp: PrettyPrinter) {
    pp.append("credential:")
    pp.pushIndent()
    pp.append("encryptionRequested: $encryptionRequested")
    pp.append("encryptionTargetTrustMetadata:")
    pp.pushIndent()
    pp.append("displayName: ${encryptionTargetTrustMetadata?.displayName}")
    pp.popIndent()
    print(pp)
    pp.popIndent()
}

internal fun PresentmentUseCaseSolution.print(pp: PrettyPrinter) {
    pp.append("solution:")
    pp.pushIndent()
    if (matches.isEmpty()) {
        pp.append("<empty>")
    } else {
        for (match in matches) {
            match.printPresentmentCredential(pp)
        }
    }
    pp.popIndent()
}

internal fun PresentmentUseCase.print(pp: PrettyPrinter) {
    pp.append("useCase:")
    pp.pushIndent()
    pp.append("optional: $optional")
    if (purposeHints.isNotEmpty()) {
        pp.append("purposeHints:")
        pp.pushIndent()
        for (purposeHint in purposeHints) {
            when (purposeHint) {
                is PresentmentPurposeHintIso18013 -> {
                    pp.append("iso18013: ${purposeHint.controllerId} -> ${purposeHint.purposeHintCode}")
                }
            }
        }
        pp.popIndent()
    }
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

internal fun PresentmentData.prettyPrint(): String {
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
