package org.multipaz.asn1

import kotlinx.io.bytestring.ByteStringBuilder

class ASN1Boolean internal constructor(
    val value: Boolean,
    /**
     * The content octet this value was decoded from, or the canonical octet when the value was
     * constructed rather than decoded.
     *
     * X.690 8.2.2 lets a sender encode TRUE as any non-zero octet, and Android KeyMint emits
     * `0x01`. Retaining that octet instead of normalising it to `0xFF` is what keeps
     * `ASN1.encode(ASN1.decode(bytes))` equal to `bytes`, which this package tests as a contract
     * and which structures signed over their decoded form — such as the entry extensions of a
     * CRL, reached through a plain SEQUENCE — depend on.
     */
    val encodedValue: Byte
): ASN1PrimitiveValue(tag = TAG_NUMBER) {

    /**
     * Constructs a boolean that encodes canonically, as `0xFF` for `true` and `0x00` for `false`.
     *
     * @param value the boolean value.
     */
    constructor(value: Boolean): this(value, if (value) 0xff.toByte() else 0x00.toByte())

    override fun encode(builder: ByteStringBuilder) {
        ASN1.appendUniversalTagEncodingLength(builder, TAG_NUMBER, enc, 1)
        builder.append(encodedValue)
    }

    override fun equals(other: Any?): Boolean = other is ASN1Boolean && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String {
        return "ASN1Boolean($value)"
    }

    companion object {
        const val TAG_NUMBER = 0x01

        /**
         * Decodes a BOOLEAN from its content octets.
         *
         * Any non-zero octet decodes as `true`, per X.690 8.2.2. The requirement that TRUE be
         * `0xFF` is a restriction clause 11 places on encoders, so it is applied when encoding
         * values constructed through the public constructor, not here — see
         * https://github.com/openwallet-foundation/multipaz/issues/1992 for the certificates
         * that motivated this.
         *
         * @param content the content octets, which must be exactly one octet (X.690 8.2.1).
         * @return the decoded value, carrying [encodedValue] so it re-encodes byte-exactly.
         * @throws IllegalArgumentException if [content] is not exactly one octet.
         */
        @Throws(IllegalArgumentException::class)
        fun parse(content: ByteArray): ASN1Boolean {
            require(content.size == 1) { "Content size is ${content.size}, expected 1" }
            return ASN1Boolean(content[0] != 0x00.toByte(), content[0])
        }
    }
}