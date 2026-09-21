# Curated FaceNet Micro-Corpus Test Data

This directory contains a lightweight, curated test corpus (~255 KB total) used for biometric face matching unit tests in `multipaz-facenet`.

## Assets & Provenance

| Filename | Identity | Description / Variations | Dimensions | Size | License | Source / Provenance |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `qualcomm_demo_1.jpg` | Person 1 (Demo A) | Frontal portrait, canonical demo pair input 1 | 250x250 | 11.0 KB | BSD-3-Clause | Qualcomm AI Hub MobileFaceNet (`v0.62.2`) |
| `qualcomm_demo_2.jpg` | Person 1 (Demo B) | Frontal portrait, canonical demo pair input 2 | 250x250 | 9.0 KB | BSD-3-Clause | Qualcomm AI Hub MobileFaceNet (`v0.62.2`) |
| `warren_portrait.jpg` | Person 2 (Warren) [^2] | Official U.S. Senate portrait with glasses (113th Congress) | 204x250 | 10.8 KB | Public Domain | U.S. Congress (`unitedstates/images`, 17 U.S.C. § 105) |
| `warren_portrait_114th.jpg` | Person 2 (Warren) [^2] | Official U.S. Senate portrait with glasses (114th Congress) | 960x1200 | 77.3 KB | Public Domain | Wikimedia Commons (`File:Elizabeth_Warren,_official_portrait,_114th_Congress.jpg`, U.S. Congress) |
| `erika_mustermann.jpg` | Person 3 (Erika 2010) [^1] | Official German identity card sample portrait (2010) | 420x540 | 117.0 KB | Public Domain | Wikimedia Commons (`File:Erika_Mustermann_2010.jpg`, Bundesdruckerei) |
| `erika_mustermann_2001.jpg` | Person 4 (Erika 2001) [^1] | Official German identity card sample portrait (2001) | 709x924 | 74.7 KB | Public Domain | Wikimedia Commons (`File:Erika_Mustermann_2001.jpg`, Bundesdruckerei) |
| `male_portrait.jpg` | Person 5 (Male) | OpenID4VCI credential portrait | 312x312 | 12.9 KB | Apache-2.0 | `multipaz-openid4vci` resources |
| `female_portrait.jpg`| Person 6 (Female) | OpenID4VCI credential portrait | 319x319 | 15.7 KB | Apache-2.0 | `multipaz-openid4vci` resources |

[^1]: **Erika Mustermann Identity Note:** While both portraits represent the fictitious German sample persona ["Erika Mustermann"](https://en.wikipedia.org/wiki/Mustermann) (see also [German Wikipedia](https://de.wikipedia.org/wiki/Mustermann#Erika_Mustermann)), the German Federal Printing Office (*Bundesdruckerei*) photographed different real-life employees for the 2001 passport and 2010 identity card document redesigns. Consequently, biometric facial recognition models correctly evaluate them as two distinct individuals (measured cosine similarity ~0.60, which is below the 0.70 same-person match threshold).

[^2]: **Elizabeth Warren Cross-Session Portrait Note:** Both portraits depict the same individual (Senator Elizabeth Warren). However, they originate from two separate congressional portrait sessions (113th and 114th Congress) with pronounced appearance and capture differences: different eyeglass frames (dark-rimmed rectangular wire vs. rimless oval), hairstyle (swept-back exposing forehead vs. front bangs covering forehead), lighting (indoor studio flash on blue backdrop vs. outdoor diffuse daylight on marble pillars), and facial expression (closed-mouth vs. open-tooth smile). Compact 128-d MobileFaceNet embeddings yield a similarity score of ~0.54 between them, which exceeds the cross-session threshold of 0.50 and remains well differentiated from unrelated identities (< 0.49), while falling below the high-certainty single-session verification threshold (0.70).

## Cryptographic Hashes (SHA-256)

```
da8b022d67b11078903fa4dcc58e84ae145744d3ec5b8bd7feb3e27cd0a9f67c  erika_mustermann.jpg
7e0bbb7fadac619b9e8c59d569cbc5e19482785bb4837734c19106ec49355d10  erika_mustermann_2001.jpg
44e56ffc0049801159f70dfd4046288ac249ddb3c203ad0b34d6222f95c74792  female_portrait.jpg
f98cf67aecc4f5dda3e23648a9fcbff81cfd1179c2bbf7bcab9be64a4b94990a  male_portrait.jpg
9dd341aaab2241ac4d55db209e1175885de4fe041c63382b8d699d5d2d025798  qualcomm_demo_1.jpg
5e9813b10bf1e756f8d2e8648251d333d36e564352fc35a5cca1b363d6b36d6e  qualcomm_demo_2.jpg
1430b73bdd7cd39a74ca20a52fc34919e186c3447b2d1a8c90f2c84ba31aef80  warren_portrait.jpg
54557a339dd1b89b15725155c992fa2611f6eccb0c882ae9d578cb76741567ae  warren_portrait_114th.jpg
```
