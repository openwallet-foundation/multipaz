package org.multipaz.revocation

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tagged
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.util.fromHex
import org.multipaz.webtoken.WebTokenCheck
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration

class StatusListTest {
    // testSpecVectorN tests use datasets from the spec (see "Test vectors for Status List encoding"
    // section in https://datatracker.ietf.org/doc/draft-ietf-oauth-status-list/)
    @Test
    fun testSpecVector1() = runTest {
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

        val statusListJson = StatusList.fromJson(Json.parseToJsonElement("""
        {
            "bits": 1,
            "lst": "eNrt3AENwCAMAEGogklACtKQPg9LugC9k_ACvreiogEAAKkeCQAAAAAAAAAAAAAAAAAAAIBylgQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAXG9IAAAAAAAAAPwsJAAAAAAAAAAAAAAAvhsSAAAAAAAAAAAA7KpLAAAAAAAAAAAAAAAAAAAAAJsLCQAAAAAAAAAAADjelAAAAAAAAAAAKjDMAQAAAACAZC8L2AEb"
        }
        """).jsonObject)

        assertEquals(1, statusListJson.bitsPerItem)
        assertStatusList(status, statusListJson)

        val statusListCbor = StatusList.fromDataItem(Cbor.decode("""
            a2646269747301636c737458bd78daeddc010dc0200c0041a88249400ad2903e0f4b
            ba00bd93f002beb7a2a2010000a91e09000000000000000000000000000000807296
            04000000000000000000000000000000000000000000000000000000000000000000
            000000000000005c6f4800000000000000fc2c240000000000000000000000be1b12
            000000000000000000ecaa4b000000000000000000000000000000009b0b09000000
            00000000000038de9400000000000000002a30cc010000000080642f0bd8011b
        """.replace(Regex("\\s+"), "").fromHex()))

        assertEquals(1, statusListCbor.bitsPerItem)
        assertStatusList(status, statusListCbor)
    }

    @Test
    fun testSpecVector2() = runTest {
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

        val statusListCbor = StatusList.fromDataItem(Cbor.decode("""
               a2646269747302636c737459013d78daeddb310d00211000412ea1a04004fe5520ed
               357c28c81d3312b6df68bc65480000000000406e2101000000000000000000000000
               0000000000000000000000000000000000000040795b020000000000000000000000
               00000000000000000000000000000000000000000000000000000000000000000000
               00000000000000000000000000000000000000000000000000000000000000000000
               0080f4ba0400000000000000000000000000406d764a000000000000000000000000
               000000000000000000e0922101000000000000000000000000000000000000fc1312
               00000000000000000000000000000000000000000000000000000000000000c0912e
               01000000000000000000000000000000000000c07d4b020000000000000000000000
               00000000a8614a0000000000000000000000406a1fcd60010c
        """.replace(Regex("\\s+"), "").fromHex()))

        assertEquals(2, statusListCbor.bitsPerItem)
        assertStatusList(status, statusListCbor)
    }

    @Test
    fun testSpecVector4() = runTest {
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

        val statusListCbor = StatusList.fromDataItem(Cbor.decode("""
               a2646269747304636c737459024878daedd0410d8030100030081f0226908204244c
               025290840414111cecb7e4b8b5123a0e40669b020000000000000000000000000000
               0020b549010000000000000000000000000000000000000000000000000000000000
               00000000000000000000000000000000000000000000000000000000000000000000
               00000000000000000000000000000000000000000000000000000000000000000000
               00000000000000000000000000000000000000000000000000000000000000000000
               00000000000000000000000000000000000000000000000000000000000000000000
               00000000000000000000000000000000000000000000000000000000000000000000
               0000000000400ebb0200000000000000000000000000000000000000000000000000
               00000000000000000000000000000000000000000000000000000000000000000000
               000000000000e8c5a100000000000000000000000000000000000000000000000000
               00000000000000000000000000000000000000000000000000000000000000000000
               00000000000000000000000000000000000082280a00000000000000000000000000
               00000000000000000000000000000000000000000000000000000000000080ae9c0a
               00000000000000000000000000000000000000000000000000000000000000000000
               000000686a5640339702000000008865510000000000000000000000000000000000
               00000000000000000000000000000071dc0a0080ba55010000000000000000000000
               c0cf3daf03000000000000000008ec03dc4c04c0
        """.replace(Regex("\\s+"), "").fromHex()))

        assertEquals(4, statusListCbor.bitsPerItem)
        assertStatusList(status, statusListCbor)
    }

    @Test
    fun testSpecVector8() = runTest {
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

        val statusListCbor = StatusList.fromDataItem(Cbor.decode("""
               a2646269747308636c73745907b078daedd1639033691886d1ac6ddbb66ddbb66ddb
               b66ddbb66ddbb68d59d4d66cbe496626e94e5e9c53d57fbb9ef7ba2b158028ec2401
               000090bae724000052b6a504000000000000b4deb0120004ef5409000000008af087
               040000000001db400200000000000022f09004000000000000000000946857090000
               80789c24010000000000000050bcbe240000005a62700900000000000008c0c01200
               000074eb2c09a032900404697e09000000000000000000000000000000a8e74b0900
               000000000000e89171240000000080024d2a0100006db79a04000000000040b0de95
               0042f7a00400000000000000ddb84e02000000ca34af0400108b0f24a078ef480040
               6fec2501108e372568c6d31264e77409000000e8d60d129098b7240000000000b2b0
               b604406727480000000010a28525000048d799120081f94402202c1f4b0010b8fd25
               2019934800004098ce91a0b6d3248012cc22010040217e970000000000006aba4f02
               00000000000068997d4afbf304e2020055a69080607d280100000034e82b09000000
               00000000d2b4af040000b4de00120000000000000000c558550200000032339a0440
               031e96004230880400ddb85c020000000068ad312488dc151200408e1e9000e881eb
               250000000000b23795040000291a55028ad6bf040000000000000090a46d24000000
               00008026dc2c01746d7509000000a05d369180e0ec260100000000407a3694000000
               00000000000000004af5b90400005d3a560200000000000000a8369e040010aa0324
               00000000006881032500000000001af3b3040000108b0b2500000000000000000000
               000032b3b204000089e92ffa172c6344a09dee90000000000020694b490000000000
               000000000000000000000000000000f0b7ed3bbe3564000000803cbc2a0140504692
               0000000092f7be040059d85c020000a011c34940e8d69400000088da8e120000599b
               5e0200ba32bb040000fce719092067074b000010ba472500000000000000000080a6
               cc2a010000000000000000000000000000409fb696000000000000000000000a7697
               0400000000e4ee230900000000000000006a7b4d0202719b0411b84c02008050dd2a
               01405c269600000000000000000000000000a00943047bd976c601000000a021874b
               0000000000005080ef2400000000000000000000201a3349000000b95b4402008277
               980400c46c31090000ea785202c8d905120000d11b590200000000689137240080c0
               0d2a01404856928096985b02200fa748909ca12400000000002004fd4a00000074e9
               7809000000000000f8cb641200006d7446dacf9bcfc200404c4e96000000000088d0
               6012fccf94120000000040d83e950000202c8f48000000d09073250008c70f1200d0
               b83d2500008070ec2d0100000040412e9600000080b8cd16cda5fd180b0080047c21
               019466590900006881e32400a0b65b24000028cdbd12000000545b5f020000000000
               00000000000000000000000000a0c3b7120000000055969300a0795b490000d080b1
               2520274b4bd06a8748d05b2f480065f951020080446d24417366960080062c240100
               00004076e69200000000000000000000a0587d4b000000000000358d98e4aba6316c
               12869180329c17d435771b0400000000000000000000000080f67a49020000000000
               000080284c2e0100000000000000a9995002c2b6a904000000000000f4e975090000
               00a86f5b09000080b0fd22010000000000000000000000000000404d7b4800000000
               00000000404f1d210100000094e66d0980387d2f01d06151090028d2021200e4e037
               0982f38d04000000000000000000000000000000509c9d25f8d73c12000000000000
               00000000000064e96c09000000000000000000000000a86f1409000080fa4e940000
               200f37490000356c1cf47523180800000000000080bac69200000000000000000000
               0000000000000000000000a872950400908f192500000000000000a047c694000000
               0000204c1349407656940000b2b7bb04000000d0d974120000000000000000000040
               fbcc2001000000000000a5384a02a052d94102000000000000000080c08c2f0104e7
               59092027734a00891a5d82b04d2d010000d0bd752500000000000008d43a12000000
               000000000000000064ea79090000000000000000000028cf121234eb270900000000
               0000a872a40450b8152400000028c35312542a0b4a000000a9ba51020080d27d2601
               00000000401b3c260140932e95000000000000000048d93512000000000000000000
               00000000000000f08faf25a025869400000000007aefce327e3aa0aeb9594b0288cf
               f01240afbd2c4195432580205d2d01000000000000000000b4c9f212000000000014
               e34a0900000000000000e8adcd24000000000000803a8e9600000000000000000000
               c8c2fd120000000000000000000000c1b95d022055434b0000000040008e91000000
               006230ad0484640b09000068b9172500000000a0c3af12000000346e490980588c2b
               0100000000007432870464ee1e090000000000000000206ad74a000094623d090000
               0000000000000042739104b4c5251200000000908683240000009af29e0400000000
               00000000003df284040000000000000040b6ce9720598f4b40a2f693002018af4800
               000000f1da4502000000000000000000c8c72a120000000000000000000000d0168b
               4b0040b3fe04353d7f81
        """.replace(Regex("\\s+"), "").fromHex()))

        assertEquals(8, statusListCbor.bitsPerItem)
        assertStatusList(status, statusListCbor)
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
    fun roundtripJwt1() = runTest { testRoundtrip(5000, 1, true) }

    @Test
    fun roundtripCwt1() = runTest { testRoundtrip(5000, 1, false) }

    @Test
    fun roundtripJwt2() = runTest { testRoundtrip(5000, 2, true) }

    @Test
    fun roundtripCwt2() = runTest { testRoundtrip(5000, 2, false) }

    @Test
    fun roundtripJwt4() = runTest { testRoundtrip(5000, 4, true) }

    @Test
    fun roundtripCwt4() = runTest { testRoundtrip(5000, 4, false) }

    @Test
    fun roundtripJwt8() = runTest { testRoundtrip(5000, 8, true) }

    @Test
    fun roundtripCwt8() = runTest { testRoundtrip(5000, 8, false) }

    suspend fun testRoundtrip(size: Int, bits: Int, useJwt: Boolean) {
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
        val compressed = builder.build().compress()
        if (useJwt) {
            val jwt = compressed.serializeAsJwt(key, "foo")
            val statusList =
                StatusList.fromJwt(jwt, key.publicKey, mapOf(WebTokenCheck.SUB to "foo"))
            assertStatusList(map, statusList)
        } else {
            val cwt = compressed.serializeAsCwt(key, "foo")
            val statusList =
                StatusList.fromCwt(cwt, key.publicKey, mapOf(WebTokenCheck.SUB to "foo"))
            assertStatusList(map, statusList)
        }
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