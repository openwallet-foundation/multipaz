# Curated FaceNet Micro-Corpus Test Data

This directory contains a lightweight, curated test corpus (~1.05 MB total) used for biometric face matching unit tests in `multipaz-facenet`.

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
| `bob_with_glasses_1.jpg` | Person 7 (Bob) [^3] | Synthetic frontal portrait with dark-rimmed glasses (Set A) | 282x384 | 51.0 KB | CC0 / Public Domain | AI-generated test sample |
| `bob_with_glasses_2.jpg` | Person 7 (Bob) [^3] | Synthetic frontal portrait with dark-rimmed glasses (Set B) | 281x384 | 40.7 KB | CC0 / Public Domain | AI-generated test sample |
| `bob_without_glasses_1.jpg` | Person 7 (Bob) [^3] | Synthetic frontal portrait without glasses (Set A) | 282x384 | 46.2 KB | CC0 / Public Domain | AI-generated test sample |
| `bob_without_glasses_2.jpg` | Person 7 (Bob) [^3] | Synthetic frontal portrait without glasses (Set B) | 282x384 | 54.7 KB | CC0 / Public Domain | AI-generated test sample |
| `alice_with_glasses_1.jpg` | Person 8 (Alice) [^4] | Synthetic frontal portrait with glasses (Set A) | 1024x1024 | 167.9 KB | CC0 / Public Domain | AI-generated test sample |
| `alice_with_glasses_2.jpg` | Person 8 (Alice) [^4] | Synthetic frontal portrait with glasses (Set B) | 1024x1024 | 149.7 KB | CC0 / Public Domain | AI-generated test sample |
| `alice_without_glasses_1.jpg` | Person 8 (Alice) [^4] | Synthetic frontal portrait without glasses (Set A) | 1024x1024 | 124.7 KB | CC0 / Public Domain | AI-generated test sample |
| `alice_without_glasses_2.jpg` | Person 8 (Alice) [^4] | Synthetic frontal portrait without glasses (Set B) | 1024x1024 | 173.9 KB | CC0 / Public Domain | AI-generated test sample |

[^1]: **Erika Mustermann Identity Note:** While both portraits represent the fictitious German sample persona ["Erika Mustermann"](https://en.wikipedia.org/wiki/Mustermann) (see also [German Wikipedia](https://de.wikipedia.org/wiki/Mustermann#Erika_Mustermann)), the German Federal Printing Office (*Bundesdruckerei*) photographed different real-life employees for the 2001 passport and 2010 identity card document redesigns. Consequently, biometric facial recognition models correctly evaluate them as two distinct individuals (measured cosine similarity ~0.60, which is below the 0.70 same-person match threshold).

[^2]: **Elizabeth Warren Cross-Session Portrait Note:** Both portraits depict the same individual (Senator Elizabeth Warren). However, they originate from two separate congressional portrait sessions (113th and 114th Congress) with pronounced appearance and capture differences: different eyeglass frames (dark-rimmed rectangular wire vs. rimless oval), hairstyle (swept-back exposing forehead vs. front bangs covering forehead), lighting (indoor studio flash on blue backdrop vs. outdoor diffuse daylight on marble pillars), and facial expression (closed-mouth vs. open-tooth smile). With BlazeFace landmark-aligned face cropping, MobileFaceNet embeddings yield a similarity score of ~0.83 between them (compared to ~0.47 for direct unaligned resize), demonstrating the efficacy of facial landmark alignment. The match threshold is asserted at $\ge 0.50$, remaining well differentiated from unrelated identities (< 0.30).

[^3]: **Bob Eyewear Variation Note:** The four Bob portraits depict the same synthetic individual across variations in eyewear (two portraits with dark-rimmed glasses and two without glasses). Biometric verification models demonstrate reliable matching within the same eyewear condition (cosine similarity ~0.61, exceeding the 0.60 threshold) and across eyewear conditions (cosine similarity ~0.54–0.67, exceeding the 0.50 cross-variation threshold), while remaining sharply separated from unrelated identities (< 0.20).

[^4]: **Alice Eyewear Variation Note:** The four Alice portraits depict the same synthetic individual across variations in eyewear (two portraits with glasses and two without glasses). Biometric verification models demonstrate reliable matching within the same eyewear condition (cosine similarity ~0.66–0.69, exceeding the 0.60 threshold) and across eyewear conditions (cosine similarity ~0.42–0.58, exceeding the 0.40 cross-variation threshold), while remaining sharply separated from unrelated identities (< 0.31).

## Cryptographic Hashes (SHA-256)

```
0c931342ec7f3c3961058d3109aff0fb34005bb443dcc397daaa3504823b337d  alice_with_glasses_1.jpg
a526f7a7c82f835bae9b178ae7003afbdbaeffc3559dbf398a8c4a604b016ac3  alice_with_glasses_2.jpg
50f2d673d1a128169ef25f6dd5c16154f6ddecd4ff00fdaf79e3f6518ba018a4  alice_without_glasses_1.jpg
21bd6a8ed960b3a58a1c80c87e18192734d82e1413a5eeaa3d42a5917d63ee04  alice_without_glasses_2.jpg
65756c131c8325d1c6b74ef62054068eda4d2be7d98c7007958f51f8ba2cf843  bob_with_glasses_1.jpg
903314a07d76c750c6c2f0475f6dba37e3cf496d134925e345c57a110666a691  bob_with_glasses_2.jpg
9a7b6bd5726888b6194b6a6b3f3498461dc547f5d48ff1ae143a2d1798ac28e7  bob_without_glasses_1.jpg
377185aa2870b7a4a00d8ef72c2c21597ff29eb28624b79432fdc6007075143a  bob_without_glasses_2.jpg
da8b022d67b11078903fa4dcc58e84ae145744d3ec5b8bd7feb3e27cd0a9f67c  erika_mustermann.jpg
7e0bbb7fadac619b9e8c59d569cbc5e19482785bb4837734c19106ec49355d10  erika_mustermann_2001.jpg
44e56ffc0049801159f70dfd4046288ac249ddb3c203ad0b34d6222f95c74792  female_portrait.jpg
f98cf67aecc4f5dda3e23648a9fcbff81cfd1179c2bbf7bcab9be64a4b94990a  male_portrait.jpg
9dd341aaab2241ac4d55db209e1175885de4fe041c63382b8d699d5d2d025798  qualcomm_demo_1.jpg
5e9813b10bf1e756f8d2e8648251d333d36e564352fc35a5cca1b363d6b36d6e  qualcomm_demo_2.jpg
1430b73bdd7cd39a74ca20a52fc34919e186c3447b2d1a8c90f2c84ba31aef80  warren_portrait.jpg
54557a339dd1b89b15725155c992fa2611f6eccb0c882ae9d578cb76741567ae  warren_portrait_114th.jpg
```
