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
    for ((requestedClaim, claim) in claims) {
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
    if (matches.size == 0) {
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
    if (members.size == 0) {
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
    if (options.size == 0) {
        pp.append("<empty>")
    } else {
        for (option in options) {
            option.print(pp)
        }
    }
    pp.popIndent()
    pp.popIndent()
}

internal fun CredentialPresentmentData.prettyPrint(): String {
    val pp = PrettyPrinter()
    pp.append("credentialSets:")
    pp.pushIndent()
    if (credentialSets.size == 0) {
        pp.append("<empty>")
    } else {
        for (credentialSet in credentialSets) {
            credentialSet.print(pp)
        }
    }
    pp.popIndent()
    return pp.toString()
}

internal fun CredentialPresentmentSelection.print(pp: PrettyPrinter): String {
    pp.append("matches:")
    pp.pushIndent()
    if (matches.size == 0) {
        pp.append("<empty>")
    } else {
        for (match in matches) {
            match.print(pp)
        }
    }
    pp.popIndent()
    return pp.toString()
}
internal fun CredentialPresentmentSelection.prettyPrint(): String {
    val pp = PrettyPrinter()
    return print(pp)
}

internal fun List<CredentialPresentmentSelection>.prettyPrint(): String {
    val pp = PrettyPrinter()
    pp.append("selections:")
    pp.pushIndent()
    if (this.size == 0) {
        pp.append("<empty>")
    } else {
        for (element in this) {
            element.print(pp)
        }
    }
    pp.popIndent()
    return pp.toString()
}
