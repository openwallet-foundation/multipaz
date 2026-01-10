package org.multipaz.openid.dcql

import org.multipaz.claim.JsonClaim
import org.multipaz.claim.MdocClaim
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim

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

internal fun DcqlQuery.print(): String {
    val pp = PrettyPrinter()
    print(pp)
    return pp.toString()
}

internal fun DcqlQuery.print(pp: PrettyPrinter) {
    pp.append("credentials:")
    pp.pushIndent()
    credentialQueries.forEach {
        pp.append("credential:")
        pp.pushIndent()
        it.print(pp)
        pp.popIndent()
    }
    pp.popIndent()

    pp.append("credentialSets:")
    pp.pushIndent()
    if (credentialSetQueries.isNotEmpty()) {
        credentialSetQueries.forEach {
            pp.append("credentialSet:")
            pp.pushIndent()
            it.print(pp)
            pp.popIndent()
        }
    } else {
        pp.append("<empty>")
    }
    pp.popIndent()
}

internal fun DcqlCredentialSetQuery.print(pp: PrettyPrinter) {
    pp.append("required: $required")
    pp.append("options:")
    pp.pushIndent()
    for (option in options) {
        option.print(pp)
    }
    pp.popIndent()
}

internal fun DcqlCredentialSetOption.print(pp: PrettyPrinter) {
    pp.append("$credentialIds")
}

internal fun DcqlCredentialQuery.print(pp: PrettyPrinter) {
    pp.append("id: $id")
    pp.append("format: $format")
    if (mdocDocType != null) {
        pp.append("mdocDocType: $mdocDocType")
    }
    if (vctValues != null) {
        pp.append("vctValues: $vctValues")
    }
    pp.append("claims:")
    pp.pushIndent()
    claims.forEach {
        pp.append("claim:")
        pp.pushIndent()
        it.print(pp)
        pp.popIndent()
    }
    pp.popIndent()
    pp.append("claimSets:")
    pp.pushIndent()
    if (claimSets.isNotEmpty()) {
        claimSets.forEach {
            pp.append("claimset:")
            pp.pushIndent()
            it.print(pp)
            pp.popIndent()
        }
    } else {
        pp.append("<empty>")
    }
    pp.popIndent()
}

internal fun DcqlClaimSet.print(pp: PrettyPrinter) {
    pp.append("ids: $claimIdentifiers")
}

internal fun RequestedClaim.print(pp: PrettyPrinter) {
    if (id != null) {
        pp.append("id: $id")
    }
    when (this) {
        is JsonRequestedClaim -> {
            pp.append("path: $claimPath")
        }
        is MdocRequestedClaim -> {
            pp.append("path: [\"$namespaceName\",\"$dataElementName\"]")
            if (intentToRetain) {
                pp.append("mdocIntentToRetain: $intentToRetain")
            }
        }
    }
    if (values != null) {
        pp.append("values: $values")
    }
}

internal fun DcqlResponseCredentialSetOptionMember.print(pp: PrettyPrinter) {
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

internal fun DcqlResponseCredentialSetOptionMemberMatch.print(pp: PrettyPrinter) {
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

internal fun DcqlResponseCredentialSetOption.print(pp: PrettyPrinter) {
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

internal fun DcqlResponseCredentialSet.print(pp: PrettyPrinter) {
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

internal fun DcqlResponse.prettyPrint(): String {
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