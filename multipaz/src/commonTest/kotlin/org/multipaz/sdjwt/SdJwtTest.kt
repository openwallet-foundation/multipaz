package org.multipaz.sdjwt

import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SdJwtTest {
    companion object {

        @OptIn(ExperimentalSerializationApi::class)
        val prettyJson = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        fun prettyPrintDisclosures(disclosures: List<String>): String {
            return prettyJson.encodeToString(
                disclosures.map { Json.decodeFromString(
                    JsonArray.serializer(),
                    it.fromBase64Url().decodeToString()
                ).jsonArray }
            )
        }

        // From SD-JWT RFC Appendix A.5
        val sdJwtRfcIssuerKey = EcPublicKey.fromJwk(
            Json.decodeFromString(
                JsonObject.serializer(),
                """
                   {
                     "kty": "EC",
                     "crv": "P-256",
                     "x": "b28d4MwZMjw8-00CG4xfnn9SLMVMM19SlqZpVb_uNtQ",
                     "y": "Xv5zWwuoaTgdS6hV43yI6gBwTnjukmFQQnJ_kCxzqk8"
                   }
            """.trimIndent()
            )
        )

        val sdJwtRfcSection51SdJwtCompactSerialization =
            """
               eyJhbGciOiAiRVMyNTYiLCAidHlwIjogImV4YW1wbGUrc2Qtand0In0.eyJfc2QiOiBb
               IkNyUWU3UzVrcUJBSHQtbk1ZWGdjNmJkdDJTSDVhVFkxc1VfTS1QZ2tqUEkiLCAiSnpZ
               akg0c3ZsaUgwUjNQeUVNZmVadTZKdDY5dTVxZWhabzdGN0VQWWxTRSIsICJQb3JGYnBL
               dVZ1Nnh5bUphZ3ZrRnNGWEFiUm9jMkpHbEFVQTJCQTRvN2NJIiwgIlRHZjRvTGJnd2Q1
               SlFhSHlLVlFaVTlVZEdFMHc1cnREc3JaemZVYW9tTG8iLCAiWFFfM2tQS3QxWHlYN0tB
               TmtxVlI2eVoyVmE1TnJQSXZQWWJ5TXZSS0JNTSIsICJYekZyendzY002R242Q0pEYzZ2
               Vks4QmtNbmZHOHZPU0tmcFBJWmRBZmRFIiwgImdiT3NJNEVkcTJ4Mkt3LXc1d1BFemFr
               b2I5aFYxY1JEMEFUTjNvUUw5Sk0iLCAianN1OXlWdWx3UVFsaEZsTV8zSmx6TWFTRnpn
               bGhRRzBEcGZheVF3TFVLNCJdLCAiaXNzIjogImh0dHBzOi8vaXNzdWVyLmV4YW1wbGUu
               Y29tIiwgImlhdCI6IDE2ODMwMDAwMDAsICJleHAiOiAxODgzMDAwMDAwLCAic3ViIjog
               InVzZXJfNDIiLCAibmF0aW9uYWxpdGllcyI6IFt7Ii4uLiI6ICJwRm5kamtaX1ZDem15
               VGE2VWpsWm8zZGgta284YUlLUWM5RGxHemhhVllvIn0sIHsiLi4uIjogIjdDZjZKa1B1
               ZHJ5M2xjYndIZ2VaOGtoQXYxVTFPU2xlclAwVmtCSnJXWjAifV0sICJfc2RfYWxnIjog
               InNoYS0yNTYiLCAiY25mIjogeyJqd2siOiB7Imt0eSI6ICJFQyIsICJjcnYiOiAiUC0y
               NTYiLCAieCI6ICJUQ0FFUjE5WnZ1M09IRjRqNFc0dmZTVm9ISVAxSUxpbERsczd2Q2VH
               ZW1jIiwgInkiOiAiWnhqaVdXYlpNUUdIVldLVlE0aGJTSWlyc1ZmdWVjQ0U2dDRqVDlG
               MkhaUSJ9fX0.1wnNkOyAsH74SYPZkhz96PS5lzhYiTwlvWBF7iULXanW0VIK--EXM1Hr
               cAQyvurivQvbHQu7NMoq1_8CVnM0pw~WyIyR0xDNDJzS1F2ZUNmR2ZyeU5STjl3IiwgI
               mdpdmVuX25hbWUiLCAiSm9obiJd~WyJlbHVWNU9nM2dTTklJOEVZbnN4QV9BIiwgImZh
               bWlseV9uYW1lIiwgIkRvZSJd~WyI2SWo3dE0tYTVpVlBHYm9TNXRtdlZBIiwgImVtYWl
               sIiwgImpvaG5kb2VAZXhhbXBsZS5jb20iXQ~WyJlSThaV205UW5LUHBOUGVOZW5IZGhR
               IiwgInBob25lX251bWJlciIsICIrMS0yMDItNTU1LTAxMDEiXQ~WyJRZ19PNjR6cUF4Z
               TQxMmExMDhpcm9BIiwgInBob25lX251bWJlcl92ZXJpZmllZCIsIHRydWVd~WyJBSngt
               MDk1VlBycFR0TjRRTU9xUk9BIiwgImFkZHJlc3MiLCB7InN0cmVldF9hZGRyZXNzIjog
               IjEyMyBNYWluIFN0IiwgImxvY2FsaXR5IjogIkFueXRvd24iLCAicmVnaW9uIjogIkFu
               eXN0YXRlIiwgImNvdW50cnkiOiAiVVMifV0~WyJQYzMzSk0yTGNoY1VfbEhnZ3ZfdWZR
               IiwgImJpcnRoZGF0ZSIsICIxOTQwLTAxLTAxIl0~WyJHMDJOU3JRZmpGWFE3SW8wOXN5
               YWpBIiwgInVwZGF0ZWRfYXQiLCAxNTcwMDAwMDAwXQ~WyJsa2x4RjVqTVlsR1RQVW92T
               U5JdkNBIiwgIlVTIl0~WyJuUHVvUW5rUkZxM0JJZUFtN0FuWEZBIiwgIkRFIl0~
            """.replace("\\s".toRegex(), "")

        val sdJwtRfcAppendixA1SdJwtCompactSerialization =
            """
                eyJhbGciOiAiRVMyNTYiLCAidHlwIjogImV4YW1wbGUrc2Qtand0In0.eyJfc2QiOiBb
                IkM5aW5wNllvUmFFWFI0Mjd6WUpQN1FyazFXSF84YmR3T0FfWVVyVW5HUVUiLCAiS3Vl
                dDF5QWEwSElRdlluT1ZkNTloY1ZpTzlVZzZKMmtTZnFZUkJlb3d2RSIsICJNTWxkT0ZG
                ekIyZDB1bWxtcFRJYUdlcmhXZFVfUHBZZkx2S2hoX2ZfOWFZIiwgIlg2WkFZT0lJMnZQ
                TjQwVjd4RXhad1Z3ejd5Um1MTmNWd3Q1REw4Ukx2NGciLCAiWTM0em1JbzBRTExPdGRN
                cFhHd2pCZ0x2cjE3eUVoaFlUMEZHb2ZSLWFJRSIsICJmeUdwMFdUd3dQdjJKRFFsbjFs
                U2lhZW9iWnNNV0ExMGJRNTk4OS05RFRzIiwgIm9tbUZBaWNWVDhMR0hDQjB1eXd4N2ZZ
                dW8zTUhZS08xNWN6LVJaRVlNNVEiLCAiczBCS1lzTFd4UVFlVTh0VmxsdE03TUtzSVJU
                ckVJYTFQa0ptcXhCQmY1VSJdLCAiaXNzIjogImh0dHBzOi8vaXNzdWVyLmV4YW1wbGUu
                Y29tIiwgImlhdCI6IDE2ODMwMDAwMDAsICJleHAiOiAxODgzMDAwMDAwLCAiYWRkcmVz
                cyI6IHsiX3NkIjogWyI2YVVoelloWjdTSjFrVm1hZ1FBTzN1MkVUTjJDQzFhSGhlWnBL
                bmFGMF9FIiwgIkF6TGxGb2JrSjJ4aWF1cFJFUHlvSnotOS1OU2xkQjZDZ2pyN2ZVeW9I
                emciLCAiUHp6Y1Z1MHFiTXVCR1NqdWxmZXd6a2VzRDl6dXRPRXhuNUVXTndrclEtayIs
                ICJiMkRrdzBqY0lGOXJHZzhfUEY4WmN2bmNXN3p3Wmo1cnlCV3ZYZnJwemVrIiwgImNQ
                WUpISVo4VnUtZjlDQ3lWdWIyVWZnRWs4anZ2WGV6d0sxcF9KbmVlWFEiLCAiZ2xUM2hy
                U1U3ZlNXZ3dGNVVEWm1Xd0JUdzMyZ25VbGRJaGk4aEdWQ2FWNCIsICJydkpkNmlxNlQ1
                ZWptc0JNb0d3dU5YaDlxQUFGQVRBY2k0MG9pZEVlVnNBIiwgInVOSG9XWWhYc1poVkpD
                TkUyRHF5LXpxdDd0NjlnSkt5NVFhRnY3R3JNWDQiXX0sICJfc2RfYWxnIjogInNoYS0y
                NTYifQ.l22li-9g1583A-ss1GRBME-o07KC1fD7gu4i5447y-m8RWVyxHxgcpfM2qb50
                OVSQ9maksoaAO9RfWQv7Uf61Q~WyJHMDJOU3JRZmpGWFE3SW8wOXN5YWpBIiwgInJlZ2
                lvbiIsICJcdTZlMmZcdTUzM2EiXQ~WyJsa2x4RjVqTVlsR1RQVW92TU5JdkNBIiwgImN
                vdW50cnkiLCAiSlAiXQ~
        """.replace("\\s".toRegex(), "")

        val sdJwtRfcAppendixA2SdJwtCompactSerialization =
            """
                eyJhbGciOiAiRVMyNTYiLCAidHlwIjogImV4YW1wbGUrc2Qtand0In0.eyJfc2QiOiBb
                Ii1hU3puSWQ5bVdNOG9jdVFvbENsbHN4VmdncTEtdkhXNE90bmhVdFZtV3ciLCAiSUti
                cllObjN2QTdXRUZyeXN2YmRCSmpERFVfRXZRSXIwVzE4dlRScFVTZyIsICJvdGt4dVQx
                NG5CaXd6TkozTVBhT2l0T2w5cFZuWE9hRUhhbF94a3lOZktJIl0sICJpc3MiOiAiaHR0
                cHM6Ly9pc3N1ZXIuZXhhbXBsZS5jb20iLCAiaWF0IjogMTY4MzAwMDAwMCwgImV4cCI6
                IDE4ODMwMDAwMDAsICJ2ZXJpZmllZF9jbGFpbXMiOiB7InZlcmlmaWNhdGlvbiI6IHsi
                X3NkIjogWyI3aDRVRTlxU2N2REtvZFhWQ3VvS2ZLQkpwVkJmWE1GX1RtQUdWYVplM1Nj
                IiwgInZUd2UzcmFISUZZZ0ZBM3hhVUQyYU14Rno1b0RvOGlCdTA1cUtsT2c5THciXSwg
                InRydXN0X2ZyYW1ld29yayI6ICJkZV9hbWwiLCAiZXZpZGVuY2UiOiBbeyIuLi4iOiAi
                dFlKMFREdWN5WlpDUk1iUk9HNHFSTzV2a1BTRlJ4RmhVRUxjMThDU2wzayJ9XX0sICJj
                bGFpbXMiOiB7Il9zZCI6IFsiUmlPaUNuNl93NVpIYWFka1FNcmNRSmYwSnRlNVJ3dXJS
                czU0MjMxRFRsbyIsICJTXzQ5OGJicEt6QjZFYW5mdHNzMHhjN2NPYW9uZVJyM3BLcjdO
                ZFJtc01vIiwgIldOQS1VTks3Rl96aHNBYjlzeVdPNklJUTF1SGxUbU9VOHI4Q3ZKMGNJ
                TWsiLCAiV3hoX3NWM2lSSDliZ3JUQkppLWFZSE5DTHQtdmpoWDFzZC1pZ09mXzlsayIs
                ICJfTy13SmlIM2VuU0I0Uk9IbnRUb1FUOEptTHR6LW1oTzJmMWM4OVhvZXJRIiwgImh2
                RFhod21HY0pRc0JDQTJPdGp1TEFjd0FNcERzYVUwbmtvdmNLT3FXTkUiXX19LCAiX3Nk
                X2FsZyI6ICJzaGEtMjU2In0.1Ab2YOOWW_vxIOoeDzW1hvRYCaR4-yxerH7t9I0RqNcr
                xQrOiOt9_z7wch_nNHG2BAvkNkDGW5nW1OZZUkkGtA~WyIyR0xDNDJzS1F2ZUNmR2Zye
                U5STjl3IiwgInRpbWUiLCAiMjAxMi0wNC0yM1QxODoyNVoiXQ~WyJQYzMzSk0yTGNoY1
                VfbEhnZ3ZfdWZRIiwgeyJfc2QiOiBbIjl3cGpWUFd1RDdQSzBuc1FETDhCMDZsbWRnVj
                NMVnliaEh5ZFFwVE55TEkiLCAiRzVFbmhPQU9vVTlYXzZRTU52ekZYanBFQV9SYy1BRX
                RtMWJHX3djYUtJayIsICJJaHdGcldVQjYzUmNacTl5dmdaMFhQYzdHb3doM08ya3FYZU
                JJc3dnMUI0IiwgIldweFE0SFNvRXRjVG1DQ0tPZURzbEJfZW11Y1lMejJvTzhvSE5yMW
                JFVlEiXX1d~WyJlSThaV205UW5LUHBOUGVOZW5IZGhRIiwgIm1ldGhvZCIsICJwaXBwI
                l0~WyJHMDJOU3JRZmpGWFE3SW8wOXN5YWpBIiwgImdpdmVuX25hbWUiLCAiTWF4Il0~W
                yJsa2x4RjVqTVlsR1RQVW92TU5JdkNBIiwgImZhbWlseV9uYW1lIiwgIk1cdTAwZmNsb
                GVyIl0~WyJ5MXNWVTV3ZGZKYWhWZGd3UGdTN1JRIiwgImFkZHJlc3MiLCB7ImxvY2Fsa
                XR5IjogIk1heHN0YWR0IiwgInBvc3RhbF9jb2RlIjogIjEyMzQ0IiwgImNvdW50cnkiO
                iAiREUiLCAic3RyZWV0X2FkZHJlc3MiOiAiV2VpZGVuc3RyYVx1MDBkZmUgMjIifV0~
        """.replace("\\s".toRegex(), "")

        val sdJwtRfcAppendixA3SdJwtCompactSerialization =
            """
               eyJhbGciOiAiRVMyNTYiLCAidHlwIjogImRjK3NkLWp3dCJ9.eyJfc2QiOiBbIjBIWm1
               uU0lQejMzN2tTV2U3QzM0bC0tODhnekppLWVCSjJWel9ISndBVGciLCAiMUNybjAzV21
               VZVJXcDR6d1B2dkNLWGw5WmFRcC1jZFFWX2dIZGFHU1dvdyIsICIycjAwOWR6dkh1VnJ
               XclJYVDVrSk1tSG5xRUhIbldlME1MVlp3OFBBVEI4IiwgIjZaTklTRHN0NjJ5bWxyT0F
               rYWRqZEQ1WnVsVDVBMjk5Sjc4U0xoTV9fT3MiLCAiNzhqZzc3LUdZQmVYOElRZm9FTFB
               5TDBEWVBkbWZabzBKZ1ZpVjBfbEtDTSIsICI5MENUOEFhQlBibjVYOG5SWGtlc2p1MWk
               wQnFoV3FaM3dxRDRqRi1xREdrIiwgIkkwMGZjRlVvRFhDdWNwNXl5MnVqcVBzc0RWR2F
               XTmlVbGlOel9hd0QwZ2MiLCAiS2pBWGdBQTlONVdIRUR0UkloNHU1TW4xWnNXaXhoaFd
               BaVgtQTRRaXdnQSIsICJMYWk2SVU2ZDdHUWFnWFI3QXZHVHJuWGdTbGQzejhFSWdfZnY
               zZk9aMVdnIiwgIkxlemphYlJxaVpPWHpFWW1WWmY4Uk1pOXhBa2QzX00xTFo4VTdFNHM
               zdTQiLCAiUlR6M3FUbUZOSGJwV3JyT01aUzQxRjQ3NGtGcVJ2M3ZJUHF0aDZQVWhsTSI
               sICJXMTRYSGJVZmZ6dVc0SUZNanBTVGIxbWVsV3hVV2Y0Tl9vMmxka2tJcWM4IiwgIld
               UcEk3UmNNM2d4WnJ1UnBYemV6U2JrYk9yOTNQVkZ2V3g4d29KM2oxY0UiLCAiX29oSlZ
               JUUlCc1U0dXBkTlM0X3c0S2IxTUhxSjBMOXFMR3NoV3E2SlhRcyIsICJ5NTBjemMwSVN
               DaHlfYnNiYTFkTW9VdUFPUTVBTW1PU2ZHb0VlODF2MUZVIl0sICJpc3MiOiAiaHR0cHM
               6Ly9waWQtaXNzdWVyLmJ1bmQuZGUuZXhhbXBsZSIsICJpYXQiOiAxNjgzMDAwMDAwLCA
               iZXhwIjogMTg4MzAwMDAwMCwgInZjdCI6ICJ1cm46ZXVkaTpwaWQ6ZGU6MSIsICJfc2R
               fYWxnIjogInNoYS0yNTYiLCAiY25mIjogeyJqd2siOiB7Imt0eSI6ICJFQyIsICJjcnY
               iOiAiUC0yNTYiLCAieCI6ICJUQ0FFUjE5WnZ1M09IRjRqNFc0dmZTVm9ISVAxSUxpbER
               sczd2Q2VHZW1jIiwgInkiOiAiWnhqaVdXYlpNUUdIVldLVlE0aGJTSWlyc1ZmdWVjQ0U
               2dDRqVDlGMkhaUSJ9fX0.jZdt97QHNbJlmVF-2B1ZDc0HbkZW8QkO2aId2dL3LaZMSlF
               3axe8V8lXH4eC3F8WI0-_Zb7SpPTnhaXQoP9AVQ~WyIyR0xDNDJzS1F2ZUNmR2ZyeU5S
               Tjl3IiwgImdpdmVuX25hbWUiLCAiRXJpa2EiXQ~WyJlbHVWNU9nM2dTTklJOEVZbnN4Q
               V9BIiwgImZhbWlseV9uYW1lIiwgIk11c3Rlcm1hbm4iXQ~WyI2SWo3dE0tYTVpVlBHYm
               9TNXRtdlZBIiwgImJpcnRoZGF0ZSIsICIxOTYzLTA4LTEyIl0~WyJlSThaV205UW5LUH
               BOUGVOZW5IZGhRIiwgInN0cmVldF9hZGRyZXNzIiwgIkhlaWRlc3RyYVx1MDBkZmUgMT
               ciXQ~WyJRZ19PNjR6cUF4ZTQxMmExMDhpcm9BIiwgImxvY2FsaXR5IiwgIktcdTAwZjZ
               sbiJd~WyJBSngtMDk1VlBycFR0TjRRTU9xUk9BIiwgInBvc3RhbF9jb2RlIiwgIjUxMT
               Q3Il0~WyJQYzMzSk0yTGNoY1VfbEhnZ3ZfdWZRIiwgImNvdW50cnkiLCAiREUiXQ~WyJ
               HMDJOU3JRZmpGWFE3SW8wOXN5YWpBIiwgImFkZHJlc3MiLCB7Il9zZCI6IFsiQUxaRVJ
               zU241V05pRVhkQ2tzVzhJNXFRdzNfTnBBblJxcFNBWkR1ZGd3OCIsICJEX19XX3VZY3Z
               SejN0dlVuSUp2QkRIaVRjN0NfX3FIZDB4Tkt3SXNfdzlrIiwgImVCcENYVTFKNWRoSDJ
               nNHQ4UVlOVzVFeFM5QXhVVmJsVW9kb0xZb1BobzAiLCAieE9QeTktZ0pBTEs2VWJXS0Z
               MUjg1Y09CeVVEM0FiTndGZzNJM1lmUUVfSSJdfV0~WyJsa2x4RjVqTVlsR1RQVW92TU5
               JdkNBIiwgIm5hdGlvbmFsaXRpZXMiLCBbIkRFIl1d~WyJuUHVvUW5rUkZxM0JJZUFtN0
               FuWEZBIiwgInNleCIsIDJd~WyI1YlBzMUlxdVpOYTBoa2FGenp6Wk53IiwgImJpcnRoX
               2ZhbWlseV9uYW1lIiwgIkdhYmxlciJd~WyI1YTJXMF9OcmxFWnpmcW1rXzdQcS13Iiwg
               ImxvY2FsaXR5IiwgIkJlcmxpbiJd~WyJ5MXNWVTV3ZGZKYWhWZGd3UGdTN1JRIiwgImN
               vdW50cnkiLCAiREUiXQ~WyJIYlE0WDhzclZXM1FEeG5JSmRxeU9BIiwgInBsYWNlX29m
               X2JpcnRoIiwgeyJfc2QiOiBbIktVVmlhYUxuWTVqU01MOTBHMjlPT0xFTlBiYlhmaFNq
               U1BNalphR2t4QUUiLCAiWWJzVDBTNzZWcVhDVnNkMWpVU2x3S1BEZ21BTGVCMXVaY2xG
               SFhmLVVTUSJdfV0~WyJDOUdTb3VqdmlKcXVFZ1lmb2pDYjFBIiwgIjEyIiwgdHJ1ZV0~
               WyJreDVrRjE3Vi14MEptd1V4OXZndnR3IiwgIjE0IiwgdHJ1ZV0~WyJIM28xdXN3UDc2
               MEZpMnllR2RWQ0VRIiwgIjE2IiwgdHJ1ZV0~WyJPQktsVFZsdkxnLUFkd3FZR2JQOFpB
               IiwgIjE4IiwgdHJ1ZV0~WyJNMEpiNTd0NDF1YnJrU3V5ckRUM3hBIiwgIjIxIiwgdHJ1
               ZV0~WyJEc210S05ncFY0ZEFIcGpyY2Fvc0F3IiwgIjY1IiwgZmFsc2Vd~WyJlSzVvNXB
               IZmd1cFBwbHRqMXFoQUp3IiwgImFnZV9lcXVhbF9vcl9vdmVyIiwgeyJfc2QiOiBbIjF
               0RWl5elBSWU9Lc2Y3U3NZR01nUFpLc09UMWxRWlJ4SFhBMHI1X0J3a2siLCAiQ1ZLbmx
               5NVA5MHlKczNFd3R4UWlPdFVjemFYQ1lOQTRJY3pSYW9ock1EZyIsICJhNDQtZzJHcjh
               fM0FtSncyWFo4a0kxeTBRel96ZTlpT2NXMlczUkxwWEdnIiwgImdrdnkwRnV2QkJ2ajB
               oczJaTnd4Y3FPbGY4bXUyLWtDRTctTmIyUXh1QlUiLCAiaHJZNEhubUY1YjVKd0M5ZVR
               6YUZDVWNlSVFBYUlkaHJxVVhRTkNXYmZaSSIsICJ5NlNGclZGUnlxNTBJYlJKdmlUWnF
               xalFXejB0TGl1Q21NZU8wS3FhekdJIl19XQ~WyJqN0FEZGIwVVZiMExpMGNpUGNQMGV3
               IiwgImFnZV9pbl95ZWFycyIsIDYyXQ~WyJXcHhKckZ1WDh1U2kycDRodDA5anZ3IiwgI
               mFnZV9iaXJ0aF95ZWFyIiwgMTk2M10~WyJhdFNtRkFDWU1iSlZLRDA1bzNKZ3RRIiwgI
               mlzc3VhbmNlX2RhdGUiLCAiMjAyMC0wMy0xMSJd~WyI0S3lSMzJvSVp0LXprV3ZGcWJV
               TEtnIiwgImV4cGlyeV9kYXRlIiwgIjIwMzAtMDMtMTIiXQ~WyJjaEJDc3loeWgtSjg2S
               S1hd1FEaUNRIiwgImlzc3VpbmdfYXV0aG9yaXR5IiwgIkRFIl0~WyJmbE5QMW5jTXo5T
               GctYzlxTUl6XzlnIiwgImlzc3VpbmdfY291bnRyeSIsICJERSJd~
            """.replace("\\s".toRegex(), "")

        val sdJwtRfcAppendixA3SdJwtKbCompactSerialization =
            """
               eyJhbGciOiAiRVMyNTYiLCAidHlwIjogImRjK3NkLWp3dCJ9.eyJfc2QiOiBbIjBIWm1
               uU0lQejMzN2tTV2U3QzM0bC0tODhnekppLWVCSjJWel9ISndBVGciLCAiMUNybjAzV21
               VZVJXcDR6d1B2dkNLWGw5WmFRcC1jZFFWX2dIZGFHU1dvdyIsICIycjAwOWR6dkh1VnJ
               XclJYVDVrSk1tSG5xRUhIbldlME1MVlp3OFBBVEI4IiwgIjZaTklTRHN0NjJ5bWxyT0F
               rYWRqZEQ1WnVsVDVBMjk5Sjc4U0xoTV9fT3MiLCAiNzhqZzc3LUdZQmVYOElRZm9FTFB
               5TDBEWVBkbWZabzBKZ1ZpVjBfbEtDTSIsICI5MENUOEFhQlBibjVYOG5SWGtlc2p1MWk
               wQnFoV3FaM3dxRDRqRi1xREdrIiwgIkkwMGZjRlVvRFhDdWNwNXl5MnVqcVBzc0RWR2F
               XTmlVbGlOel9hd0QwZ2MiLCAiS2pBWGdBQTlONVdIRUR0UkloNHU1TW4xWnNXaXhoaFd
               BaVgtQTRRaXdnQSIsICJMYWk2SVU2ZDdHUWFnWFI3QXZHVHJuWGdTbGQzejhFSWdfZnY
               zZk9aMVdnIiwgIkxlemphYlJxaVpPWHpFWW1WWmY4Uk1pOXhBa2QzX00xTFo4VTdFNHM
               zdTQiLCAiUlR6M3FUbUZOSGJwV3JyT01aUzQxRjQ3NGtGcVJ2M3ZJUHF0aDZQVWhsTSI
               sICJXMTRYSGJVZmZ6dVc0SUZNanBTVGIxbWVsV3hVV2Y0Tl9vMmxka2tJcWM4IiwgIld
               UcEk3UmNNM2d4WnJ1UnBYemV6U2JrYk9yOTNQVkZ2V3g4d29KM2oxY0UiLCAiX29oSlZ
               JUUlCc1U0dXBkTlM0X3c0S2IxTUhxSjBMOXFMR3NoV3E2SlhRcyIsICJ5NTBjemMwSVN
               DaHlfYnNiYTFkTW9VdUFPUTVBTW1PU2ZHb0VlODF2MUZVIl0sICJpc3MiOiAiaHR0cHM
               6Ly9waWQtaXNzdWVyLmJ1bmQuZGUuZXhhbXBsZSIsICJpYXQiOiAxNjgzMDAwMDAwLCA
               iZXhwIjogMTg4MzAwMDAwMCwgInZjdCI6ICJ1cm46ZXVkaTpwaWQ6ZGU6MSIsICJfc2R
               fYWxnIjogInNoYS0yNTYiLCAiY25mIjogeyJqd2siOiB7Imt0eSI6ICJFQyIsICJjcnY
               iOiAiUC0yNTYiLCAieCI6ICJUQ0FFUjE5WnZ1M09IRjRqNFc0dmZTVm9ISVAxSUxpbER
               sczd2Q2VHZW1jIiwgInkiOiAiWnhqaVdXYlpNUUdIVldLVlE0aGJTSWlyc1ZmdWVjQ0U
               2dDRqVDlGMkhaUSJ9fX0.jZdt97QHNbJlmVF-2B1ZDc0HbkZW8QkO2aId2dL3LaZMSlF
               3axe8V8lXH4eC3F8WI0-_Zb7SpPTnhaXQoP9AVQ~WyJlSzVvNXBIZmd1cFBwbHRqMXFo
               QUp3IiwgImFnZV9lcXVhbF9vcl9vdmVyIiwgeyJfc2QiOiBbIjF0RWl5elBSWU9Lc2Y3
               U3NZR01nUFpLc09UMWxRWlJ4SFhBMHI1X0J3a2siLCAiQ1ZLbmx5NVA5MHlKczNFd3R4
               UWlPdFVjemFYQ1lOQTRJY3pSYW9ock1EZyIsICJhNDQtZzJHcjhfM0FtSncyWFo4a0kx
               eTBRel96ZTlpT2NXMlczUkxwWEdnIiwgImdrdnkwRnV2QkJ2ajBoczJaTnd4Y3FPbGY4
               bXUyLWtDRTctTmIyUXh1QlUiLCAiaHJZNEhubUY1YjVKd0M5ZVR6YUZDVWNlSVFBYUlk
               aHJxVVhRTkNXYmZaSSIsICJ5NlNGclZGUnlxNTBJYlJKdmlUWnFxalFXejB0TGl1Q21N
               ZU8wS3FhekdJIl19XQ~WyJPQktsVFZsdkxnLUFkd3FZR2JQOFpBIiwgIjE4IiwgdHJ1Z
               V0~WyJsa2x4RjVqTVlsR1RQVW92TU5JdkNBIiwgIm5hdGlvbmFsaXRpZXMiLCBbIkRFI
               l1d~eyJhbGciOiAiRVMyNTYiLCAidHlwIjogImtiK2p3dCJ9.eyJub25jZSI6ICIxMjM
               0NTY3ODkwIiwgImF1ZCI6ICJodHRwczovL3ZlcmlmaWVyLmV4YW1wbGUub3JnIiwgIml
               hdCI6IDE3NDg0NTQyNzEsICJzZF9oYXNoIjogIk9YNUM3ejVPcXV0MmZhTGVkc3NrRTl
               1TTZoTC1FN3JVaWJfOVl4LWFzRk0ifQ.i7B5FOvjBpJd1aaBJ1T_AkhJP_MwMaL6HHXm
               NziJ1j7RgOCRfIBhswg-tSuVs03bmcMGiAXXiJA32LzTFfh6cA
            """.replace("\\s".toRegex(), "")

        val sdJwtRfcAppendixA4SdJwtCompactSerialization =
            """
               eyJhbGciOiAiRVMyNTYiLCAidHlwIjogImV4YW1wbGUrc2Qtand0In0.eyJAY29udGV4
               dCI6IFsiaHR0cHM6Ly93d3cudzMub3JnLzIwMTgvY3JlZGVudGlhbHMvdjEiLCAiaHR0
               cHM6Ly93M2lkLm9yZy92YWNjaW5hdGlvbi92MSJdLCAidHlwZSI6IFsiVmVyaWZpYWJs
               ZUNyZWRlbnRpYWwiLCAiVmFjY2luYXRpb25DZXJ0aWZpY2F0ZSJdLCAiaXNzdWVyIjog
               Imh0dHBzOi8vZXhhbXBsZS5jb20vaXNzdWVyIiwgImlzc3VhbmNlRGF0ZSI6ICIyMDIz
               LTAyLTA5VDExOjAxOjU5WiIsICJleHBpcmF0aW9uRGF0ZSI6ICIyMDI4LTAyLTA4VDEx
               OjAxOjU5WiIsICJuYW1lIjogIkNPVklELTE5IFZhY2NpbmF0aW9uIENlcnRpZmljYXRl
               IiwgImRlc2NyaXB0aW9uIjogIkNPVklELTE5IFZhY2NpbmF0aW9uIENlcnRpZmljYXRl
               IiwgImNyZWRlbnRpYWxTdWJqZWN0IjogeyJfc2QiOiBbIjFWX0stOGxEUThpRlhCRlhi
               Wlk5ZWhxUjRIYWJXQ2k1VDB5Ykl6WlBld3ciLCAiSnpqTGd0UDI5ZFAtQjN0ZDEyUDY3
               NGdGbUsyenk4MUhNdEJnZjZDSk5XZyIsICJSMmZHYmZBMDdaX1lsa3FtTlp5bWExeHl5
               eDFYc3RJaVM2QjFZYmwySlo0IiwgIlRDbXpybDdLMmdldl9kdTdwY01JeXpSTEhwLVll
               Zy1GbF9jeHRyVXZQeGciLCAiVjdrSkJMSzc4VG1WRE9tcmZKN1p1VVBIdUtfMmNjN3la
               UmE0cVYxdHh3TSIsICJiMGVVc3ZHUC1PRERkRm9ZNE5semxYYzN0RHNsV0p0Q0pGNzVO
               dzhPal9nIiwgInpKS19lU01YandNOGRYbU1aTG5JOEZHTTA4ekozX3ViR2VFTUotNVRC
               eTAiXSwgInZhY2NpbmUiOiB7Il9zZCI6IFsiMWNGNWhMd2toTU5JYXFmV0pyWEk3Tk1X
               ZWRMLTlmNlkyUEE1MnlQalNaSSIsICJIaXk2V1d1ZUxENWJuMTYyOTh0UHY3R1hobWxk
               TURPVG5CaS1DWmJwaE5vIiwgIkxiMDI3cTY5MWpYWGwtakM3M3ZpOGViT2o5c214M0Mt
               X29nN2dBNFRCUUUiXSwgInR5cGUiOiAiVmFjY2luZSJ9LCAicmVjaXBpZW50IjogeyJf
               c2QiOiBbIjFsU1FCTlkyNHEwVGg2T0d6dGhxLTctNGw2Y0FheHJZWE9HWnBlV19sbkEi
               LCAiM256THE4MU0yb04wNndkdjFzaEh2T0VKVnhaNUtMbWREa0hFREpBQldFSSIsICJQ
               bjFzV2kwNkc0TEpybm4tX1JUMFJiTV9IVGR4blBKUXVYMmZ6V3ZfSk9VIiwgImxGOXV6
               ZHN3N0hwbEdMYzcxNFRyNFdPN01HSnphN3R0N1FGbGVDWDRJdHciXSwgInR5cGUiOiAi
               VmFjY2luZVJlY2lwaWVudCJ9LCAidHlwZSI6ICJWYWNjaW5hdGlvbkV2ZW50In0sICJf
               c2RfYWxnIjogInNoYS0yNTYiLCAiY25mIjogeyJqd2siOiB7Imt0eSI6ICJFQyIsICJj
               cnYiOiAiUC0yNTYiLCAieCI6ICJUQ0FFUjE5WnZ1M09IRjRqNFc0dmZTVm9ISVAxSUxp
               bERsczd2Q2VHZW1jIiwgInkiOiAiWnhqaVdXYlpNUUdIVldLVlE0aGJTSWlyc1ZmdWVj
               Q0U2dDRqVDlGMkhaUSJ9fX0.umiUzP5osIlBr2oY_qEEVRXDHTPgEsdc8vAgnSQUfgrY
               2WBvvnAdftbHGs496ZW8bsK096myVYIQJyplHinDEA~WyIyR0xDNDJzS1F2ZUNmR2Zye
               U5STjl3IiwgImF0Y0NvZGUiLCAiSjA3QlgwMyJd~WyJlbHVWNU9nM2dTTklJOEVZbnN4
               QV9BIiwgIm1lZGljaW5hbFByb2R1Y3ROYW1lIiwgIkNPVklELTE5IFZhY2NpbmUgTW9k
               ZXJuYSJd~WyI2SWo3dE0tYTVpVlBHYm9TNXRtdlZBIiwgIm1hcmtldGluZ0F1dGhvcml
               6YXRpb25Ib2xkZXIiLCAiTW9kZXJuYSBCaW90ZWNoIl0~WyJlSThaV205UW5LUHBOUGV
               OZW5IZGhRIiwgIm5leHRWYWNjaW5hdGlvbkRhdGUiLCAiMjAyMS0wOC0xNlQxMzo0MDo
               xMloiXQ~WyJRZ19PNjR6cUF4ZTQxMmExMDhpcm9BIiwgImNvdW50cnlPZlZhY2NpbmF0
               aW9uIiwgIkdFIl0~WyJBSngtMDk1VlBycFR0TjRRTU9xUk9BIiwgImRhdGVPZlZhY2Np
               bmF0aW9uIiwgIjIwMjEtMDYtMjNUMTM6NDA6MTJaIl0~WyJQYzMzSk0yTGNoY1VfbEhn
               Z3ZfdWZRIiwgIm9yZGVyIiwgIjMvMyJd~WyJHMDJOU3JRZmpGWFE3SW8wOXN5YWpBIiw
               gImdlbmRlciIsICJGZW1hbGUiXQ~WyJsa2x4RjVqTVlsR1RQVW92TU5JdkNBIiwgImJp
               cnRoRGF0ZSIsICIxOTYxLTA4LTE3Il0~WyJuUHVvUW5rUkZxM0JJZUFtN0FuWEZBIiwg
               ImdpdmVuTmFtZSIsICJNYXJpb24iXQ~WyI1YlBzMUlxdVpOYTBoa2FGenp6Wk53IiwgI
               mZhbWlseU5hbWUiLCAiTXVzdGVybWFubiJd~WyI1YTJXMF9OcmxFWnpmcW1rXzdQcS13
               IiwgImFkbWluaXN0ZXJpbmdDZW50cmUiLCAiUHJheGlzIFNvbW1lcmdhcnRlbiJd~WyJ
               5MXNWVTV3ZGZKYWhWZGd3UGdTN1JRIiwgImJhdGNoTnVtYmVyIiwgIjE2MjYzODI3MzY
               iXQ~WyJIYlE0WDhzclZXM1FEeG5JSmRxeU9BIiwgImhlYWx0aFByb2Zlc3Npb25hbCIs
               ICI4ODMxMTAwMDAwMTUzNzYiXQ~
            """.replace("\\s".toRegex(), "")

        val sdJwtRfcAppendixA4SdJwtKbCompactSerialization =
            """
               eyJhbGciOiAiRVMyNTYiLCAidHlwIjogImV4YW1wbGUrc2Qtand0In0.eyJAY29udGV4
               dCI6IFsiaHR0cHM6Ly93d3cudzMub3JnLzIwMTgvY3JlZGVudGlhbHMvdjEiLCAiaHR0
               cHM6Ly93M2lkLm9yZy92YWNjaW5hdGlvbi92MSJdLCAidHlwZSI6IFsiVmVyaWZpYWJs
               ZUNyZWRlbnRpYWwiLCAiVmFjY2luYXRpb25DZXJ0aWZpY2F0ZSJdLCAiaXNzdWVyIjog
               Imh0dHBzOi8vZXhhbXBsZS5jb20vaXNzdWVyIiwgImlzc3VhbmNlRGF0ZSI6ICIyMDIz
               LTAyLTA5VDExOjAxOjU5WiIsICJleHBpcmF0aW9uRGF0ZSI6ICIyMDI4LTAyLTA4VDEx
               OjAxOjU5WiIsICJuYW1lIjogIkNPVklELTE5IFZhY2NpbmF0aW9uIENlcnRpZmljYXRl
               IiwgImRlc2NyaXB0aW9uIjogIkNPVklELTE5IFZhY2NpbmF0aW9uIENlcnRpZmljYXRl
               IiwgImNyZWRlbnRpYWxTdWJqZWN0IjogeyJfc2QiOiBbIjFWX0stOGxEUThpRlhCRlhi
               Wlk5ZWhxUjRIYWJXQ2k1VDB5Ykl6WlBld3ciLCAiSnpqTGd0UDI5ZFAtQjN0ZDEyUDY3
               NGdGbUsyenk4MUhNdEJnZjZDSk5XZyIsICJSMmZHYmZBMDdaX1lsa3FtTlp5bWExeHl5
               eDFYc3RJaVM2QjFZYmwySlo0IiwgIlRDbXpybDdLMmdldl9kdTdwY01JeXpSTEhwLVll
               Zy1GbF9jeHRyVXZQeGciLCAiVjdrSkJMSzc4VG1WRE9tcmZKN1p1VVBIdUtfMmNjN3la
               UmE0cVYxdHh3TSIsICJiMGVVc3ZHUC1PRERkRm9ZNE5semxYYzN0RHNsV0p0Q0pGNzVO
               dzhPal9nIiwgInpKS19lU01YandNOGRYbU1aTG5JOEZHTTA4ekozX3ViR2VFTUotNVRC
               eTAiXSwgInZhY2NpbmUiOiB7Il9zZCI6IFsiMWNGNWhMd2toTU5JYXFmV0pyWEk3Tk1X
               ZWRMLTlmNlkyUEE1MnlQalNaSSIsICJIaXk2V1d1ZUxENWJuMTYyOTh0UHY3R1hobWxk
               TURPVG5CaS1DWmJwaE5vIiwgIkxiMDI3cTY5MWpYWGwtakM3M3ZpOGViT2o5c214M0Mt
               X29nN2dBNFRCUUUiXSwgInR5cGUiOiAiVmFjY2luZSJ9LCAicmVjaXBpZW50IjogeyJf
               c2QiOiBbIjFsU1FCTlkyNHEwVGg2T0d6dGhxLTctNGw2Y0FheHJZWE9HWnBlV19sbkEi
               LCAiM256THE4MU0yb04wNndkdjFzaEh2T0VKVnhaNUtMbWREa0hFREpBQldFSSIsICJQ
               bjFzV2kwNkc0TEpybm4tX1JUMFJiTV9IVGR4blBKUXVYMmZ6V3ZfSk9VIiwgImxGOXV6
               ZHN3N0hwbEdMYzcxNFRyNFdPN01HSnphN3R0N1FGbGVDWDRJdHciXSwgInR5cGUiOiAi
               VmFjY2luZVJlY2lwaWVudCJ9LCAidHlwZSI6ICJWYWNjaW5hdGlvbkV2ZW50In0sICJf
               c2RfYWxnIjogInNoYS0yNTYiLCAiY25mIjogeyJqd2siOiB7Imt0eSI6ICJFQyIsICJj
               cnYiOiAiUC0yNTYiLCAieCI6ICJUQ0FFUjE5WnZ1M09IRjRqNFc0dmZTVm9ISVAxSUxp
               bERsczd2Q2VHZW1jIiwgInkiOiAiWnhqaVdXYlpNUUdIVldLVlE0aGJTSWlyc1ZmdWVj
               Q0U2dDRqVDlGMkhaUSJ9fX0.umiUzP5osIlBr2oY_qEEVRXDHTPgEsdc8vAgnSQUfgrY
               2WBvvnAdftbHGs496ZW8bsK096myVYIQJyplHinDEA~WyJQYzMzSk0yTGNoY1VfbEhnZ
               3ZfdWZRIiwgIm9yZGVyIiwgIjMvMyJd~WyJBSngtMDk1VlBycFR0TjRRTU9xUk9BIiwg
               ImRhdGVPZlZhY2NpbmF0aW9uIiwgIjIwMjEtMDYtMjNUMTM6NDA6MTJaIl0~WyIyR0xD
               NDJzS1F2ZUNmR2ZyeU5STjl3IiwgImF0Y0NvZGUiLCAiSjA3QlgwMyJd~WyJlbHVWNU9
               nM2dTTklJOEVZbnN4QV9BIiwgIm1lZGljaW5hbFByb2R1Y3ROYW1lIiwgIkNPVklELTE
               5IFZhY2NpbmUgTW9kZXJuYSJd~eyJhbGciOiAiRVMyNTYiLCAidHlwIjogImtiK2p3dC
               J9.eyJub25jZSI6ICIxMjM0NTY3ODkwIiwgImF1ZCI6ICJodHRwczovL3ZlcmlmaWVyL
               mV4YW1wbGUub3JnIiwgImlhdCI6IDE3NDg0NTQyNzEsICJzZF9oYXNoIjogIkdXNDk4U
               1hJWGxWTjh4SGRYM01kUWZaQ29LTTN3S2lxOTBSLWFYX2RKNGcifQ.6Il5IDkqqpYr-b
               InghcdoFKFghm1xNYCGWR9OdiwovqHVM3KQCuVQzvuQjycriTg8IKE8Ue54L-uKuYxPl
               yRAg
            """.replace("\\s".toRegex(), "")

    }

    @Test
    fun testVerifysdJwtRfcSection51() {
        val sdJwt = SdJwt(sdJwtRfcSection51SdJwtCompactSerialization)

        assertEquals(
            """
                [
                  [
                    "2GLC42sKQveCfGfryNRN9w",
                    "given_name",
                    "John"
                  ],
                  [
                    "eluV5Og3gSNII8EYnsxA_A",
                    "family_name",
                    "Doe"
                  ],
                  [
                    "6Ij7tM-a5iVPGboS5tmvVA",
                    "email",
                    "johndoe@example.com"
                  ],
                  [
                    "eI8ZWm9QnKPpNPeNenHdhQ",
                    "phone_number",
                    "+1-202-555-0101"
                  ],
                  [
                    "Qg_O64zqAxe412a108iroA",
                    "phone_number_verified",
                    true
                  ],
                  [
                    "AJx-095VPrpTtN4QMOqROA",
                    "address",
                    {
                      "street_address": "123 Main St",
                      "locality": "Anytown",
                      "region": "Anystate",
                      "country": "US"
                    }
                  ],
                  [
                    "Pc33JM2LchcU_lHggv_ufQ",
                    "birthdate",
                    "1940-01-01"
                  ],
                  [
                    "G02NSrQfjFXQ7Io09syajA",
                    "updated_at",
                    1570000000
                  ],
                  [
                    "lklxF5jMYlGTPUovMNIvCA",
                    "US"
                  ],
                  [
                    "nPuoQnkRFq3BIeAm7AnXFA",
                    "DE"
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(sdJwt.disclosures)
        )

        assertEquals(
            """
                {
                  "iss": "https://issuer.example.com",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "sub": "user_42",
                  "nationalities": [
                    "US",
                    "DE"
                  ],
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  },
                  "updated_at": 1570000000,
                  "email": "johndoe@example.com",
                  "phone_number": "+1-202-555-0101",
                  "family_name": "Doe",
                  "phone_number_verified": true,
                  "address": {
                    "street_address": "123 Main St",
                    "locality": "Anytown",
                    "region": "Anystate",
                    "country": "US"
                  },
                  "birthdate": "1940-01-01",
                  "given_name": "John"
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwt.verify(sdJwtRfcIssuerKey))
        )
    }

    @Test
    fun testVerifySdJwtRfcAppendixA1() {
        val sdJwt = SdJwt(sdJwtRfcAppendixA1SdJwtCompactSerialization)
        assertEquals(
            """
                {
                  "_sd": [
                    "C9inp6YoRaEXR427zYJP7Qrk1WH_8bdwOA_YUrUnGQU",
                    "Kuet1yAa0HIQvYnOVd59hcViO9Ug6J2kSfqYRBeowvE",
                    "MMldOFFzB2d0umlmpTIaGerhWdU_PpYfLvKhh_f_9aY",
                    "X6ZAYOII2vPN40V7xExZwVwz7yRmLNcVwt5DL8RLv4g",
                    "Y34zmIo0QLLOtdMpXGwjBgLvr17yEhhYT0FGofR-aIE",
                    "fyGp0WTwwPv2JDQln1lSiaeobZsMWA10bQ5989-9DTs",
                    "ommFAicVT8LGHCB0uywx7fYuo3MHYKO15cz-RZEYM5Q",
                    "s0BKYsLWxQQeU8tVlltM7MKsIRTrEIa1PkJmqxBBf5U"
                  ],
                  "iss": "https://issuer.example.com",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "address": {
                    "_sd": [
                      "6aUhzYhZ7SJ1kVmagQAO3u2ETN2CC1aHheZpKnaF0_E",
                      "AzLlFobkJ2xiaupREPyoJz-9-NSldB6Cgjr7fUyoHzg",
                      "PzzcVu0qbMuBGSjulfewzkesD9zutOExn5EWNwkrQ-k",
                      "b2Dkw0jcIF9rGg8_PF8ZcvncW7zwZj5ryBWvXfrpzek",
                      "cPYJHIZ8Vu-f9CCyVub2UfgEk8jvvXezwK1p_JneeXQ",
                      "glT3hrSU7fSWgwF5UDZmWwBTw32gnUldIhi8hGVCaV4",
                      "rvJd6iq6T5ejmsBMoGwuNXh9qAAFATAci40oidEeVsA",
                      "uNHoWYhXsZhVJCNE2Dqy-zqt7t69gJKy5QaFv7GrMX4"
                    ]
                  },
                  "_sd_alg": "sha-256"
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwt.jwtBody)
        )
        assertEquals(
            """
                {
                  "iss": "https://issuer.example.com",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "address": {
                    "region": "港区",
                    "country": "JP"
                  }
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwt.verify(sdJwtRfcIssuerKey))
        )
    }

    @Test
    fun testVerifySdJwtRfcAppendixA2() {
        val sdJwt = SdJwt(sdJwtRfcAppendixA2SdJwtCompactSerialization)
        assertEquals(
            """
                {
                  "_sd": [
                    "-aSznId9mWM8ocuQolCllsxVggq1-vHW4OtnhUtVmWw",
                    "IKbrYNn3vA7WEFrysvbdBJjDDU_EvQIr0W18vTRpUSg",
                    "otkxuT14nBiwzNJ3MPaOitOl9pVnXOaEHal_xkyNfKI"
                  ],
                  "iss": "https://issuer.example.com",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "verified_claims": {
                    "verification": {
                      "_sd": [
                        "7h4UE9qScvDKodXVCuoKfKBJpVBfXMF_TmAGVaZe3Sc",
                        "vTwe3raHIFYgFA3xaUD2aMxFz5oDo8iBu05qKlOg9Lw"
                      ],
                      "trust_framework": "de_aml",
                      "evidence": [
                        {
                          "...": "tYJ0TDucyZZCRMbROG4qRO5vkPSFRxFhUELc18CSl3k"
                        }
                      ]
                    },
                    "claims": {
                      "_sd": [
                        "RiOiCn6_w5ZHaadkQMrcQJf0Jte5RwurRs54231DTlo",
                        "S_498bbpKzB6Eanftss0xc7cOaoneRr3pKr7NdRmsMo",
                        "WNA-UNK7F_zhsAb9syWO6IIQ1uHlTmOU8r8CvJ0cIMk",
                        "Wxh_sV3iRH9bgrTBJi-aYHNCLt-vjhX1sd-igOf_9lk",
                        "_O-wJiH3enSB4ROHntToQT8JmLtz-mhO2f1c89XoerQ",
                        "hvDXhwmGcJQsBCA2OtjuLAcwAMpDsaU0nkovcKOqWNE"
                      ]
                    }
                  },
                  "_sd_alg": "sha-256"
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwt.jwtBody)
        )

        assertEquals(
            """
                [
                  [
                    "2GLC42sKQveCfGfryNRN9w",
                    "time",
                    "2012-04-23T18:25Z"
                  ],
                  [
                    "Pc33JM2LchcU_lHggv_ufQ",
                    {
                      "_sd": [
                        "9wpjVPWuD7PK0nsQDL8B06lmdgV3LVybhHydQpTNyLI",
                        "G5EnhOAOoU9X_6QMNvzFXjpEA_Rc-AEtm1bG_wcaKIk",
                        "IhwFrWUB63RcZq9yvgZ0XPc7Gowh3O2kqXeBIswg1B4",
                        "WpxQ4HSoEtcTmCCKOeDslB_emucYLz2oO8oHNr1bEVQ"
                      ]
                    }
                  ],
                  [
                    "eI8ZWm9QnKPpNPeNenHdhQ",
                    "method",
                    "pipp"
                  ],
                  [
                    "G02NSrQfjFXQ7Io09syajA",
                    "given_name",
                    "Max"
                  ],
                  [
                    "lklxF5jMYlGTPUovMNIvCA",
                    "family_name",
                    "Müller"
                  ],
                  [
                    "y1sVU5wdfJahVdgwPgS7RQ",
                    "address",
                    {
                      "locality": "Maxstadt",
                      "postal_code": "12344",
                      "country": "DE",
                      "street_address": "Weidenstraße 22"
                    }
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(sdJwt.disclosures)
        )

        assertEquals(
            """
               {
                 "iss": "https://issuer.example.com",
                 "iat": 1683000000,
                 "exp": 1883000000,
                 "verified_claims": {
                   "verification": {
                     "trust_framework": "de_aml",
                     "evidence": [
                       {
                         "method": "pipp"
                       }
                     ],
                     "time": "2012-04-23T18:25Z"
                   },
                   "claims": {
                     "given_name": "Max",
                     "family_name": "Müller",
                     "address": {
                       "locality": "Maxstadt",
                       "postal_code": "12344",
                       "country": "DE",
                       "street_address": "Weidenstraße 22"
                     }
                   }
                 }
               }
            """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwt.verify(sdJwtRfcIssuerKey))
        )
    }

    @Test
    fun testVerifySdJwtRfcAppendixA3() {
        val sdJwt = SdJwt(sdJwtRfcAppendixA3SdJwtCompactSerialization)
        assertEquals(
            """
                {
                  "_sd": [
                    "0HZmnSIPz337kSWe7C34l--88gzJi-eBJ2Vz_HJwATg",
                    "1Crn03WmUeRWp4zwPvvCKXl9ZaQp-cdQV_gHdaGSWow",
                    "2r009dzvHuVrWrRXT5kJMmHnqEHHnWe0MLVZw8PATB8",
                    "6ZNISDst62ymlrOAkadjdD5ZulT5A299J78SLhM__Os",
                    "78jg77-GYBeX8IQfoELPyL0DYPdmfZo0JgViV0_lKCM",
                    "90CT8AaBPbn5X8nRXkesju1i0BqhWqZ3wqD4jF-qDGk",
                    "I00fcFUoDXCucp5yy2ujqPssDVGaWNiUliNz_awD0gc",
                    "KjAXgAA9N5WHEDtRIh4u5Mn1ZsWixhhWAiX-A4QiwgA",
                    "Lai6IU6d7GQagXR7AvGTrnXgSld3z8EIg_fv3fOZ1Wg",
                    "LezjabRqiZOXzEYmVZf8RMi9xAkd3_M1LZ8U7E4s3u4",
                    "RTz3qTmFNHbpWrrOMZS41F474kFqRv3vIPqth6PUhlM",
                    "W14XHbUffzuW4IFMjpSTb1melWxUWf4N_o2ldkkIqc8",
                    "WTpI7RcM3gxZruRpXzezSbkbOr93PVFvWx8woJ3j1cE",
                    "_ohJVIQIBsU4updNS4_w4Kb1MHqJ0L9qLGshWq6JXQs",
                    "y50czc0ISChy_bsba1dMoUuAOQ5AMmOSfGoEe81v1FU"
                  ],
                  "iss": "https://pid-issuer.bund.de.example",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "vct": "urn:eudi:pid:de:1",
                  "_sd_alg": "sha-256",
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  }
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwt.jwtBody)
        )

        assertEquals(
            """
                [
                  [
                    "2GLC42sKQveCfGfryNRN9w",
                    "given_name",
                    "Erika"
                  ],
                  [
                    "eluV5Og3gSNII8EYnsxA_A",
                    "family_name",
                    "Mustermann"
                  ],
                  [
                    "6Ij7tM-a5iVPGboS5tmvVA",
                    "birthdate",
                    "1963-08-12"
                  ],
                  [
                    "eI8ZWm9QnKPpNPeNenHdhQ",
                    "street_address",
                    "Heidestraße 17"
                  ],
                  [
                    "Qg_O64zqAxe412a108iroA",
                    "locality",
                    "Köln"
                  ],
                  [
                    "AJx-095VPrpTtN4QMOqROA",
                    "postal_code",
                    "51147"
                  ],
                  [
                    "Pc33JM2LchcU_lHggv_ufQ",
                    "country",
                    "DE"
                  ],
                  [
                    "G02NSrQfjFXQ7Io09syajA",
                    "address",
                    {
                      "_sd": [
                        "ALZERsSn5WNiEXdCksW8I5qQw3_NpAnRqpSAZDudgw8",
                        "D__W_uYcvRz3tvUnIJvBDHiTc7C__qHd0xNKwIs_w9k",
                        "eBpCXU1J5dhH2g4t8QYNW5ExS9AxUVblUodoLYoPho0",
                        "xOPy9-gJALK6UbWKFLR85cOByUD3AbNwFg3I3YfQE_I"
                      ]
                    }
                  ],
                  [
                    "lklxF5jMYlGTPUovMNIvCA",
                    "nationalities",
                    [
                      "DE"
                    ]
                  ],
                  [
                    "nPuoQnkRFq3BIeAm7AnXFA",
                    "sex",
                    2
                  ],
                  [
                    "5bPs1IquZNa0hkaFzzzZNw",
                    "birth_family_name",
                    "Gabler"
                  ],
                  [
                    "5a2W0_NrlEZzfqmk_7Pq-w",
                    "locality",
                    "Berlin"
                  ],
                  [
                    "y1sVU5wdfJahVdgwPgS7RQ",
                    "country",
                    "DE"
                  ],
                  [
                    "HbQ4X8srVW3QDxnIJdqyOA",
                    "place_of_birth",
                    {
                      "_sd": [
                        "KUViaaLnY5jSML90G29OOLENPbbXfhSjSPMjZaGkxAE",
                        "YbsT0S76VqXCVsd1jUSlwKPDgmALeB1uZclFHXf-USQ"
                      ]
                    }
                  ],
                  [
                    "C9GSoujviJquEgYfojCb1A",
                    "12",
                    true
                  ],
                  [
                    "kx5kF17V-x0JmwUx9vgvtw",
                    "14",
                    true
                  ],
                  [
                    "H3o1uswP760Fi2yeGdVCEQ",
                    "16",
                    true
                  ],
                  [
                    "OBKlTVlvLg-AdwqYGbP8ZA",
                    "18",
                    true
                  ],
                  [
                    "M0Jb57t41ubrkSuyrDT3xA",
                    "21",
                    true
                  ],
                  [
                    "DsmtKNgpV4dAHpjrcaosAw",
                    "65",
                    false
                  ],
                  [
                    "eK5o5pHfgupPpltj1qhAJw",
                    "age_equal_or_over",
                    {
                      "_sd": [
                        "1tEiyzPRYOKsf7SsYGMgPZKsOT1lQZRxHXA0r5_Bwkk",
                        "CVKnly5P90yJs3EwtxQiOtUczaXCYNA4IczRaohrMDg",
                        "a44-g2Gr8_3AmJw2XZ8kI1y0Qz_ze9iOcW2W3RLpXGg",
                        "gkvy0FuvBBvj0hs2ZNwxcqOlf8mu2-kCE7-Nb2QxuBU",
                        "hrY4HnmF5b5JwC9eTzaFCUceIQAaIdhrqUXQNCWbfZI",
                        "y6SFrVFRyq50IbRJviTZqqjQWz0tLiuCmMeO0KqazGI"
                      ]
                    }
                  ],
                  [
                    "j7ADdb0UVb0Li0ciPcP0ew",
                    "age_in_years",
                    62
                  ],
                  [
                    "WpxJrFuX8uSi2p4ht09jvw",
                    "age_birth_year",
                    1963
                  ],
                  [
                    "atSmFACYMbJVKD05o3JgtQ",
                    "issuance_date",
                    "2020-03-11"
                  ],
                  [
                    "4KyR32oIZt-zkWvFqbULKg",
                    "expiry_date",
                    "2030-03-12"
                  ],
                  [
                    "chBCsyhyh-J86I-awQDiCQ",
                    "issuing_authority",
                    "DE"
                  ],
                  [
                    "flNP1ncMz9Lg-c9qMIz_9g",
                    "issuing_country",
                    "DE"
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(sdJwt.disclosures)
        )

        assertEquals(
            """
                {
                  "iss": "https://pid-issuer.bund.de.example",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "vct": "urn:eudi:pid:de:1",
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  },
                  "given_name": "Erika",
                  "place_of_birth": {
                    "locality": "Berlin",
                    "country": "DE"
                  },
                  "age_equal_or_over": {
                    "21": true,
                    "18": true,
                    "65": false,
                    "12": true,
                    "16": true,
                    "14": true
                  },
                  "issuing_authority": "DE",
                  "expiry_date": "2030-03-12",
                  "sex": 2,
                  "family_name": "Mustermann",
                  "birth_family_name": "Gabler",
                  "birthdate": "1963-08-12",
                  "age_birth_year": 1963,
                  "address": {
                    "street_address": "Heidestraße 17",
                    "locality": "Köln",
                    "country": "DE",
                    "postal_code": "51147"
                  },
                  "issuance_date": "2020-03-11",
                  "age_in_years": 62,
                  "issuing_country": "DE",
                  "nationalities": [
                    "DE"
                  ]
                }
               """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwt.verify(sdJwtRfcIssuerKey))
        )
    }

    @Test
    fun testVerifySdJwtRfcAppendixA3KeyBinding() {
        val sdJwtKb = SdJwtKb(sdJwtRfcAppendixA3SdJwtKbCompactSerialization)
        assertEquals(
            """
                {
                  "_sd": [
                    "0HZmnSIPz337kSWe7C34l--88gzJi-eBJ2Vz_HJwATg",
                    "1Crn03WmUeRWp4zwPvvCKXl9ZaQp-cdQV_gHdaGSWow",
                    "2r009dzvHuVrWrRXT5kJMmHnqEHHnWe0MLVZw8PATB8",
                    "6ZNISDst62ymlrOAkadjdD5ZulT5A299J78SLhM__Os",
                    "78jg77-GYBeX8IQfoELPyL0DYPdmfZo0JgViV0_lKCM",
                    "90CT8AaBPbn5X8nRXkesju1i0BqhWqZ3wqD4jF-qDGk",
                    "I00fcFUoDXCucp5yy2ujqPssDVGaWNiUliNz_awD0gc",
                    "KjAXgAA9N5WHEDtRIh4u5Mn1ZsWixhhWAiX-A4QiwgA",
                    "Lai6IU6d7GQagXR7AvGTrnXgSld3z8EIg_fv3fOZ1Wg",
                    "LezjabRqiZOXzEYmVZf8RMi9xAkd3_M1LZ8U7E4s3u4",
                    "RTz3qTmFNHbpWrrOMZS41F474kFqRv3vIPqth6PUhlM",
                    "W14XHbUffzuW4IFMjpSTb1melWxUWf4N_o2ldkkIqc8",
                    "WTpI7RcM3gxZruRpXzezSbkbOr93PVFvWx8woJ3j1cE",
                    "_ohJVIQIBsU4updNS4_w4Kb1MHqJ0L9qLGshWq6JXQs",
                    "y50czc0ISChy_bsba1dMoUuAOQ5AMmOSfGoEe81v1FU"
                  ],
                  "iss": "https://pid-issuer.bund.de.example",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "vct": "urn:eudi:pid:de:1",
                  "_sd_alg": "sha-256",
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  }
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwtKb.sdJwt.jwtBody)
        )

        assertEquals(
            """
                [
                  [
                    "eK5o5pHfgupPpltj1qhAJw",
                    "age_equal_or_over",
                    {
                      "_sd": [
                        "1tEiyzPRYOKsf7SsYGMgPZKsOT1lQZRxHXA0r5_Bwkk",
                        "CVKnly5P90yJs3EwtxQiOtUczaXCYNA4IczRaohrMDg",
                        "a44-g2Gr8_3AmJw2XZ8kI1y0Qz_ze9iOcW2W3RLpXGg",
                        "gkvy0FuvBBvj0hs2ZNwxcqOlf8mu2-kCE7-Nb2QxuBU",
                        "hrY4HnmF5b5JwC9eTzaFCUceIQAaIdhrqUXQNCWbfZI",
                        "y6SFrVFRyq50IbRJviTZqqjQWz0tLiuCmMeO0KqazGI"
                      ]
                    }
                  ],
                  [
                    "OBKlTVlvLg-AdwqYGbP8ZA",
                    "18",
                    true
                  ],
                  [
                    "lklxF5jMYlGTPUovMNIvCA",
                    "nationalities",
                    [
                      "DE"
                    ]
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(sdJwtKb.sdJwt.disclosures)
        )

        assertEquals(
            """
               {
                 "iss": "https://pid-issuer.bund.de.example",
                 "iat": 1683000000,
                 "exp": 1883000000,
                 "vct": "urn:eudi:pid:de:1",
                 "cnf": {
                   "jwk": {
                     "kty": "EC",
                     "crv": "P-256",
                     "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                     "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                   }
                 },
                 "age_equal_or_over": {
                   "18": true
                 },
                 "nationalities": [
                   "DE"
                 ]
               }
                  """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwtKb.verify(
                issuerKey = sdJwtRfcIssuerKey,
                checkNonce = { nonce -> nonce == "1234567890" },
                checkAudience = { audience -> audience == "https://verifier.example.org" },
                checkCreationTime = { creationTime -> creationTime.toEpochMilliseconds() == 1748454271000L }
            ))
        )

        // Negative tests to check that callbacks are properly evaluated
        assertFailsWith(IllegalStateException::class) {
            sdJwtKb.verify(
                issuerKey = sdJwtRfcIssuerKey,
                checkNonce = { false },
                checkAudience = { audience -> audience == "https://verifier.example.org" },
                checkCreationTime = { creationTime -> creationTime.toEpochMilliseconds() == 1748454271000L }
            )
        }.let { error -> assertEquals("Failed verification of nonce", error.message) }
        assertFailsWith(IllegalStateException::class) {
            sdJwtKb.verify(
                issuerKey = sdJwtRfcIssuerKey,
                checkNonce = { nonce -> nonce == "1234567890" },
                checkAudience = { false },
                checkCreationTime = { creationTime -> creationTime.toEpochMilliseconds() == 1748454271000L }
            )
        }.let { error -> assertEquals("Failed verification of audience", error.message) }
        assertFailsWith(IllegalStateException::class) {
            sdJwtKb.verify(
                issuerKey = sdJwtRfcIssuerKey,
                checkNonce = { nonce -> nonce == "1234567890" },
                checkAudience = { audience -> audience == "https://verifier.example.org" },
                checkCreationTime = { false }
            )
        }.let { error -> assertEquals("Failed verification of creationTime", error.message) }
    }

    @Test
    fun testVerifySdJwtRfcAppendixA4() {
        val sdJwt = SdJwt(sdJwtRfcAppendixA4SdJwtCompactSerialization)
        assertEquals(
            """
                {
                  "@context": [
                    "https://www.w3.org/2018/credentials/v1",
                    "https://w3id.org/vaccination/v1"
                  ],
                  "type": [
                    "VerifiableCredential",
                    "VaccinationCertificate"
                  ],
                  "issuer": "https://example.com/issuer",
                  "issuanceDate": "2023-02-09T11:01:59Z",
                  "expirationDate": "2028-02-08T11:01:59Z",
                  "name": "COVID-19 Vaccination Certificate",
                  "description": "COVID-19 Vaccination Certificate",
                  "credentialSubject": {
                    "_sd": [
                      "1V_K-8lDQ8iFXBFXbZY9ehqR4HabWCi5T0ybIzZPeww",
                      "JzjLgtP29dP-B3td12P674gFmK2zy81HMtBgf6CJNWg",
                      "R2fGbfA07Z_YlkqmNZyma1xyyx1XstIiS6B1Ybl2JZ4",
                      "TCmzrl7K2gev_du7pcMIyzRLHp-Yeg-Fl_cxtrUvPxg",
                      "V7kJBLK78TmVDOmrfJ7ZuUPHuK_2cc7yZRa4qV1txwM",
                      "b0eUsvGP-ODDdFoY4NlzlXc3tDslWJtCJF75Nw8Oj_g",
                      "zJK_eSMXjwM8dXmMZLnI8FGM08zJ3_ubGeEMJ-5TBy0"
                    ],
                    "vaccine": {
                      "_sd": [
                        "1cF5hLwkhMNIaqfWJrXI7NMWedL-9f6Y2PA52yPjSZI",
                        "Hiy6WWueLD5bn16298tPv7GXhmldMDOTnBi-CZbphNo",
                        "Lb027q691jXXl-jC73vi8ebOj9smx3C-_og7gA4TBQE"
                      ],
                      "type": "Vaccine"
                    },
                    "recipient": {
                      "_sd": [
                        "1lSQBNY24q0Th6OGzthq-7-4l6cAaxrYXOGZpeW_lnA",
                        "3nzLq81M2oN06wdv1shHvOEJVxZ5KLmdDkHEDJABWEI",
                        "Pn1sWi06G4LJrnn-_RT0RbM_HTdxnPJQuX2fzWv_JOU",
                        "lF9uzdsw7HplGLc714Tr4WO7MGJza7tt7QFleCX4Itw"
                      ],
                      "type": "VaccineRecipient"
                    },
                    "type": "VaccinationEvent"
                  },
                  "_sd_alg": "sha-256",
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  }
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwt.jwtBody)
        )

        assertEquals(
            """
                [
                  [
                    "2GLC42sKQveCfGfryNRN9w",
                    "atcCode",
                    "J07BX03"
                  ],
                  [
                    "eluV5Og3gSNII8EYnsxA_A",
                    "medicinalProductName",
                    "COVID-19 Vaccine Moderna"
                  ],
                  [
                    "6Ij7tM-a5iVPGboS5tmvVA",
                    "marketingAuthorizationHolder",
                    "Moderna Biotech"
                  ],
                  [
                    "eI8ZWm9QnKPpNPeNenHdhQ",
                    "nextVaccinationDate",
                    "2021-08-16T13:40:12Z"
                  ],
                  [
                    "Qg_O64zqAxe412a108iroA",
                    "countryOfVaccination",
                    "GE"
                  ],
                  [
                    "AJx-095VPrpTtN4QMOqROA",
                    "dateOfVaccination",
                    "2021-06-23T13:40:12Z"
                  ],
                  [
                    "Pc33JM2LchcU_lHggv_ufQ",
                    "order",
                    "3/3"
                  ],
                  [
                    "G02NSrQfjFXQ7Io09syajA",
                    "gender",
                    "Female"
                  ],
                  [
                    "lklxF5jMYlGTPUovMNIvCA",
                    "birthDate",
                    "1961-08-17"
                  ],
                  [
                    "nPuoQnkRFq3BIeAm7AnXFA",
                    "givenName",
                    "Marion"
                  ],
                  [
                    "5bPs1IquZNa0hkaFzzzZNw",
                    "familyName",
                    "Mustermann"
                  ],
                  [
                    "5a2W0_NrlEZzfqmk_7Pq-w",
                    "administeringCentre",
                    "Praxis Sommergarten"
                  ],
                  [
                    "y1sVU5wdfJahVdgwPgS7RQ",
                    "batchNumber",
                    "1626382736"
                  ],
                  [
                    "HbQ4X8srVW3QDxnIJdqyOA",
                    "healthProfessional",
                    "883110000015376"
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(sdJwt.disclosures)
        )

        assertEquals(
            """
                {
                  "@context": [
                    "https://www.w3.org/2018/credentials/v1",
                    "https://w3id.org/vaccination/v1"
                  ],
                  "type": [
                    "VerifiableCredential",
                    "VaccinationCertificate"
                  ],
                  "issuer": "https://example.com/issuer",
                  "issuanceDate": "2023-02-09T11:01:59Z",
                  "expirationDate": "2028-02-08T11:01:59Z",
                  "name": "COVID-19 Vaccination Certificate",
                  "description": "COVID-19 Vaccination Certificate",
                  "credentialSubject": {
                    "vaccine": {
                      "type": "Vaccine",
                      "atcCode": "J07BX03",
                      "medicinalProductName": "COVID-19 Vaccine Moderna",
                      "marketingAuthorizationHolder": "Moderna Biotech"
                    },
                    "recipient": {
                      "type": "VaccineRecipient",
                      "familyName": "Mustermann",
                      "gender": "Female",
                      "birthDate": "1961-08-17",
                      "givenName": "Marion"
                    },
                    "type": "VaccinationEvent",
                    "healthProfessional": "883110000015376",
                    "countryOfVaccination": "GE",
                    "nextVaccinationDate": "2021-08-16T13:40:12Z",
                    "administeringCentre": "Praxis Sommergarten",
                    "batchNumber": "1626382736",
                    "order": "3/3",
                    "dateOfVaccination": "2021-06-23T13:40:12Z"
                  },
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  }
                }
               """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwt.verify(sdJwtRfcIssuerKey))
        )
    }

    @Test
    fun testVerifySdJwtRfcAppendixA4KeyBinding() {
        val sdJwtKb = SdJwtKb(sdJwtRfcAppendixA4SdJwtKbCompactSerialization)
        assertEquals(
            """
                {
                  "@context": [
                    "https://www.w3.org/2018/credentials/v1",
                    "https://w3id.org/vaccination/v1"
                  ],
                  "type": [
                    "VerifiableCredential",
                    "VaccinationCertificate"
                  ],
                  "issuer": "https://example.com/issuer",
                  "issuanceDate": "2023-02-09T11:01:59Z",
                  "expirationDate": "2028-02-08T11:01:59Z",
                  "name": "COVID-19 Vaccination Certificate",
                  "description": "COVID-19 Vaccination Certificate",
                  "credentialSubject": {
                    "_sd": [
                      "1V_K-8lDQ8iFXBFXbZY9ehqR4HabWCi5T0ybIzZPeww",
                      "JzjLgtP29dP-B3td12P674gFmK2zy81HMtBgf6CJNWg",
                      "R2fGbfA07Z_YlkqmNZyma1xyyx1XstIiS6B1Ybl2JZ4",
                      "TCmzrl7K2gev_du7pcMIyzRLHp-Yeg-Fl_cxtrUvPxg",
                      "V7kJBLK78TmVDOmrfJ7ZuUPHuK_2cc7yZRa4qV1txwM",
                      "b0eUsvGP-ODDdFoY4NlzlXc3tDslWJtCJF75Nw8Oj_g",
                      "zJK_eSMXjwM8dXmMZLnI8FGM08zJ3_ubGeEMJ-5TBy0"
                    ],
                    "vaccine": {
                      "_sd": [
                        "1cF5hLwkhMNIaqfWJrXI7NMWedL-9f6Y2PA52yPjSZI",
                        "Hiy6WWueLD5bn16298tPv7GXhmldMDOTnBi-CZbphNo",
                        "Lb027q691jXXl-jC73vi8ebOj9smx3C-_og7gA4TBQE"
                      ],
                      "type": "Vaccine"
                    },
                    "recipient": {
                      "_sd": [
                        "1lSQBNY24q0Th6OGzthq-7-4l6cAaxrYXOGZpeW_lnA",
                        "3nzLq81M2oN06wdv1shHvOEJVxZ5KLmdDkHEDJABWEI",
                        "Pn1sWi06G4LJrnn-_RT0RbM_HTdxnPJQuX2fzWv_JOU",
                        "lF9uzdsw7HplGLc714Tr4WO7MGJza7tt7QFleCX4Itw"
                      ],
                      "type": "VaccineRecipient"
                    },
                    "type": "VaccinationEvent"
                  },
                  "_sd_alg": "sha-256",
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  }
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwtKb.sdJwt.jwtBody)
        )

        assertEquals(
            """
                [
                  [
                    "Pc33JM2LchcU_lHggv_ufQ",
                    "order",
                    "3/3"
                  ],
                  [
                    "AJx-095VPrpTtN4QMOqROA",
                    "dateOfVaccination",
                    "2021-06-23T13:40:12Z"
                  ],
                  [
                    "2GLC42sKQveCfGfryNRN9w",
                    "atcCode",
                    "J07BX03"
                  ],
                  [
                    "eluV5Og3gSNII8EYnsxA_A",
                    "medicinalProductName",
                    "COVID-19 Vaccine Moderna"
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(sdJwtKb.sdJwt.disclosures)
        )

        assertEquals(
            """
                {
                  "@context": [
                    "https://www.w3.org/2018/credentials/v1",
                    "https://w3id.org/vaccination/v1"
                  ],
                  "type": [
                    "VerifiableCredential",
                    "VaccinationCertificate"
                  ],
                  "issuer": "https://example.com/issuer",
                  "issuanceDate": "2023-02-09T11:01:59Z",
                  "expirationDate": "2028-02-08T11:01:59Z",
                  "name": "COVID-19 Vaccination Certificate",
                  "description": "COVID-19 Vaccination Certificate",
                  "credentialSubject": {
                    "vaccine": {
                      "type": "Vaccine",
                      "atcCode": "J07BX03",
                      "medicinalProductName": "COVID-19 Vaccine Moderna"
                    },
                    "recipient": {
                      "type": "VaccineRecipient"
                    },
                    "type": "VaccinationEvent",
                    "order": "3/3",
                    "dateOfVaccination": "2021-06-23T13:40:12Z"
                  },
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  }
                }
               """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwtKb.verify(
                issuerKey = sdJwtRfcIssuerKey,
                checkNonce = { nonce -> nonce == "1234567890" },
                checkAudience = { audience -> audience == "https://verifier.example.org" },
                checkCreationTime = { creationTime -> creationTime.toEpochMilliseconds() == 1748454271000L }
            ))
        )
    }

    @Test
    fun testVerifyOther() {
        val sdJwt = SdJwt(
            compactSerialization =
                "eyJ4NWMiOlsiTUlJQzR6Q0NBb21nQXdJQkFnSUJEakFLQmdncWhrak9QUVFEQWpCak1Rc3dDUVlEVlFRR0V3SkVSVEVQTUEwR0ExVUVCd3dHUW1WeWJHbHVNUjB3R3dZRFZRUUtEQlJDZFc1a1pYTmtjblZqYTJWeVpXa2dSMjFpU0RFS01BZ0dBMVVFQ3d3QlNURVlNQllHQTFVRUF3d1BTVVIxYm1sdmJpQlVaWE4wSUVOQk1CNFhEVEkxTURFeU56RTFOVGswTWxvWERUSTJNRE13TXpFMU5UazBNbG93VGpFTE1Ba0dBMVVFQmhNQ1JFVXhIVEFiQmdOVkJBb01GRUoxYm1SbGMyUnlkV05yWlhKbGFTQkhiV0pJTVFvd0NBWURWUVFMREFGSk1SUXdFZ1lEVlFRRERBdFVaWE4wSUVsemMzVmxjakJaTUJNR0J5cUdTTTQ5QWdFR0NDcUdTTTQ5QXdFSEEwSUFCR2NFN0pPVU92VUwwYmZ0eXdzSEc0UWJKcDZ3c0ZhZ3E4NUpScmlXbUxzS1pjc1haS0I0QU52cW1YcUxocjJYN0JnS2ExOERCSEw3bllTMk9ONXlHdVdqZ2dGQk1JSUJQVEFkQmdOVkhRNEVGZ1FVNlR3dDV5eWNmMWpLOE1GQlpRbU5JbWR2Y3M0d0RBWURWUjBUQVFIL0JBSXdBREFPQmdOVkhROEJBZjhFQkFNQ0I0QXdnZHdHQTFVZEVRU0IxRENCMFlJVFpHVnRieTVpWkhJdGNISnZkRzkwZVhCbGM0WTBhSFIwY0hNNkx5OWtaVzF2TG1Ka2NpMXdjbTkwYjNSNWNHVnpMMmx6YzNWbGNpOTFibWwyWlhKemFYUjVibVYwZDI5eWE0WW1hSFIwY0hNNkx5OWtaVzF2TG1Ka2NpMXdjbTkwYjNSNWNHVnpMMmx6YzNWbGNpOXdhV1NHTEdoMGRIQnpPaTh2WkdWdGJ5NWlaSEl0Y0hKdmRHOTBlWEJsY3k5cGMzTjFaWEl2WW5WdVpHVnpZVzEwaGk1b2RIUndjem92TDJSbGJXOHVZbVJ5TFhCeWIzUnZkSGx3WlhNdmFYTnpkV1Z5TDJGeVltVnBkR2RsWW1WeU1COEdBMVVkSXdRWU1CYUFGRStXNno3YWpUdW1leCtZY0Zib05yVmVDMnRSTUFvR0NDcUdTTTQ5QkFNQ0EwZ0FNRVVDSUFHVDE4RTRRdThhT012MWI5V1dYMmlNM2drWlRSck14MlB4RzRxYTREeWhBaUVBeFVTTmUrdVNzTkJCSXh3b2I2K0RKMnVjN21USnp5aGlJQ2ZaR0J4MjNPOD0iLCJNSUlDTFRDQ0FkU2dBd0lCQWdJVU1ZVUhoR0Q5aFUvYzBFbzZtVzhyamplSit0MHdDZ1lJS29aSXpqMEVBd0l3WXpFTE1Ba0dBMVVFQmhNQ1JFVXhEekFOQmdOVkJBY01Ca0psY214cGJqRWRNQnNHQTFVRUNnd1VRblZ1WkdWelpISjFZMnRsY21WcElFZHRZa2d4Q2pBSUJnTlZCQXNNQVVreEdEQVdCZ05WQkFNTUQwbEVkVzVwYjI0Z1ZHVnpkQ0JEUVRBZUZ3MHlNekEzTVRNd09USTFNamhhRncwek16QTNNVEF3T1RJMU1qaGFNR014Q3pBSkJnTlZCQVlUQWtSRk1ROHdEUVlEVlFRSERBWkNaWEpzYVc0eEhUQWJCZ05WQkFvTUZFSjFibVJsYzJSeWRXTnJaWEpsYVNCSGJXSklNUW93Q0FZRFZRUUxEQUZKTVJnd0ZnWURWUVFEREE5SlJIVnVhVzl1SUZSbGMzUWdRMEV3V1RBVEJnY3Foa2pPUFFJQkJnZ3Foa2pPUFFNQkJ3TkNBQVNFSHo4WWpyRnlUTkhHTHZPMTRFQXhtOXloOGJLT2drVXpZV2NDMWN2ckpuNUpnSFlITXhaYk5NTzEzRWgwRXIyNzM4UVFPZ2VSb1pNSVRhb2RrZk5TbzJZd1pEQWRCZ05WSFE0RUZnUVVUNWJyUHRxTk82WjdINWh3VnVnMnRWNExhMUV3SHdZRFZSMGpCQmd3Rm9BVVQ1YnJQdHFOTzZaN0g1aHdWdWcydFY0TGExRXdFZ1lEVlIwVEFRSC9CQWd3QmdFQi93SUJBREFPQmdOVkhROEJBZjhFQkFNQ0FZWXdDZ1lJS29aSXpqMEVBd0lEUndBd1JBSWdZMERlcmRDeHQ0ekdQWW44eU5yRHhJV0NKSHB6cTRCZGpkc1ZOMm8xR1JVQ0lCMEtBN2JHMUZWQjFJaUs4ZDU3UUFMK1BHOVg1bGRLRzdFa29BbWhXVktlIl0sImtpZCI6ImJjMGRlZGE0NTU1NGVjYzNlZTQzNjA5YmEyMDc4MzIwMWY5NGEwOTYiLCJ0eXAiOiJkYytzZC1qd3QiLCJhbGciOiJFUzI1NiJ9.eyJfc2QiOlsiODQ5T1hCSnktcHZfNTk4UVhWcVFmVEp3X0tIdjlzOGdna25VMzBVaDZjayIsIlB6cTRsLUdBU2tlTV9kM09tS1lnQ0tUelU4T1FlNjZZbnFKal9XcUtPTGMiLCJiX3FLZXRVT3ppRXdPRl9LYVEyR0xoNXo5d2huYUVjNmtFdEFDdHlqSnZ3IiwiakVhd1FHRUVjU2RJWDRWbUtRS0J1dHQxRWJPVGc0QS1wcXJVbXlvOHFsRSIsImphSUpMSDc5MTYySVEydmZONTRBZ011VWxCNmx0OV80N1NsczlwUDUtQWsiLCJubEl4OXQxOXlrcEkxSm1pTF9mcmV4X0xVTnpMcEFUYXVZX0VXd09ES2ljIiwicDlXbTQxLUNsTy1acXNSVnNucnNUc3JlUGpoWGRqMnJUbFdJa2dqOUNsMCIsInZqZVFoSmJfU1J6NXY3TjY0NEd1bkczTkZQRURRY3RLZFQwN215ZjVLRzgiXSwidmN0IjoidXJuOmV1ZGk6cGlkOjEiLCJfc2RfYWxnIjoic2hhLTI1NiIsImlzcyI6Imh0dHBzOi8vZGVtby5iZHItcHJvdG90eXBlcy9pc3N1ZXIvcGlkIiwiY25mIjp7Imp3ayI6eyJrdHkiOiJFQyIsImNydiI6IlAtMjU2IiwieCI6IjRsbElYWVpJLVIzSWppMm5wRDAwWmdZc3gtQmtKamY4bnFSeFVrRndDUTAiLCJ5IjoiVlRBMFdRSFo1el96NDJvQ2RFWHdCakYxblpUdHdyUnBYODNMazgweTE0ZyJ9fSwiZXhwIjoxNzUzNzg3NjY1LCJpYXQiOjE3NDYwMTE2NjUsImFnZV9lcXVhbF9vcl9vdmVyIjp7Il9zZCI6WyI4c2ZoOFJPaHlRV00tcmR6el9pb2xBYnZZY21ncFdjRHFNdUVSWnZ0NTFjIiwiVkFvc0czU2ZYb0hmUFVWTmgtaGlCRlNIbnl4MWRoM1FzZm9xcURwcS1aRSJdfX0.5KKKPiTNxNDzagKzYnaolyRciOZgOHFwub33BUWetUt8UatNQcX3nc87lRR6X1hzFY3p5gKWyP0BAtTX_mZyGA~WyIwSmRWenhqT1BmcGdETU9KOUNSWkxRIiwiZmFtaWx5X25hbWUiLCJNVVNURVJNQU5OIl0~WyI5UWFPdGVra193ZmxzZkFheFRNWVp3IiwiZ2l2ZW5fbmFtZSIsIkVSSUtBIl0~WyJQMzVoUEtIWlZ0bTVacnp3MTMwV0RBIiwiYmlydGhkYXRlIiwiMjAwNi0wMi0yOCJd~WyJXeExqYVZ2eHozem1NUkl6bm1SbVRBIiwiY291bnRyeSIsIkJFUkxJTiJd~WyJWdjM4ZVM3YTB1aXJCbWEydDAwaDh3IiwibG9jYWxpdHkiLCJCRVJMSU4iXQ~WyJNVGVhZVZkVmVKa243Tm1GOFNSdU53IiwicmVnaW9uIiwiQkVSTElOIl0~WyJRSkt6WktUVldqaHo4dFhuVWFGZTJBIiwicGxhY2Vfb2ZfYmlydGgiLHsiX3NkIjpbIjVMNkJiUGIxU2tGSWdvQUZ6VXFzRGJqeHVzN3V0RFVPUHgwZ3FadXVLYmMiLCJIanBVd3pjcXNCeEgtUFdmRnJqc2x0UzVmM3pWUExGU1liZjNPYUZ1OGprIiwiTmtkTEJzX180M0ROSDNKd09UNll2UDhxbUc1em9OMDJBbHZNbmJTcDF6byJdfV0~WyJlVm9qMTRPQ1pPN0VaYVZGcHQybTRRIiwibmF0aW9uYWxpdGllcyIsW11d~WyJUbTVITUY0cmhhY0ltNV91WGxKbkhnIiwiZGF0ZV9vZl9leHBpcnkiLCIyMDI2LTEyLTMxIl0~WyIxTURKcDhZV193Qnp1QktNa0hKSExBIiwiMTgiLHRydWVd~WyI5QVRkUldEdGVuVnNiMF85MWRueENnIiwiMjEiLGZhbHNlXQ~WyJmMFItM0xSMzA0bG45WXVKeVlnUGRRIiwiaXNzdWluZ19hdXRob3JpdHkiLCJERSJd~WyJhQlpCd3hyT1Rjd0JZemVDeDRlenpRIiwiaXNzdWluZ19jb3VudHJ5IiwiREUiXQ~"
        )
        val embeddedIssuerKey = sdJwt.x5c!!.certificates.first().ecPublicKey
        assertEquals(
            """
                {
                  "_sd": [
                    "849OXBJy-pv_598QXVqQfTJw_KHv9s8ggknU30Uh6ck",
                    "Pzq4l-GASkeM_d3OmKYgCKTzU8OQe66YnqJj_WqKOLc",
                    "b_qKetUOziEwOF_KaQ2GLh5z9whnaEc6kEtACtyjJvw",
                    "jEawQGEEcSdIX4VmKQKButt1EbOTg4A-pqrUmyo8qlE",
                    "jaIJLH79162IQ2vfN54AgMuUlB6lt9_47Sls9pP5-Ak",
                    "nlIx9t19ykpI1JmiL_frex_LUNzLpATauY_EWwODKic",
                    "p9Wm41-ClO-ZqsRVsnrsTsrePjhXdj2rTlWIkgj9Cl0",
                    "vjeQhJb_SRz5v7N644GunG3NFPEDQctKdT07myf5KG8"
                  ],
                  "vct": "urn:eudi:pid:1",
                  "_sd_alg": "sha-256",
                  "iss": "https://demo.bdr-prototypes/issuer/pid",
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "4llIXYZI-R3Iji2npD00ZgYsx-BkJjf8nqRxUkFwCQ0",
                      "y": "VTA0WQHZ5z_z42oCdEXwBjF1nZTtwrRpX83Lk80y14g"
                    }
                  },
                  "exp": 1753787665,
                  "iat": 1746011665,
                  "age_equal_or_over": {
                    "_sd": [
                      "8sfh8ROhyQWM-rdzz_iolAbvYcmgpWcDqMuERZvt51c",
                      "VAosG3SfXoHfPUVNh-hiBFSHnyx1dh3QsfoqqDpq-ZE"
                    ]
                  }
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwt.jwtBody)
        )

        assertEquals(
            """
                [
                  [
                    "0JdVzxjOPfpgDMOJ9CRZLQ",
                    "family_name",
                    "MUSTERMANN"
                  ],
                  [
                    "9QaOtekk_wflsfAaxTMYZw",
                    "given_name",
                    "ERIKA"
                  ],
                  [
                    "P35hPKHZVtm5Zrzw130WDA",
                    "birthdate",
                    "2006-02-28"
                  ],
                  [
                    "WxLjaVvxz3zmMRIznmRmTA",
                    "country",
                    "BERLIN"
                  ],
                  [
                    "Vv38eS7a0uirBma2t00h8w",
                    "locality",
                    "BERLIN"
                  ],
                  [
                    "MTeaeVdVeJkn7NmF8SRuNw",
                    "region",
                    "BERLIN"
                  ],
                  [
                    "QJKzZKTVWjhz8tXnUaFe2A",
                    "place_of_birth",
                    {
                      "_sd": [
                        "5L6BbPb1SkFIgoAFzUqsDbjxus7utDUOPx0gqZuuKbc",
                        "HjpUwzcqsBxH-PWfFrjsltS5f3zVPLFSYbf3OaFu8jk",
                        "NkdLBs__43DNH3JwOT6YvP8qmG5zoN02AlvMnbSp1zo"
                      ]
                    }
                  ],
                  [
                    "eVoj14OCZO7EZaVFpt2m4Q",
                    "nationalities",
                    []
                  ],
                  [
                    "Tm5HMF4rhacIm5_uXlJnHg",
                    "date_of_expiry",
                    "2026-12-31"
                  ],
                  [
                    "1MDJp8YW_wBzuBKMkHJHLA",
                    "18",
                    true
                  ],
                  [
                    "9ATdRWDtenVsb0_91dnxCg",
                    "21",
                    false
                  ],
                  [
                    "f0R-3LR304ln9YuJyYgPdQ",
                    "issuing_authority",
                    "DE"
                  ],
                  [
                    "aBZBwxrOTcwBYzeCx4ezzQ",
                    "issuing_country",
                    "DE"
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(sdJwt.disclosures)
        )

        assertEquals(
            """
                {
                  "vct": "urn:eudi:pid:1",
                  "iss": "https://demo.bdr-prototypes/issuer/pid",
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "4llIXYZI-R3Iji2npD00ZgYsx-BkJjf8nqRxUkFwCQ0",
                      "y": "VTA0WQHZ5z_z42oCdEXwBjF1nZTtwrRpX83Lk80y14g"
                    }
                  },
                  "exp": 1753787665,
                  "iat": 1746011665,
                  "age_equal_or_over": {
                    "21": false,
                    "18": true
                  },
                  "place_of_birth": {
                    "country": "BERLIN",
                    "region": "BERLIN",
                    "locality": "BERLIN"
                  },
                  "family_name": "MUSTERMANN",
                  "date_of_expiry": "2026-12-31",
                  "birthdate": "2006-02-28",
                  "given_name": "ERIKA",
                  "issuing_country": "DE",
                  "issuing_authority": "DE",
                  "nationalities": []
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwt.verify(embeddedIssuerKey))
        )
    }

    @Test
    fun testCreate() = runTest {
        val issuerKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val sdJwt = SdJwt.create(
            issuerKey = AsymmetricKey.anonymous(issuerKey, Algorithm.ESP256),
            kbKey = null,
            random = Random(0),
            claims = Json.parseToJsonElement(
                """
                    {
                      "given_name": "Erika",
                      "family_name": "Mustermann",
                      "age_birth_year": 1963,
                      "age_equal_or_over": {
                        "12": true,
                        "14": true,
                        "16": true,
                        "18": true,
                        "21": true,
                        "65": false
                      },
                      "nationalities": [
                        "DE",
                        "US",
                        "DK"
                      ]
                    }                    
                """.trimIndent().trim()
            ).jsonObject,
            nonSdClaims = Json.parseToJsonElement(
                """
                   {
                     "vct": "urn:eudi:pid:de:1",
                     "iss": "https://pid-issuer.bund.de.example"
                   }
                """.trimIndent().trim()
            ).jsonObject,
        )

        assertEquals(
            """
                {
                  "vct": "urn:eudi:pid:de:1",
                  "iss": "https://pid-issuer.bund.de.example",
                  "_sd": [
                    "7By1ZYnPqIm002hk803CGwHE2oOPTeVhFhP1LbZeDIo",
                    "tA1MbUtOb7DNrEl5-nZ9ycp1FMvGa1bywpB2H_sLcL0",
                    "IxHTcmPQxGkK7QLP3Y-aHl8IgMMYvHOLheOmiZ8-v14",
                    "L8PfuQ9a6MCXlqOpwPnepcZzAzGBOomP8z_KdJ29DNo",
                    "g1hFrw58GXGDplr7zhow1Y6cnGsDS9x-ocR62QglINg"
                  ],
                  "_sd_alg": "sha-256"
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwt.jwtBody)
        )

        assertEquals(
            """
                [
                  [
                    "LMK0jFCu_lOzl07ZHmtOqQ",
                    "given_name",
                    "Erika"
                  ],
                  [
                    "53vML1N_CwLv6GAwrCwxUw",
                    "family_name",
                    "Mustermann"
                  ],
                  [
                    "wKeWAvmlExDI7tmInUbvOw",
                    "age_birth_year",
                    1963
                  ],
                  [
                    "Cf-HXWT2yPIvr2IMcilpAA",
                    "12",
                    true
                  ],
                  [
                    "Vml25Rd2VJC86JxzOCo0Kw",
                    "14",
                    true
                  ],
                  [
                    "l1J5hbcVzU-JcvQonhGyLg",
                    "16",
                    true
                  ],
                  [
                    "UAfxWiEGSzdMfH8Z70q5Cw",
                    "18",
                    true
                  ],
                  [
                    "HYuz-QMe58l2GieOlzD_Kg",
                    "21",
                    true
                  ],
                  [
                    "c9bSh4D24cnb0UtII211fw",
                    "65",
                    false
                  ],
                  [
                    "3fmcSCNCxkGHyE43oiXe3g",
                    "age_equal_or_over",
                    {
                      "_sd": [
                        "05uahUW9clwm18BzECWj07NHKhgZ5fCqbCHGwod7Fmw",
                        "jOg0uBowidxdszcmz8PaLaklADgMgkD9ylRwvMIT9Kw",
                        "51EW28Po8yjEDdta1ylwPKQEm_pOPJUC7SI4Kf_iAOo",
                        "0U8TFOmeYpTEINAOL00hzAgUkd28sAhgwYylunV8XAg",
                        "XbobgnNiRnKgg04w55mvgXjcpXBnHdJfHvhIlszJs64",
                        "c9qyu4RFNp5r0pB7QWSwcPeBgw08ZQ-zS7G0IE_z7ck"
                      ]
                    }
                  ],
                  [
                    "yUpTzlSZ7Clfpz0eykbSnw",
                    "DE"
                  ],
                  [
                    "QyVu1oQpCqA0IvFunik-aQ",
                    "US"
                  ],
                  [
                    "NhBu7A2mB7_oVgTk42cPdw",
                    "DK"
                  ],
                  [
                    "FJ9ZnatPnIoKD2GTtP_7JQ",
                    "nationalities",
                    [
                      {
                        "...": "Gg_rF--hhVUPgoGoNCGo4tpP94TZ6jC1fgtgYwY8FW8"
                      },
                      {
                        "...": "HQesjd7LZyvIqL5TNxV8p1eNgckEkKwlQewZ2ZSZkXI"
                      },
                      {
                        "...": "Lq4W3DK4Plo9ImvkqnJts1vhaNVyAIfZ4KUSxtOyk4c"
                      }
                    ]
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(sdJwt.disclosures)
        )

        assertEquals(
            """
                {
                  "vct": "urn:eudi:pid:de:1",
                  "iss": "https://pid-issuer.bund.de.example",
                  "given_name": "Erika",
                  "family_name": "Mustermann",
                  "age_birth_year": 1963,
                  "age_equal_or_over": {
                    "12": true,
                    "14": true,
                    "16": true,
                    "18": true,
                    "21": true,
                    "65": false
                  },
                  "nationalities": [
                    "DE",
                    "US",
                    "DK"
                  ]
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwt.verify(issuerKey.publicKey))
        )
    }

    // Check that no filter produces the same SD-JWT.
    @Test
    fun testFilterAll() {
        val filteredSdJwt = SdJwt(sdJwtRfcAppendixA3SdJwtCompactSerialization)
            .filter { path: JsonArray, value: JsonElement -> true }
        assertEquals(filteredSdJwt.compactSerialization, sdJwtRfcAppendixA3SdJwtCompactSerialization)
        assertEquals(
            """
                [
                  [
                    "2GLC42sKQveCfGfryNRN9w",
                    "given_name",
                    "Erika"
                  ],
                  [
                    "eluV5Og3gSNII8EYnsxA_A",
                    "family_name",
                    "Mustermann"
                  ],
                  [
                    "6Ij7tM-a5iVPGboS5tmvVA",
                    "birthdate",
                    "1963-08-12"
                  ],
                  [
                    "eI8ZWm9QnKPpNPeNenHdhQ",
                    "street_address",
                    "Heidestraße 17"
                  ],
                  [
                    "Qg_O64zqAxe412a108iroA",
                    "locality",
                    "Köln"
                  ],
                  [
                    "AJx-095VPrpTtN4QMOqROA",
                    "postal_code",
                    "51147"
                  ],
                  [
                    "Pc33JM2LchcU_lHggv_ufQ",
                    "country",
                    "DE"
                  ],
                  [
                    "G02NSrQfjFXQ7Io09syajA",
                    "address",
                    {
                      "_sd": [
                        "ALZERsSn5WNiEXdCksW8I5qQw3_NpAnRqpSAZDudgw8",
                        "D__W_uYcvRz3tvUnIJvBDHiTc7C__qHd0xNKwIs_w9k",
                        "eBpCXU1J5dhH2g4t8QYNW5ExS9AxUVblUodoLYoPho0",
                        "xOPy9-gJALK6UbWKFLR85cOByUD3AbNwFg3I3YfQE_I"
                      ]
                    }
                  ],
                  [
                    "lklxF5jMYlGTPUovMNIvCA",
                    "nationalities",
                    [
                      "DE"
                    ]
                  ],
                  [
                    "nPuoQnkRFq3BIeAm7AnXFA",
                    "sex",
                    2
                  ],
                  [
                    "5bPs1IquZNa0hkaFzzzZNw",
                    "birth_family_name",
                    "Gabler"
                  ],
                  [
                    "5a2W0_NrlEZzfqmk_7Pq-w",
                    "locality",
                    "Berlin"
                  ],
                  [
                    "y1sVU5wdfJahVdgwPgS7RQ",
                    "country",
                    "DE"
                  ],
                  [
                    "HbQ4X8srVW3QDxnIJdqyOA",
                    "place_of_birth",
                    {
                      "_sd": [
                        "KUViaaLnY5jSML90G29OOLENPbbXfhSjSPMjZaGkxAE",
                        "YbsT0S76VqXCVsd1jUSlwKPDgmALeB1uZclFHXf-USQ"
                      ]
                    }
                  ],
                  [
                    "C9GSoujviJquEgYfojCb1A",
                    "12",
                    true
                  ],
                  [
                    "kx5kF17V-x0JmwUx9vgvtw",
                    "14",
                    true
                  ],
                  [
                    "H3o1uswP760Fi2yeGdVCEQ",
                    "16",
                    true
                  ],
                  [
                    "OBKlTVlvLg-AdwqYGbP8ZA",
                    "18",
                    true
                  ],
                  [
                    "M0Jb57t41ubrkSuyrDT3xA",
                    "21",
                    true
                  ],
                  [
                    "DsmtKNgpV4dAHpjrcaosAw",
                    "65",
                    false
                  ],
                  [
                    "eK5o5pHfgupPpltj1qhAJw",
                    "age_equal_or_over",
                    {
                      "_sd": [
                        "1tEiyzPRYOKsf7SsYGMgPZKsOT1lQZRxHXA0r5_Bwkk",
                        "CVKnly5P90yJs3EwtxQiOtUczaXCYNA4IczRaohrMDg",
                        "a44-g2Gr8_3AmJw2XZ8kI1y0Qz_ze9iOcW2W3RLpXGg",
                        "gkvy0FuvBBvj0hs2ZNwxcqOlf8mu2-kCE7-Nb2QxuBU",
                        "hrY4HnmF5b5JwC9eTzaFCUceIQAaIdhrqUXQNCWbfZI",
                        "y6SFrVFRyq50IbRJviTZqqjQWz0tLiuCmMeO0KqazGI"
                      ]
                    }
                  ],
                  [
                    "j7ADdb0UVb0Li0ciPcP0ew",
                    "age_in_years",
                    62
                  ],
                  [
                    "WpxJrFuX8uSi2p4ht09jvw",
                    "age_birth_year",
                    1963
                  ],
                  [
                    "atSmFACYMbJVKD05o3JgtQ",
                    "issuance_date",
                    "2020-03-11"
                  ],
                  [
                    "4KyR32oIZt-zkWvFqbULKg",
                    "expiry_date",
                    "2030-03-12"
                  ],
                  [
                    "chBCsyhyh-J86I-awQDiCQ",
                    "issuing_authority",
                    "DE"
                  ],
                  [
                    "flNP1ncMz9Lg-c9qMIz_9g",
                    "issuing_country",
                    "DE"
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(filteredSdJwt.disclosures)
        )
        assertEquals(
            """
                {
                  "iss": "https://pid-issuer.bund.de.example",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "vct": "urn:eudi:pid:de:1",
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  },
                  "given_name": "Erika",
                  "place_of_birth": {
                    "locality": "Berlin",
                    "country": "DE"
                  },
                  "age_equal_or_over": {
                    "21": true,
                    "18": true,
                    "65": false,
                    "12": true,
                    "16": true,
                    "14": true
                  },
                  "issuing_authority": "DE",
                  "expiry_date": "2030-03-12",
                  "sex": 2,
                  "family_name": "Mustermann",
                  "birth_family_name": "Gabler",
                  "birthdate": "1963-08-12",
                  "age_birth_year": 1963,
                  "address": {
                    "street_address": "Heidestraße 17",
                    "locality": "Köln",
                    "country": "DE",
                    "postal_code": "51147"
                  },
                  "issuance_date": "2020-03-11",
                  "age_in_years": 62,
                  "issuing_country": "DE",
                  "nationalities": [
                    "DE"
                  ]
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(filteredSdJwt.verify(sdJwtRfcIssuerKey))
        )
    }

    // Check that filtering all includes only the always disclosed elements.
    @Test
    fun testFilterNone() {
        val filteredSdJwt = SdJwt(sdJwtRfcAppendixA3SdJwtCompactSerialization)
            .filter { path: JsonArray, value: JsonElement -> false }
        assertEquals(
            """
                []
            """.trimIndent().trim(),
            prettyPrintDisclosures(filteredSdJwt.disclosures)
        )
        assertEquals(
            """
                {
                  "iss": "https://pid-issuer.bund.de.example",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "vct": "urn:eudi:pid:de:1",
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  }
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(filteredSdJwt.verify(sdJwtRfcIssuerKey)
            )
        )
    }

    // Check filtering on "given_name".
    @Test
    fun testFilterGivenNameOnly() {
        val filteredSdJwt = SdJwt(sdJwtRfcAppendixA3SdJwtCompactSerialization)
            .filter { path: JsonArray, value: JsonElement ->
                path.size == 1 && path[0].jsonPrimitive.content == "given_name"
            }
        assertEquals(
            """
                [
                  [
                    "2GLC42sKQveCfGfryNRN9w",
                    "given_name",
                    "Erika"
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(filteredSdJwt.disclosures)
        )
        assertEquals(
            """
                {
                  "iss": "https://pid-issuer.bund.de.example",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "vct": "urn:eudi:pid:de:1",
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  },
                  "given_name": "Erika"
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(filteredSdJwt.verify(sdJwtRfcIssuerKey))
        )
    }

    // Check filtering on "address.locality" - this demonstrates that `filter()` tops
    // off the list of disclosures as needed, that is, the disclosure for both "address.locality"
    // and also "address" is included EVEN when only "address.locality" was requested.
    //
    @Test
    fun testFilterTopOffWorks() {
        val filteredSdJwt = SdJwt(sdJwtRfcAppendixA3SdJwtCompactSerialization)
            .filter { path: JsonArray, value: JsonElement ->
                path.size == 2 && path[0].jsonPrimitive.content == "address" && path[1].jsonPrimitive.content == "locality"
            }
        assertEquals(
            """
                [
                  [
                    "Qg_O64zqAxe412a108iroA",
                    "locality",
                    "Köln"
                  ],
                  [
                    "G02NSrQfjFXQ7Io09syajA",
                    "address",
                    {
                      "_sd": [
                        "ALZERsSn5WNiEXdCksW8I5qQw3_NpAnRqpSAZDudgw8",
                        "D__W_uYcvRz3tvUnIJvBDHiTc7C__qHd0xNKwIs_w9k",
                        "eBpCXU1J5dhH2g4t8QYNW5ExS9AxUVblUodoLYoPho0",
                        "xOPy9-gJALK6UbWKFLR85cOByUD3AbNwFg3I3YfQE_I"
                      ]
                    }
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(filteredSdJwt.disclosures)
        )
        assertEquals(
            """
                {
                  "iss": "https://pid-issuer.bund.de.example",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "vct": "urn:eudi:pid:de:1",
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  },
                  "address": {
                    "locality": "Köln"
                  }
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(filteredSdJwt.verify(sdJwtRfcIssuerKey))
        )
    }

    // Check filtering using paths on top-level claims.
    @Test
    fun testFilterOnPaths() {
        val filteredSdJwt = SdJwt(sdJwtRfcAppendixA3SdJwtCompactSerialization)
            .filter(
                listOf(
                    JsonArray(listOf(JsonPrimitive("given_name"))),
                    JsonArray(listOf(JsonPrimitive("address"))),
                )
            )
        assertEquals(
            """
                [
                  [
                    "2GLC42sKQveCfGfryNRN9w",
                    "given_name",
                    "Erika"
                  ],
                  [
                    "eI8ZWm9QnKPpNPeNenHdhQ",
                    "street_address",
                    "Heidestraße 17"
                  ],
                  [
                    "Qg_O64zqAxe412a108iroA",
                    "locality",
                    "Köln"
                  ],
                  [
                    "AJx-095VPrpTtN4QMOqROA",
                    "postal_code",
                    "51147"
                  ],
                  [
                    "Pc33JM2LchcU_lHggv_ufQ",
                    "country",
                    "DE"
                  ],
                  [
                    "G02NSrQfjFXQ7Io09syajA",
                    "address",
                    {
                      "_sd": [
                        "ALZERsSn5WNiEXdCksW8I5qQw3_NpAnRqpSAZDudgw8",
                        "D__W_uYcvRz3tvUnIJvBDHiTc7C__qHd0xNKwIs_w9k",
                        "eBpCXU1J5dhH2g4t8QYNW5ExS9AxUVblUodoLYoPho0",
                        "xOPy9-gJALK6UbWKFLR85cOByUD3AbNwFg3I3YfQE_I"
                      ]
                    }
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(filteredSdJwt.disclosures)
        )
        assertEquals(
            """
                {
                  "iss": "https://pid-issuer.bund.de.example",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "vct": "urn:eudi:pid:de:1",
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  },
                  "given_name": "Erika",
                  "address": {
                    "street_address": "Heidestraße 17",
                    "locality": "Köln",
                    "country": "DE",
                    "postal_code": "51147"
                  }
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(filteredSdJwt.verify(sdJwtRfcIssuerKey))
        )
    }

    // Check filtering using paths into an object.
    @Test
    fun testFilterOnPathsIntoObject() {
        val filteredSdJwt = SdJwt(sdJwtRfcAppendixA3SdJwtCompactSerialization)
            .filter(
                listOf(
                    JsonArray(listOf(JsonPrimitive("given_name"))),
                    JsonArray(listOf(JsonPrimitive("address"), JsonPrimitive("locality"))),
                    JsonArray(listOf(JsonPrimitive("address"), JsonPrimitive("country"))),
                )
            )
        assertEquals(
            """
                [
                  [
                    "2GLC42sKQveCfGfryNRN9w",
                    "given_name",
                    "Erika"
                  ],
                  [
                    "Qg_O64zqAxe412a108iroA",
                    "locality",
                    "Köln"
                  ],
                  [
                    "Pc33JM2LchcU_lHggv_ufQ",
                    "country",
                    "DE"
                  ],
                  [
                    "G02NSrQfjFXQ7Io09syajA",
                    "address",
                    {
                      "_sd": [
                        "ALZERsSn5WNiEXdCksW8I5qQw3_NpAnRqpSAZDudgw8",
                        "D__W_uYcvRz3tvUnIJvBDHiTc7C__qHd0xNKwIs_w9k",
                        "eBpCXU1J5dhH2g4t8QYNW5ExS9AxUVblUodoLYoPho0",
                        "xOPy9-gJALK6UbWKFLR85cOByUD3AbNwFg3I3YfQE_I"
                      ]
                    }
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(filteredSdJwt.disclosures)
        )
        assertEquals(
            """
                {
                  "iss": "https://pid-issuer.bund.de.example",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "vct": "urn:eudi:pid:de:1",
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  },
                  "given_name": "Erika",
                  "address": {
                    "locality": "Köln",
                    "country": "DE"
                  }
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(filteredSdJwt.verify(sdJwtRfcIssuerKey))
        )
    }

    // Check filtering using paths into an array.
    @Test
    fun testFilterOnPathsIntoArrayWhole() {
        val filteredSdJwt = SdJwt(sdJwtRfcSection51SdJwtCompactSerialization)
            .filter(
                listOf(
                    JsonArray(listOf(JsonPrimitive("given_name"))),
                    JsonArray(listOf(JsonPrimitive("nationalities"))),
                )
            )
        assertEquals(
            """
                [
                  [
                    "2GLC42sKQveCfGfryNRN9w",
                    "given_name",
                    "John"
                  ],
                  [
                    "lklxF5jMYlGTPUovMNIvCA",
                    "US"
                  ],
                  [
                    "nPuoQnkRFq3BIeAm7AnXFA",
                    "DE"
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(filteredSdJwt.disclosures)
        )
        assertEquals(
            """
                {
                  "iss": "https://issuer.example.com",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "sub": "user_42",
                  "nationalities": [
                    "US",
                    "DE"
                  ],
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  },
                  "given_name": "John"
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(filteredSdJwt.verify(sdJwtRfcIssuerKey))
        )
    }

    // Check filtering using paths into an array.
    @Test
    fun testFilterOnPathsIntoArray() {
        val filteredSdJwt = SdJwt(sdJwtRfcSection51SdJwtCompactSerialization)
            .filter(
                listOf(
                    JsonArray(listOf(JsonPrimitive("given_name"))),
                    JsonArray(listOf(JsonPrimitive("nationalities"), JsonPrimitive(0))),
                )
            )
        assertEquals(
            """
                [
                  [
                    "2GLC42sKQveCfGfryNRN9w",
                    "given_name",
                    "John"
                  ],
                  [
                    "lklxF5jMYlGTPUovMNIvCA",
                    "US"
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(filteredSdJwt.disclosures)
        )
        assertEquals(
            """
                {
                  "iss": "https://issuer.example.com",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "sub": "user_42",
                  "nationalities": [
                    "US"
                  ],
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  },
                  "given_name": "John"
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(filteredSdJwt.verify(sdJwtRfcIssuerKey))
        )
    }

    // Check filtering using paths into an array.
    @Test
    fun testFilterOnPathsIntoArray2() {
        val filteredSdJwt = SdJwt(sdJwtRfcSection51SdJwtCompactSerialization)
            .filter(
                listOf(
                    JsonArray(listOf(JsonPrimitive("given_name"))),
                    JsonArray(listOf(JsonPrimitive("nationalities"), JsonPrimitive(1))),
                )
            )
        assertEquals(
            """
                [
                  [
                    "2GLC42sKQveCfGfryNRN9w",
                    "given_name",
                    "John"
                  ],
                  [
                    "nPuoQnkRFq3BIeAm7AnXFA",
                    "DE"
                  ]
                ]
            """.trimIndent().trim(),
            prettyPrintDisclosures(filteredSdJwt.disclosures)
        )
        assertEquals(
            """
                {
                  "iss": "https://issuer.example.com",
                  "iat": 1683000000,
                  "exp": 1883000000,
                  "sub": "user_42",
                  "nationalities": [
                    "DE"
                  ],
                  "cnf": {
                    "jwk": {
                      "kty": "EC",
                      "crv": "P-256",
                      "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                      "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                    }
                  },
                  "given_name": "John"
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(filteredSdJwt.verify(sdJwtRfcIssuerKey))
        )
    }

    @Test
    fun testPresent() = runTest {
        val issuerKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val kbKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val sdJwt = SdJwt.create(
            issuerKey = AsymmetricKey.anonymous(issuerKey, Algorithm.ESP256),
            kbKey = kbKey.publicKey,
            random = Random(0),
            claims = Json.parseToJsonElement(
                """
                    {
                      "given_name": "Erika",
                      "family_name": "Mustermann",
                      "age_birth_year": 1963,
                      "age_equal_or_over": {
                        "12": true,
                        "14": true,
                        "16": true,
                        "18": true,
                        "21": true,
                        "65": false
                      },
                      "nationalities": [
                        "DE",
                        "US",
                        "DK"
                      ]
                    }                    
                """.trimIndent().trim()
            ).jsonObject,
            nonSdClaims = Json.parseToJsonElement(
                """
                   {
                     "vct": "urn:eudi:pid:de:1",
                     "iss": "https://pid-issuer.bund.de.example"
                   }                    
                """.trimIndent().trim()
            ).jsonObject,
        )

        val nonce = Random.nextBytes(16).toHex()
        val creationTime = Instant.fromEpochSeconds(Clock.System.now().toEpochMilliseconds()/1000L)
        val sdJwtKb = sdJwt
            .filter(
                listOf(
                    JsonArray(listOf(JsonPrimitive("given_name"))),
                    JsonArray(listOf(JsonPrimitive("age_equal_or_over"), JsonPrimitive("18"))),
                    JsonArray(listOf(JsonPrimitive("nationalities"))),
                    JsonArray(listOf(JsonPrimitive("age_birth_year"))),
                )
            )
            .present(
                signingKey = AsymmetricKey.anonymous(kbKey, Algorithm.ESP256),
                nonce = nonce,
                audience = "https://verifier.example.org",
                creationTime = creationTime
            )
        assertEquals(
            """
                {
                  "vct": "urn:eudi:pid:de:1",
                  "iss": "https://pid-issuer.bund.de.example",
                  "cnf": {
                    "jwk": {
                      "crv": "P-256",
                      "kty": "EC",
                      "x": "${(kbKey.publicKey as EcPublicKeyDoubleCoordinate).x.toBase64Url()}",
                      "y": "${(kbKey.publicKey as EcPublicKeyDoubleCoordinate).y.toBase64Url()}"
                    }
                  },
                  "given_name": "Erika",
                  "age_birth_year": 1963,
                  "age_equal_or_over": {
                    "18": true
                  },
                  "nationalities": [
                    "DE",
                    "US",
                    "DK"
                  ]
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(sdJwtKb.verify(
                issuerKey = issuerKey.publicKey,
                checkNonce = { nonce_ -> nonce_ == nonce },
                checkAudience = { audience_ -> audience_ == "https://verifier.example.org" },
                checkCreationTime = { creationTime_ -> creationTime_ == creationTime }
            ))
        )
    }

    @Test
    fun testAccessors() {
        val sdJwt = SdJwt(sdJwtRfcAppendixA3SdJwtCompactSerialization)
        assertEquals("https://pid-issuer.bund.de.example", sdJwt.issuer)
        assertEquals(null, sdJwt.subject)
        assertEquals("urn:eudi:pid:de:1", sdJwt.credentialType)
        assertEquals(null, sdJwt.validFrom)
        assertEquals(1883000000000L, sdJwt.validUntil!!.toEpochMilliseconds())
        assertEquals(1683000000000L, sdJwt.issuedAt!!.toEpochMilliseconds())
        assertEquals(
            """
                {
                  "crv": "P-256",
                  "kty": "EC",
                  "x": "TCAER19Zvu3OHF4j4W4vfSVoHIP1ILilDls7vCeGemc",
                  "y": "ZxjiWWbZMQGHVWKVQ4hbSIirsVfuecCE6t4jT9F2HZQ"
                }
            """.trimIndent().trim(),
            prettyJson.encodeToString(
                sdJwt.kbKey!!.toJwk()
            ))

        val otherSdJwt = SdJwt(sdJwtRfcSection51SdJwtCompactSerialization)
        assertEquals("user_42", otherSdJwt.subject)
    }

}
