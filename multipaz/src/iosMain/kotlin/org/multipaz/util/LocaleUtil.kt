package org.multipaz.util

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

// Mapping from ISO 639-2 three-letter codes to ISO 639-1 two-letter codes
// iOS returns three-letter codes via NSLocale.languageCode, but our
// GeneratedTranslations expects two-letter codes
private val iso6392ToIso6391 = mapOf(
    "aar" to "aa", "abk" to "ab", "afr" to "af", "aka" to "ak", "amh" to "am",
    "ara" to "ar", "arg" to "an", "asm" to "as", "ava" to "av", "ave" to "ae",
    "aym" to "ay", "aze" to "az", "bak" to "ba", "bam" to "bm", "bel" to "be",
    "ben" to "bn", "bih" to "bh", "bis" to "bi", "bod" to "bo", "bos" to "bs",
    "bre" to "br", "bul" to "bg", "cat" to "ca", "ces" to "cs", "cha" to "ch",
    "che" to "ce", "chu" to "cu", "chv" to "cv", "cor" to "kw", "cos" to "co",
    "cre" to "cr", "cym" to "cy", "dan" to "da", "deu" to "de", "div" to "dv",
    "dzo" to "dz", "ell" to "el", "eng" to "en", "epo" to "eo", "est" to "et",
    "eus" to "eu", "ewe" to "ee", "fao" to "fo", "fas" to "fa", "fij" to "fj",
    "fin" to "fi", "fra" to "fr", "fry" to "fy", "ful" to "ff", "gae" to "ga",
    "gle" to "ga", "glg" to "gl", "glv" to "gv", "grn" to "gn", "guj" to "gu",
    "hat" to "ht", "hau" to "ha", "heb" to "he", "her" to "hz", "hin" to "hi",
    "hmo" to "ho", "hrv" to "hr", "hun" to "hu", "hye" to "hy", "ibo" to "ig",
    "ido" to "io", "iii" to "ii", "iku" to "iu", "ile" to "ie", "ina" to "ia",
    "ind" to "id", "ipk" to "ik", "isl" to "is", "ita" to "it", "jav" to "jv",
    "jpn" to "ja", "kal" to "kl", "kan" to "kn", "kas" to "ks", "kat" to "ka",
    "kau" to "kr", "kaz" to "kk", "khm" to "km", "kik" to "ki", "kin" to "rw",
    "kir" to "ky", "kom" to "kv", "kon" to "kg", "kor" to "ko", "kua" to "kj",
    "kur" to "ku", "lao" to "lo", "lat" to "la", "lav" to "lv", "lim" to "li",
    "lin" to "ln", "lit" to "lt", "ltz" to "lb", "lub" to "lu", "lug" to "lg",
    "mah" to "mh", "mal" to "ml", "mar" to "mr", "mkd" to "mk", "mlg" to "mg",
    "mlt" to "mt", "mon" to "mn", "mri" to "mi", "msa" to "ms", "mya" to "my",
    "nau" to "na", "nav" to "nv", "nbl" to "nr", "nde" to "nd", "ndo" to "ng",
    "nep" to "ne", "nld" to "nl", "nno" to "nn", "nob" to "nb", "nor" to "no",
    "nya" to "ny", "oci" to "oc", "oji" to "oj", "ori" to "or", "orm" to "om",
    "oss" to "os", "pan" to "pa", "pli" to "pi", "pol" to "pl", "por" to "pt",
    "pus" to "ps", "que" to "qu", "roh" to "rm", "ron" to "ro", "run" to "rn",
    "rus" to "ru", "sag" to "sg", "san" to "sa", "sin" to "si", "slk" to "sk",
    "slv" to "sl", "sme" to "se", "smo" to "sm", "sna" to "sn", "snd" to "sd",
    "som" to "so", "sot" to "st", "spa" to "es", "sqi" to "sq", "srb" to "sr",
    "srp" to "sr", "ssw" to "ss", "sun" to "su", "swa" to "sw", "swe" to "sv",
    "tah" to "ty", "tam" to "ta", "tat" to "tt", "tel" to "te", "tgk" to "tg",
    "tgl" to "tl", "tha" to "th", "tir" to "ti", "ton" to "to", "tsn" to "tn",
    "tso" to "ts", "tuk" to "tk", "tur" to "tr", "twi" to "tw", "uig" to "ug",
    "ukr" to "uk", "urd" to "ur", "uzb" to "uz", "ven" to "ve", "vie" to "vi",
    "vol" to "vo", "wln" to "wa", "wol" to "wo", "xho" to "xh", "yid" to "yi",
    "yor" to "yo", "zha" to "za", "zho" to "zh", "zul" to "zu"
)

actual val currentLocale: String
    get() {
        // iOS returns ISO 639-2 three-letter codes via NSLocale.languageCode
        // (e.g., "spa" for Spanish, "fra" for French)
        val locale = NSLocale.currentLocale
        val threeLetterCode = locale.languageCode

        // Map to two-letter code, fallback to original if not in mapping
        val twoLetterCode = iso6392ToIso6391[threeLetterCode] ?: threeLetterCode

        // Handle special cases:
        // - "zh-Hans" (iOS Simplified Chinese) -> "zh-rCN"
        // - "zh-Hant" (iOS Traditional Chinese) -> "zh" (if you have Traditional Chinese)
        val result = when {
            twoLetterCode == "zh" -> "zh-rCN"  // Map Chinese to Simplified Chinese
            else -> twoLetterCode.substringBefore("-").substringBefore("_")
        }
        return result
    }