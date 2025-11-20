package org.multipaz.statuslist

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.jwt.JwtCheck
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class StatusListTest {
    // testSpecVectorN tests use datasets from the spec (see "Test vectors for Status List encoding"
    // section in https://datatracker.ietf.org/doc/draft-ietf-oauth-status-list/)
    @Test
    fun testSpecVector1() {
        val status = mutableMapOf<Int, Int>()
        status[0]=1
        status[1993]=1
        status[25460]=1
        status[159495]=1
        status[495669]=1
        status[554353]=1
        status[645645]=1
        status[723232]=1
        status[854545]=1
        status[934534]=1
        status[1000345]=1

        val statusList = StatusList.fromJson(Json.parseToJsonElement("""
        {
            "bits": 1,
            "lst": "eNrt3AENwCAMAEGogklACtKQPg9LugC9k_ACvreiogEAAKkeCQAAAAAAAAAAAAAAAAAAAIBylgQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAXG9IAAAAAAAAAPwsJAAAAAAAAAAAAAAAvhsSAAAAAAAAAAAA7KpLAAAAAAAAAAAAAAAAAAAAAJsLCQAAAAAAAAAAADjelAAAAAAAAAAAKjDMAQAAAACAZC8L2AEb"
        }
        """).jsonObject)

        assertEquals(1, statusList.bitsPerItem)
        assertStatusList(status, statusList)
    }

    @Test
    fun testSpecVector2() {
        val status = mutableMapOf<Int, Int>()
        status[0]=1
        status[1993]=2
        status[25460]=1
        status[159495]=3
        status[495669]=1
        status[554353]=1
        status[645645]=2
        status[723232]=1
        status[854545]=1
        status[934534]=2
        status[1000345]=3

        val statusList = StatusList.fromJson(Json.parseToJsonElement("""
        {
            "bits": 2,
            "lst": "eNrt2zENACEQAEEuoaBABP5VIO01fCjIHTMStt9ovGVIAAAAAABAbiEBAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEB5WwIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAID0ugQAAAAAAAAAAAAAAAAAQG12SgAAAAAAAAAAAAAAAAAAAAAAAAAAAOCSIQEAAAAAAAAAAAAAAAAAAAAAAAD8ExIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwJEuAQAAAAAAAAAAAAAAAAAAAAAAAMB9SwIAAAAAAAAAAAAAAAAAAACoYUoAAAAAAAAAAAAAAEBqH81gAQw"
        }
        """).jsonObject)

        assertEquals(2, statusList.bitsPerItem)
        assertStatusList(status, statusList)
    }

    @Test
    fun testSpecVector4() {
        val status = mutableMapOf<Int, Int>()
        status[0]=1
        status[1993]=2
        status[35460]=3
        status[459495]=4
        status[595669]=5
        status[754353]=6
        status[845645]=7
        status[923232]=8
        status[924445]=9
        status[934534]=10
        status[1004534]=11
        status[1000345]=12
        status[1030203]=13
        status[1030204]=14
        status[1030205]=15

        val statusList = StatusList.fromJson(Json.parseToJsonElement("""
        {
            "bits": 4,
            "lst": "eNrt0EENgDAQADAIHwImkIIEJEwCUpCEBBQRHOy35Li1EjoOQGabAgAAAAAAAAAAAAAAAAAAACC1SQEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABADrsCAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADoxaEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIIoCgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACArpwKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGhqVkAzlwIAAAAAiGVRAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABx3AoAgLpVAQAAAAAAAAAAAAAAwM89rwMAAAAAAAAAAAjsA9xMBMA"
        }
        """).jsonObject)

        assertEquals(4, statusList.bitsPerItem)
        assertStatusList(status, statusList)
    }

    @Test
    fun testSpecVector8() {
        val status = mutableMapOf<Int, Int>()
        status[233478] = 0
        status[52451] = 1
        status[576778] = 2
        status[513575] = 3
        status[468106] = 4
        status[292632] = 5
        status[214947] = 6
        status[182323] = 7
        status[884834] = 8
        status[66653] = 9
        status[62489] = 10
        status[196493] = 11
        status[458517] = 12
        status[487925] = 13
        status[55649] = 14
        status[416992] = 15
        status[879796] = 16
        status[462297] = 17
        status[942059] = 18
        status[583408] = 19
        status[13628] = 20
        status[334829] = 21
        status[886286] = 22
        status[713557] = 23
        status[582738] = 24
        status[326064] = 25
        status[451545] = 26
        status[705889] = 27
        status[214350] = 28
        status[194502] = 29
        status[796765] = 30
        status[202828] = 31
        status[752834] = 32
        status[721327] = 33
        status[554740] = 34
        status[91122] = 35
        status[963483] = 36
        status[261779] = 37
        status[793844] = 38
        status[165255] = 39
        status[614839] = 40
        status[758403] = 41
        status[403258] = 42
        status[145867] = 43
        status[96100] = 44
        status[477937] = 45
        status[606890] = 46
        status[167335] = 47
        status[488197] = 48
        status[211815] = 49
        status[797182] = 50
        status[582952] = 51
        status[950870] = 52
        status[765108] = 53
        status[341110] = 54
        status[776325] = 55
        status[745056] = 56
        status[439368] = 57
        status[559893] = 58
        status[149741] = 59
        status[358903] = 60
        status[513405] = 61
        status[342679] = 62
        status[969429] = 63
        status[795775] = 64
        status[566121] = 65
        status[460566] = 66
        status[680070] = 67
        status[117310] = 68
        status[480348] = 69
        status[67319] = 70
        status[661552] = 71
        status[841303] = 72
        status[561493] = 73
        status[138807] = 74
        status[442463] = 75
        status[659927] = 76
        status[445910] = 77
        status[1046963] = 78
        status[829700] = 79
        status[962282] = 80
        status[299623] = 81
        status[555493] = 82
        status[292826] = 83
        status[517215] = 84
        status[551009] = 85
        status[898490] = 86
        status[837603] = 87
        status[759161] = 88
        status[459948] = 89
        status[290102] = 90
        status[1034977] = 91
        status[190650] = 92
        status[98810] = 93
        status[229950] = 94
        status[320531] = 95
        status[335506] = 96
        status[885333] = 97
        status[133227] = 98
        status[806915] = 99
        status[800313] = 100
        status[981571] = 101
        status[527253] = 102
        status[24077] = 103
        status[240232] = 104
        status[559572] = 105
        status[713399] = 106
        status[233941] = 107
        status[615514] = 108
        status[911768] = 109
        status[331680] = 110
        status[951527] = 111
        status[6805] = 112
        status[552366] = 113
        status[374660] = 114
        status[223159] = 115
        status[625884] = 116
        status[417146] = 117
        status[320527] = 118
        status[784154] = 119
        status[338792] = 120
        status[1199] = 121
        status[679804] = 122
        status[1024680] = 123
        status[40845] = 124
        status[234603] = 125
        status[761225] = 126
        status[644903] = 127
        status[502167] = 128
        status[121477] = 129
        status[505144] = 130
        status[165165] = 131
        status[179628] = 132
        status[1019195] = 133
        status[145149] = 134
        status[263738] = 135
        status[269256] = 136
        status[996739] = 137
        status[346296] = 138
        status[555864] = 139
        status[887384] = 140
        status[444173] = 141
        status[421844] = 142
        status[653716] = 143
        status[836747] = 144
        status[783119] = 145
        status[918762] = 146
        status[946835] = 147
        status[253764] = 148
        status[519895] = 149
        status[471224] = 150
        status[134272] = 151
        status[709016] = 152
        status[44112] = 153
        status[482585] = 154
        status[461829] = 155
        status[15080] = 156
        status[148883] = 157
        status[123467] = 158
        status[480125] = 159
        status[141348] = 160
        status[65877] = 161
        status[692958] = 162
        status[148598] = 163
        status[499131] = 164
        status[584009] = 165
        status[1017987] = 166
        status[449287] = 167
        status[277478] = 168
        status[991262] = 169
        status[509602] = 170
        status[991896] = 171
        status[853666] = 172
        status[399318] = 173
        status[197815] = 174
        status[203278] = 175
        status[903979] = 176
        status[743015] = 177
        status[888308] = 178
        status[862143] = 179
        status[979421] = 180
        status[113605] = 181
        status[206397] = 182
        status[127113] = 183
        status[844358] = 184
        status[711569] = 185
        status[229153] = 186
        status[521470] = 187
        status[401793] = 188
        status[398896] = 189
        status[940810] = 190
        status[293983] = 191
        status[884749] = 192
        status[384802] = 193
        status[584151] = 194
        status[970201] = 195
        status[523882] = 196
        status[158093] = 197
        status[929312] = 198
        status[205329] = 199
        status[106091] = 200
        status[30949] = 201
        status[195586] = 202
        status[495723] = 203
        status[348779] = 204
        status[852312] = 205
        status[1018463] = 206
        status[1009481] = 207
        status[448260] = 208
        status[841042] = 209
        status[122967] = 210
        status[345269] = 211
        status[794764] = 212
        status[4520] = 213
        status[818773] = 214
        status[556171] = 215
        status[954221] = 216
        status[598210] = 217
        status[887110] = 218
        status[1020623] = 219
        status[324632] = 220
        status[398244] = 221
        status[622241] = 222
        status[456551] = 223
        status[122648] = 224
        status[127837] = 225
        status[657676] = 226
        status[119884] = 227
        status[105156] = 228
        status[999897] = 229
        status[330160] = 230
        status[119285] = 231
        status[168005] = 232
        status[389703] = 233
        status[143699] = 234
        status[142524] = 235
        status[493258] = 236
        status[846778] = 237
        status[251420] = 238
        status[516351] = 239
        status[83344] = 240
        status[171931] = 241
        status[879178] = 242
        status[663475] = 243
        status[546865] = 244
        status[428362] = 245
        status[658891] = 246
        status[500560] = 247
        status[557034] = 248
        status[830023] = 249
        status[274471] = 250
        status[629139] = 251
        status[958869] = 252
        status[663071] = 253
        status[152133] = 254
        status[19535] = 255

        val statusList = StatusList.fromJson(Json.parseToJsonElement("""
        {
            "bits": 8,
            "lst": "eNrt0WOQM2kYhtGsbdu2bdu2bdu2bdu2bdu2jVnU1my-SWYm6U5enFPVf7ue97orFYAo7CQBAACQuuckAABStqUEAAAAAAAAtN6wEgAE71QJAAAAAIrwhwQAAAAAAdtAAgAAAAAAACLwkAQAAAAAAAAAAACUaFcJAACAeJwkAQAAAAAAAABQvL4kAAAAWmJwCQAAAAAAAAjAwBIAAAB06ywJoDKQBARpfgkAAAAAAAAAAAAAAAAAAACo50sJAAAAAAAAAOiRcSQAAAAAgAJNKgEAAG23mgQAAAAAAECw3pUAQvegBAAAAAAAAADduE4CAAAAyjSvBAAQiw8koHjvSABAb-wlARCONyVoxtMSZOd0CQAAAOjWDRKQmLckAAAAAACysLYEQGcnSAAAAAAQooUlAABI15kSAIH5RAIgLB9LABC4_SUgGZNIAABAmM6RoLbTJIASzCIBAEAhfpcAAAAAAABquk8CAAAAAAAAaJl9SvvzBOICAFWmkIBgfSgBAAAANOgrCQAAAAAAAADStK8EAAC03gASAAAAAAAAAADFWFUCAAAAMjOaBEADHpYAQjCIBADduFwCAAAAAGitMSSI3BUSAECOHpAA6IHrJQAAAAAAsjeVBAAAKRpVAorWvwQAAAAAAAAAkKRtJAAAAAAAgCbcLAF0bXUJAAAAoF02kYDg7CYBAAAAAEB6NpQAAAAAAAAAAAAAAEr1uQQAAF06VgIAAAAAAAAAqDaeBAAQqgMkAAAAAABogQMlAAAAAAAa87MEAAAQiwslAAAAAAAAAAAAAAAAMrOyBAAAiekv-hcsY0Sgne6QAAAAAAAgaUtJAAAAAAAAAAAAAAAAAAAAAAAAAADwt-07vjVkAAAAgDy8KgFAUEaSAAAAAJL3vgQAWdhcAgAAoBHDSUDo1pQAAACI2o4SAABZm14CALoyuwQAAPznGQkgZwdLAAAQukclAAAAAAAAAAAAgKbMKgEAAAAAAAAAAAAAAAAAAECftpYAAAAAAAAAAAAACnaXBAAAAADk7iMJAAAAAAAAAABqe00CAnGbBBG4TAIAgFDdKgFAXCaWAAAAAAAAAAAAAAAAAKAJQwR72XbGAQAAAKAhh0sAAAAAAABQgO8kAAAAAAAAAAAAACAaM0kAAAC5W0QCAIJ3mAQAxGwxCQAA6nhSAsjZBRIAANEbWQIAAAAAaJE3JACAwA0qAUBIVpKAlphbAiAPp0iQnKEkAAAAAAAgBP1KAAAAdOl4CQAAAAAAAPjLZBIAAG10RtrPm8_CAEBMTpYAAAAAAIjQYBL8z5QSAAAAAEDYPpUAACAsj0gAAADQkHMlAAjHDxIA0Lg9JQAAgHDsLQEAAABAQS6WAAAAgLjNFs2l_RgLAIAEfCEBlGZZCQAAaIHjJACgtlskAAAozb0SAAAAVFtfAgAAAAAAAAAAAAAAAAAAAAAAAKDDtxIAAAAAVZaTAKB5W0kAANCAsSUgJ0tL0GqHSNBbL0gAZflRAgCARG0kQXNmlgCABiwkAQAAAEB25pIAAAAAAAAAAAAAoFh9SwAAAAAAADWNmOSrpjFsEoaRgDKcF9Q1dxsEAAAAAAAAAAAAAAAAgPZ6SQIAAAAAAAAAgChMLgEAAAAAAAAAqZlQAsK2qQQAAAAAAAD06XUJAAAAqG9bCQAAgLD9IgEAAAAAAAAAAAAAAAAAAEBNe0gAAAAAAAAAAEBPHSEBAAAAlOZtCYA4fS8B0GFRCQAo0gISAOTgNwmC840EAAAAAAAAAAAAAAAAAAAAUJydJfjXPBIAAAAAAAAAAAAAAABk6WwJAAAAAAAAAAAAAAAAqG8UCQAAgPpOlAAAIA83SQAANWwc9HUjGAgAAAAAAACAusaSAAAAAAAAAAAAAAAAAAAAAAAAAAAAqHKVBACQjxklAAAAAAAAAKBHxpQAAAAAACBME0lAdlaUAACyt7sEAAAA0Nl0EgAAAAAAAAAAAABA-8wgAQAAAAAAAKU4SgKgUtlBAgAAAAAAAAAAgMCMLwEE51kJICdzSgCJGl2CsE0tAQAA0L11JQAAAAAAAAjUOhIAAAAAAAAAAAAAAGTqeQkAAAAAAAAAAAAAKM8SEjTrJwkAAAAAAACocqQEULgVJAAAACjDUxJUKgtKAAAAqbpRAgCA0n0mAQAAAABAGzwmAUCTLpUAAAAAAAAAAEjZNRIAAAAAAAAAAAAAAAAAAAAA8I-vJaAlhpQAAAAAAHrvzjJ-OqCuuVlLAojP8BJAr70sQZVDJYAgXS0BAAAAAAAAAAAAtMnyEgAAAAAAFONKCQAAAAAAAADorc0kAAAAAAAAgDqOlgAAAAAAAAAAAADIwv0SAAAAAAAAAAAAAADBuV0CIFVDSwAAAABAAI6RAAAAAGIwrQSEZAsJAABouRclAAAAAKDDrxIAAAA0bkkJgFiMKwEAAAAAAHQyhwRk7h4JAAAAAAAAAAAgatdKAACUYj0JAAAAAAAAAAAAQnORBLTFJRIAAAAAkIaDJAAAAJryngQAAAAAAAAAAAA98oQEAAAAAAAAAEC2zpcgWY9LQKL2kwAgGK9IAAAAAPHaRQIAAAAAAAAAAADIxyoSAAAAAAAAAAAAAADQFotLAECz_gQ1PX-B"
        }
        """).jsonObject)

        assertEquals(8, statusList.bitsPerItem)
        assertStatusList(status, statusList)
    }

    @Test
    fun invalidStatus() {
        val statusListBuilder = StatusList.Builder(1)
        try {
            statusListBuilder.addStatus(3, 2)
            fail()
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            statusListBuilder.addStatus(3, -1)
            fail()
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun invalidIndex() {
        val statusListBuilder = StatusList.Builder(2)
        try {
            statusListBuilder.addStatus(-1, 2)
            fail()
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            statusListBuilder.addStatus(5, 2)
            statusListBuilder.addStatus(5, 2)
            fail()
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun roundtrip1() = runTest { testRoundtrip(5000, 1) }

    @Test
    fun roundtrip2() = runTest { testRoundtrip(5000, 2) }

    @Test
    fun roundtrip4() = runTest { testRoundtrip(5000, 4) }

    @Test
    fun roundtrip8() = runTest { testRoundtrip(5000, 8) }

    suspend fun testRoundtrip(size: Int, bits: Int) {
        val map = mutableMapOf<Int, Int>()
        val statusCount = (1 shl bits)  // number of distinct status values
        repeat (size / 8 + 5) {
            map[Random.nextInt(size)] =
                1 + if (statusCount == 1) 1 else Random.nextInt(statusCount - 1)
        }
        val builder = StatusList.Builder(bits)
        for ((index, status) in map.entries.sortedWith { (i1, _), (i2, _) -> i1 - i2 }) {
            builder.addStatus(index, status)
        }
        val key = AsymmetricKey.ephemeral()
        val jwt = builder.build().compress().serializeAsJwt(key, "foo")
        val statusList = StatusList.fromJwt(jwt, key.publicKey, mapOf(JwtCheck.ISS to "foo"))
        assertStatusList(map, statusList)
    }

    fun assertStatusList(expected: Map<Int, Int>, statusList: StatusList) {
        var maxIndex = 0
        for ((index, status) in expected.entries.sortedWith { (i1, _), (i2, _) -> i1 - i2 }) {
            if (index > maxIndex) {
                maxIndex = index
            }
            assertEquals(status, statusList[index], "index = $index")
        }
        for (index in 0..<maxIndex) {
            if (!expected.contains(index)) {
                assertEquals(0, statusList[index])
            }
        }
    }
}