# FaceNet Test Data

This directory contains test assets—the MobileFaceNet machine learning model and a curated test face corpus—used for biometric face matching unit tests and sample applications in Multipaz.

## Model Provenance

The model file `model/mobile_facenet.tflite` is Qualcomm's official pre-exported MobileFaceNet model from the Qualcomm AI Hub:

- **Architecture:** MobileFaceNet (Chen et al., 2018, "MobileFaceNets: Efficient CNNs for Accurate Real-Time Face Verification on Mobile Devices")
- **Provider:** Qualcomm Technologies, Inc. ([Qualcomm AI Hub](https://aihub.qualcomm.com/))
- **Model Card:** [Hugging Face: qualcomm/MobileFaceNet](https://huggingface.co/qualcomm/MobileFaceNet)
- **Source Archive:** [Qualcomm AI Hub release v0.62.2](https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/mobile_facenet/releases/v0.62.2/mobile_facenet-tflite-float.zip)
- **License:** Apache License 2.0 (Apache-2.0)
- **Precision:** Float32
- **Tensors:**
  - Input: 2 inputs (`img1`, `img2`), each `[1, 3, 112, 112]` float32 normalized to `[0.0, 1.0]`
  - Output: 1 output (`embeddings`), shape `[2, 128]` float32

## Test Faces & Provenance

The images in `testfaces/` constitute a lightweight, curated test corpus used across unit tests in `multipaz-facenet` to validate same-person matching, cross-eyewear robustness, cross-session alignment, and differentiation between different identities.

| Filename | Identity | Description / Variations | Dimensions | Size | License | Source / Provenance |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `qualcomm_demo_1.jpg` | Person 1 (Demo A) | Frontal portrait, canonical demo pair input 1 | 250x250 | 11.0 KB | BSD-3-Clause | Qualcomm AI Hub MobileFaceNet (`v0.62.2`) |
| `qualcomm_demo_1.png` | Person 1 (Demo A) | Frontal portrait in PNG format | 250x250 | 90.8 KB | BSD-3-Clause | Converted from `qualcomm_demo_1.jpg` |
| `qualcomm_demo_1.jp2` | Person 1 (Demo A) | Frontal portrait in JPEG 2000 format | 250x250 | 15.7 KB | BSD-3-Clause | Converted from `qualcomm_demo_1.jpg` |
| `qualcomm_demo_2.jpg` | Person 1 (Demo B) | Frontal portrait, canonical demo pair input 2 | 250x250 | 9.0 KB | BSD-3-Clause | Qualcomm AI Hub MobileFaceNet (`v0.62.2`) |
| `warren_portrait.jpg` | Person 2 (Warren) | Official U.S. Senate portrait with glasses (113th Congress) | 204x250 | 10.8 KB | Public Domain | U.S. Congress (`unitedstates/images`, 17 U.S.C. § 105) |
| `warren_portrait_114th.jpg` | Person 2 (Warren) | Official U.S. Senate portrait with glasses (114th Congress) | 960x1200 | 77.3 KB | Public Domain | Wikimedia Commons (`File:Elizabeth_Warren,_official_portrait,_114th_Congress.jpg`, U.S. Congress) |
| `erika_mustermann.jpg` | Person 3 (Erika 2010) | Official German identity card sample portrait (2010) | 420x540 | 117.0 KB | Public Domain | Wikimedia Commons (`File:Erika_Mustermann_2010.jpg`, Bundesdruckerei) |
| `erika_mustermann_2001.jpg` | Person 4 (Erika 2001) | Official German identity card sample portrait (2001) | 709x924 | 74.7 KB | Public Domain | Wikimedia Commons (`File:Erika_Mustermann_2001.jpg`, Bundesdruckerei) |
| `male_portrait.jpg` | Person 5 (Male) | OpenID4VCI credential portrait | 312x312 | 12.9 KB | Apache-2.0 | `multipaz-openid4vci` resources |
| `female_portrait.jpg`| Person 6 (Female) | OpenID4VCI credential portrait | 319x319 | 15.7 KB | Apache-2.0 | `multipaz-openid4vci` resources |
| `bob_with_glasses_1.jpg` | Person 7 (Bob) | Synthetic frontal portrait with dark-rimmed glasses (Set A) | 282x384 | 51.0 KB | CC0 / Public Domain | AI-generated test sample |
| `bob_with_glasses_2.jpg` | Person 7 (Bob) | Synthetic frontal portrait with dark-rimmed glasses (Set B) | 281x384 | 40.7 KB | CC0 / Public Domain | AI-generated test sample |
| `bob_without_glasses_1.jpg` | Person 7 (Bob) | Synthetic frontal portrait without glasses (Set A) | 282x384 | 46.2 KB | CC0 / Public Domain | AI-generated test sample |
| `bob_without_glasses_2.jpg` | Person 7 (Bob) | Synthetic frontal portrait without glasses (Set B) | 282x384 | 54.7 KB | CC0 / Public Domain | AI-generated test sample |
| `alice_with_glasses_1.jpg` | Person 8 (Alice) | Synthetic frontal portrait with glasses (Set A) | 1024x1024 | 167.9 KB | CC0 / Public Domain | AI-generated test sample |
| `alice_with_glasses_2.jpg` | Person 8 (Alice) | Synthetic frontal portrait with glasses (Set B) | 1024x1024 | 149.7 KB | CC0 / Public Domain | AI-generated test sample |
| `alice_without_glasses_1.jpg` | Person 8 (Alice) | Synthetic frontal portrait without glasses (Set A) | 1024x1024 | 124.7 KB | CC0 / Public Domain | AI-generated test sample |
| `alice_without_glasses_2.jpg` | Person 8 (Alice) | Synthetic frontal portrait without glasses (Set B) | 1024x1024 | 173.9 KB | CC0 / Public Domain | AI-generated test sample |
