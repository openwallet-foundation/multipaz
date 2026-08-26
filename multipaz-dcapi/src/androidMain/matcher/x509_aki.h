#pragma once

#include <cstdint>
#include <cstddef>
#include <cstring>
#include <vector>

namespace x509 {

struct Asn1Element {
    uint8_t tag;
    const uint8_t* data;
    size_t length;
};

inline bool parseAsn1Tlv(const uint8_t* buf, size_t size, size_t& offset, Asn1Element& out) {
    if (offset >= size) return false;
    out.tag = buf[offset++];
    if (offset >= size) return false;
    uint8_t lenByte = buf[offset++];
    if (lenByte < 0x80) {
        out.length = lenByte;
    } else {
        size_t numLenBytes = lenByte & 0x7F;
        if (numLenBytes == 0 || numLenBytes > 4 || offset + numLenBytes > size) return false;
        out.length = 0;
        for (size_t i = 0; i < numLenBytes; ++i) {
            out.length = (out.length << 8) | buf[offset++];
        }
    }
    if (offset + out.length > size) return false;
    out.data = buf + offset;
    offset += out.length;
    return true;
}

// Extracts AuthorityKeyIdentifier (2.5.29.35) keyIdentifier value from a DER-encoded X.509 certificate.
inline bool extractAkiFromDerCert(const uint8_t* certData, size_t certLen, std::vector<uint8_t>& outAki) {
    size_t offset = 0;
    Asn1Element certSeq;
    if (!parseAsn1Tlv(certData, certLen, offset, certSeq) || certSeq.tag != 0x30) {
        return false;
    }

    size_t tbsOffset = 0;
    Asn1Element tbsSeq;
    if (!parseAsn1Tlv(certSeq.data, certSeq.length, tbsOffset, tbsSeq) || tbsSeq.tag != 0x30) {
        return false;
    }

    // Inside TBSCertificate, iterate fields to find [3] EXPLICIT Extensions (tag 0xA3)
    size_t inTbsOffset = 0;
    Asn1Element extWrapper;
    bool foundExtWrapper = false;
    while (inTbsOffset < tbsSeq.length) {
        Asn1Element elem;
        if (!parseAsn1Tlv(tbsSeq.data, tbsSeq.length, inTbsOffset, elem)) {
            break;
        }
        if (elem.tag == 0xA3) { // [3] Context-specific constructed
            extWrapper = elem;
            foundExtWrapper = true;
            break;
        }
    }

    if (!foundExtWrapper) {
        return false;
    }

    // Extensions is a SEQUENCE of Extension
    size_t extsOffset = 0;
    Asn1Element extsSeq;
    if (!parseAsn1Tlv(extWrapper.data, extWrapper.length, extsOffset, extsSeq) || extsSeq.tag != 0x30) {
        return false;
    }

    // OID for AuthorityKeyIdentifier is 2.5.29.35 -> { 0x55, 0x1D, 0x23 }
    static const uint8_t AKI_OID[] = { 0x55, 0x1D, 0x23 };

    size_t extItemOffset = 0;
    while (extItemOffset < extsSeq.length) {
        Asn1Element extSeq;
        if (!parseAsn1Tlv(extsSeq.data, extsSeq.length, extItemOffset, extSeq) || extSeq.tag != 0x30) {
            break;
        }

        size_t singleExtOffset = 0;
        Asn1Element oidElem;
        if (!parseAsn1Tlv(extSeq.data, extSeq.length, singleExtOffset, oidElem) || oidElem.tag != 0x06) {
            continue;
        }

        if (oidElem.length == sizeof(AKI_OID) &&
            memcmp(oidElem.data, AKI_OID, sizeof(AKI_OID)) == 0) {
            // Found AKI extension. Next element is optional critical (BOOLEAN, 0x01) followed by extnValue (OCTET STRING, 0x04)
            Asn1Element nextElem;
            if (!parseAsn1Tlv(extSeq.data, extSeq.length, singleExtOffset, nextElem)) {
                continue;
            }
            if (nextElem.tag == 0x01) { // critical BOOLEAN
                if (!parseAsn1Tlv(extSeq.data, extSeq.length, singleExtOffset, nextElem)) {
                    continue;
                }
            }
            if (nextElem.tag != 0x04) { // OCTET STRING
                continue;
            }

            // Inside OCTET STRING: AuthorityKeyIdentifier ::= SEQUENCE { keyIdentifier [0] KeyIdentifier OPTIONAL, ... }
            size_t akiSeqOffset = 0;
            Asn1Element akiSeq;
            if (!parseAsn1Tlv(nextElem.data, nextElem.length, akiSeqOffset, akiSeq) || akiSeq.tag != 0x30) {
                continue;
            }

            // Look for [0] KeyIdentifier (tag 0x80)
            size_t akiFieldOffset = 0;
            while (akiFieldOffset < akiSeq.length) {
                Asn1Element akiField;
                if (!parseAsn1Tlv(akiSeq.data, akiSeq.length, akiFieldOffset, akiField)) {
                    break;
                }
                if (akiField.tag == 0x80) { // [0] Context-specific primitive
                    outAki.assign(akiField.data, akiField.data + akiField.length);
                    return true;
                }
            }
        }
    }

    return false;
}

} // namespace x509
