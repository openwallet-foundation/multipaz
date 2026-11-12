# Multipaz Consent Prompt — Golden Master Screenshot Catalog

This directory contains the golden-master reference screenshots for the
[`Consent`](../src/commonMain/kotlin/org/multipaz/compose/presentment/Consent.kt) composable
in `:multipaz-compose` (captured deterministically by
[`ConsentGoldenScreenshotTest`](../src/androidUnitTest/kotlin/org/multipaz/compose/presentment/ConsentGoldenScreenshotTest.kt)
using Robolectric Native Graphics) alongside the native iOS SwiftUI
[`Consent`](../../multipaz-swiftui/src/iosMain/swift/Consent.swift) view in `:multipaz-swiftui`
(stored in [`multipaz-swiftui/golden-screenshots/`](../../multipaz-swiftui/golden-screenshots/)).

- **Run Compose golden verification tests:** `./gradlew :multipaz-compose:testDebugUnitTest`
- **Regenerate Compose goldens & catalog:** `./gradlew :multipaz-compose:testDebugUnitTest -PupdateGoldens=true`
- **Regenerate Android 17 QPR2+ EmbeddedBiometricPrompt goldens (manual):** `./multipaz-compose/golden-screenshots/update-biometric-goldens.sh`

## Categories

- **[1. Normal — Single-Credential Requests](#01_normal_single)** (`01_normal_single` — 10 scenarios)
  - [Age Verification (First-Time Share)](#age_verification_first_time)
  - [Age Verification (Returning User History)](#age_verification_returning_user)
  - [Age Over 21 Over-the-Internet (EU Age Verification Credential)](#age_over_21_eu_av_over_the_internet)
  - [Driving Privileges with Statutory Disclaimer](#driving_privileges_with_disclaimer)
  - [Address Verification with Partial Data Retention](#address_verification_partial_retention)
  - [Identity Verification with Multiple Matching Documents](#identity_multiple_matching_docs)
  - [Loyalty Card Verification](#loyalty_card)
  - [Movie Ticket Verification (SD-JWT VC)](#movie_ticket_sdjwt)
  - [Unverified Proximity Reader Requesting Non-PII (Signed Certificate Not in Trust List)](#unverified_proximity_reader_age_over_21)
  - [Anonymous Proximity Reader Requesting Non-PII (No Reader Certificate)](#anonymous_proximity_reader_age_over_21)
- **[2. Transactions — Built-In & Custom Transaction Renderers](#02_transactions)** (`02_transactions` — 4 scenarios)
  - [Payment Transaction with Card Claims & Card Switcher](#payment_with_claims)
  - [Payment Confirmation Only (Zero Attribute Claims)](#payment_confirmation_only)
  - [Transit Fare Selection (Custom Interactive Transaction Renderer)](#transit_fare_custom_renderer)
  - [AP2 Payment Mandate Delegation (Summary + Signed Mandate Details)](#ap2_payment_delegate)
- **[3. Multi-Credential & Optional Combinations](#03_multi_credential)** (`03_multi_credential` — 6 scenarios)
  - [Compound Solution: ID + Boarding Pass in a Single Use-Case](#mdl_and_boarding_pass_single_use_case)
  - [Alternative Credential Types: mDL OR Boarding Pass](#mdl_or_boarding_pass)
  - [Mandatory mDL + Optional Loyalty Card (Opting Out)](#mandatory_mdl_and_optional_loyalty)
  - [Four Movie Tickets to One Movie (multiple = true)](#four_movie_tickets_multiple)
  - [Family Boarding Passes Across Two Flights: UT 815 & UT 516 (uniqueDocSetRequired = false)](#six_boarding_passes_two_flights)
  - [Family Boarding Passes Filtered by Flight Number in DocRequestInfo (uniqueDocSetRequired = false)](#three_boarding_passes_with_filtering)
- **[4. Claim-Level Encryption (Intermediary + Encryption Target)](#04_encryption)** (`04_encryption` — 3 scenarios)
  - [Trusted Intermediary + Trusted Encryption Target (Transient)](#trusted_requester_trusted_target)
  - [Trusted Intermediary + Trusted Encryption Target (Multiple Matching Documents)](#trusted_requester_trusted_target_multiple_choices)
  - [Trusted Intermediary + Trusted Encryption Target (Retained)](#trusted_requester_trusted_target_retained)
- **[5. Documents with Issuer-Provided Metadata (MpzPass)](#05_issuer_metadata)** (`05_issuer_metadata` — 2 scenarios)
  - [MpzPass Loyalty Card with Issuer-Provided Metadata](#mpzpass_loyalty_card)
  - [MpzPass Loyalty Card — Localized Issuer Metadata (Danish da-DK)](#mpzpass_loyalty_card_danish)
- **[6. Warning (Yellow) — Unverified Requesters, Unverified Encryption Targets & Proximity Anonymous PII](#06_warning_untrusted)** (`06_warning_untrusted` — 7 scenarios)
  - [Unverified Website Requesting Age Over 18](#unverified_website_age_over_18)
  - [Unverified Local Android App Requesting Age Over 21](#unverified_app_age_over_21)
  - [Unverified Reader (With Certificate) Asking for Personally Identifiable Information (PII)](#untrusted_requester_pii_requested)
  - [Trusted Intermediary + Unverified Encryption Target (With Certificate)](#trusted_requester_untrusted_encryption_target)
  - [Anonymous Proximity Intermediary Kiosk + Trusted Encryption Target](#anonymous_requester_trusted_target)
  - [Unverified Over-the-Internet Requester (With Certificate) Asking to Retain Portrait](#untrusted_requester_intent_to_retain)
  - [Unverified Requester (With Certificate) Attaching Transaction Data](#untrusted_requester_transaction_data)
- **[7. High-Risk (Red) & Cool-Off Safeguards](#07_high_risk)** (`07_high_risk` — 6 scenarios)
  - [Anonymous Website (No Certificate) Asking for Personally Identifiable Information (PII)](#anonymous_website_pii_requested)
  - [Trusted Requester Making an Over-Broad / Unusual Request (Scroll + Cool-Off)](#trusted_requester_unusual_overbroad)
  - [Trusted Requester with Mismatched ISO 18013-5 Purpose Hint](#trusted_requester_mismatched_purpose_hint)
  - [Trusted Intermediary + Anonymous Encryption Target (No Certificate Chain)](#trusted_requester_anonymous_encryption_target)
  - [Anonymous Over-the-Internet Requester (No Certificate) Asking to Retain Portrait](#anonymous_website_intent_to_retain)
  - [Anonymous Over-the-Internet Requester (No Certificate) Attaching Transaction Data](#anonymous_website_transaction_data)
- **[8. Custom Risk & Purpose Classification Callbacks](#08_custom_policy)** (`08_custom_policy` — 2 scenarios)
  - [Custom Policy: Elevate Trusted RP Data Retention to WARNING](#custom_warning_for_trusted_rp_retaining_data)
  - [Custom Policy: Allowlist First-Party Verifier App as NORMAL](#custom_normal_for_allowlisted_first_party_app)
- **[9. Dark Theme Coverage](#09_dark_mode)** (`09_dark_mode` — 4 scenarios)
  - [Dark Mode — Normal Age Verification](#normal_age_verification)
  - [Dark Mode — Warning (Untrusted Over-the-Internet Requester)](#warning_untrusted_website)
  - [Dark Mode — High-Risk (Anonymous Website Requesting PII)](#high_risk_pii_requested)
  - [Dark Mode — Requester Info Page with Encryption Target](#requester_info_with_encryption_target)
- **[10. Localization — Danish & Right-to-Left (Arabic)](#10_localization)** (`10_localization` — 6 scenarios)
  - [Danish (da-DK) — Full Flow Across Main, Details, Chooser, Requester Info & Certificate Chain](#danish_normal_with_subpages)
  - [Danish (da-DK) — Payment Transaction with Tip Selector](#danish_payment_with_tip)
  - [Danish (da-DK) — High-Risk Anonymous Website Requesting PII & Retention](#danish_high_risk_pii_and_retention)
  - [Arabic RTL (ar-SA) — Full Flow Across Main, Details, Chooser, Requester Info & Certificate Chain](#arabic_rtl_normal_with_subpages)
  - [Arabic RTL (ar-SA) — Payment Transaction with Tip Selector](#arabic_rtl_payment_with_tip)
  - [Arabic RTL (ar-SA) — High-Risk Anonymous Website with RTL Cool-Off Progress Bar](#arabic_rtl_high_risk_pii_and_retention)
- **[11. Combined Consent & Biometric Authentication (Android EmbeddedBiometricPrompt)](#11_biometric_prompt)** (`11_biometric_prompt` — 8 scenarios)
  - [Trusted Proximity Requester Requesting Age Over 21 (Without Portrait)](#trusted_requester_age_over_21)
  - [Trusted Proximity Requester Requesting Age Over 21 & Portrait](#trusted_requester_age_over_21_and_portrait)
  - [Unverified Proximity Requester Requesting Age Over 21 (Without Portrait)](#unknown_requester_age_over_21)
  - [Unverified Proximity Requester Requesting Age Over 21 & Portrait](#unknown_requester_age_over_21_and_portrait)
  - [Anonymous Proximity Requester Requesting Age Over 21 (Without Portrait)](#anonymous_requester_age_over_21)
  - [Anonymous Proximity Requester Requesting Age Over 21 & Portrait](#anonymous_requester_age_over_21_and_portrait)
  - [Trusted Proximity Requester Requesting Age Over 50 (Falling Back to Age in Years) & Portrait](#trusted_requester_age_over_50_and_portrait)
  - [Trusted Proximity Requester Requesting Age Over 65 (False in Credential) & Portrait](#trusted_requester_age_over_65_and_portrait)

---

<a id="01_normal_single"></a>

## 1. Normal — Single-Credential Requests

Trusted relying parties requesting standard, recognized presentment purposes from a single credential, as well as unverified or anonymous proximity requests for non-PII (e.g., age_over_21 and portrait) where no warning is shown because the user is in physical proximity and the relying party is already free to capture their likeness (whereas over-the-Internet and app-to-app requests for the same claims trigger a warning).

<a id="age_verification_first_time"></a>

### `age_verification_first_time` — Age Verification (First-Time Share)

- **Relying Party:** Utopia Vineyards — Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (age_over_21, portrait)
- **Risk Assessment:** `NORMAL`

A trusted wine shop terminal requests proof of age over 21 and holder portrait for the first time. We capture the main prompt, the document claims details page, the requester info page, and the X.509 certificate chain viewer.

| | `main` — Main consent prompt | `doc_details` — Document details page | `requester_info` — Requester info page (first-time share) | `cert_chain` — X.509 Certificate chain viewer |
| :---: | :---: | :---: | :---: | :---: |
| **Compose** | <img src="01_normal_single/age_verification_first_time__main.png" alt="Compose: Age Verification (First-Time Share) - Main consent prompt" width="260" /> | <img src="01_normal_single/age_verification_first_time__doc_details.png" alt="Compose: Age Verification (First-Time Share) - Document details page" width="260" /> | <img src="01_normal_single/age_verification_first_time__requester_info.png" alt="Compose: Age Verification (First-Time Share) - Requester info page (first-time share)" width="260" /> | <img src="01_normal_single/age_verification_first_time__cert_chain.png" alt="Compose: Age Verification (First-Time Share) - X.509 Certificate chain viewer" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/age_verification_first_time__main.png" alt="SwiftUI: Age Verification (First-Time Share) - Main consent prompt" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/age_verification_first_time__doc_details.png" alt="SwiftUI: Age Verification (First-Time Share) - Document details page" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/age_verification_first_time__requester_info.png" alt="SwiftUI: Age Verification (First-Time Share) - Requester info page (first-time share)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/age_verification_first_time__cert_chain.png" alt="SwiftUI: Age Verification (First-Time Share) - X.509 Certificate chain viewer" width="260" /> |

<a id="age_verification_returning_user"></a>

### `age_verification_returning_user` — Age Verification (Returning User History)

- **Relying Party:** Utopia Vineyards — Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (age_over_21, portrait)
- **Risk Assessment:** `NORMAL`

The user has previously shared credentials with Utopia Vineyards 3 times. The bottom RP-info box notes the prior interaction, and the Requester Info page displays the first and most recent share timestamps.

| | `main` — Main consent prompt (shared before) | `requester_info` — Requester info page (3 past shares) |
| :---: | :---: | :---: |
| **Compose** | <img src="01_normal_single/age_verification_returning_user__main.png" alt="Compose: Age Verification (Returning User History) - Main consent prompt (shared before)" width="260" /> | <img src="01_normal_single/age_verification_returning_user__requester_info.png" alt="Compose: Age Verification (Returning User History) - Requester info page (3 past shares)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/age_verification_returning_user__main.png" alt="SwiftUI: Age Verification (Returning User History) - Main consent prompt (shared before)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/age_verification_returning_user__requester_info.png" alt="SwiftUI: Age Verification (Returning User History) - Requester info page (3 past shares)" width="260" /> |

<a id="age_over_21_eu_av_over_the_internet"></a>

### `age_over_21_eu_av_over_the_internet` — Age Over 21 Over-the-Internet (EU Age Verification Credential)

- **Relying Party:** Utopia Vineyards — Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** EU Age Verification (eu.europa.ec.av.1: age_over_21)
- **Risk Assessment:** `NORMAL`

An online wine store requests only age_over_21 from an EU Age Verification credential over the Internet. No portrait is requested, so the relying party only receives the piece of information that someone is over 21, not whether it is the authorized holder. This can still work if the relying party assumes that the age credential is on the authorized holder's device (that is, adequate user proofing was done at provisioning time), it is device-bound (so it cannot be copied to other devices), and the device has been configured so only the authorized holder can access it.

| | `main` — Main consent prompt (EU Age Verification age_over_21) | `doc_details` — Document details page |
| :---: | :---: | :---: |
| **Compose** | <img src="01_normal_single/age_over_21_eu_av_over_the_internet__main.png" alt="Compose: Age Over 21 Over-the-Internet (EU Age Verification Credential) - Main consent prompt (EU Age Verification age_over_21)" width="260" /> | <img src="01_normal_single/age_over_21_eu_av_over_the_internet__doc_details.png" alt="Compose: Age Over 21 Over-the-Internet (EU Age Verification Credential) - Document details page" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/age_over_21_eu_av_over_the_internet__main.png" alt="SwiftUI: Age Over 21 Over-the-Internet (EU Age Verification Credential) - Main consent prompt (EU Age Verification age_over_21)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/age_over_21_eu_av_over_the_internet__doc_details.png" alt="SwiftUI: Age Over 21 Over-the-Internet (EU Age Verification Credential) - Document details page" width="260" /> |

<a id="driving_privileges_with_disclaimer"></a>

### `driving_privileges_with_disclaimer` — Driving Privileges with Statutory Disclaimer

- **Relying Party:** Utopia Department of Transportation — Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (US Transportation profile)
- **Risk Assessment:** `NORMAL`

A roadside inspection reader requests driving privileges and displays a statutory TrustMetadata disclaimer in the bottom RP-info box. Non-salient validity/issuer claims are collapsed into 'And N more'.

| | `main` — Main consent prompt with disclaimer | `doc_details` — Document details page | `requester_info` — Requester info page with disclaimer |
| :---: | :---: | :---: | :---: |
| **Compose** | <img src="01_normal_single/driving_privileges_with_disclaimer__main.png" alt="Compose: Driving Privileges with Statutory Disclaimer - Main consent prompt with disclaimer" width="260" /> | <img src="01_normal_single/driving_privileges_with_disclaimer__doc_details.png" alt="Compose: Driving Privileges with Statutory Disclaimer - Document details page" width="260" /> | <img src="01_normal_single/driving_privileges_with_disclaimer__requester_info.png" alt="Compose: Driving Privileges with Statutory Disclaimer - Requester info page with disclaimer" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/driving_privileges_with_disclaimer__main.png" alt="SwiftUI: Driving Privileges with Statutory Disclaimer - Main consent prompt with disclaimer" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/driving_privileges_with_disclaimer__doc_details.png" alt="SwiftUI: Driving Privileges with Statutory Disclaimer - Document details page" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/driving_privileges_with_disclaimer__requester_info.png" alt="SwiftUI: Driving Privileges with Statutory Disclaimer - Requester info page with disclaimer" width="260" /> |

<a id="address_verification_partial_retention"></a>

### `address_verification_partial_retention` — Address Verification with Partial Data Retention

- **Relying Party:** Utopia Marketplace — Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** mDL (transient portrait/issue/expiry + retained name & address)
- **Risk Assessment:** `NORMAL`

An e-commerce checkout requests transient verification of portrait and validity dates alongside retained name and shipping address, splitting claims into 'shared with' and 'stored by' sections.

| | `main` — Main consent prompt (split transient/stored) | `doc_details` — Document details page |
| :---: | :---: | :---: |
| **Compose** | <img src="01_normal_single/address_verification_partial_retention__main.png" alt="Compose: Address Verification with Partial Data Retention - Main consent prompt (split transient/stored)" width="260" /> | <img src="01_normal_single/address_verification_partial_retention__doc_details.png" alt="Compose: Address Verification with Partial Data Retention - Document details page" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/address_verification_partial_retention__main.png" alt="SwiftUI: Address Verification with Partial Data Retention - Main consent prompt (split transient/stored)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/address_verification_partial_retention__doc_details.png" alt="SwiftUI: Address Verification with Partial Data Retention - Document details page" width="260" /> |

<a id="identity_multiple_matching_docs"></a>

### `identity_multiple_matching_docs` — Identity Verification with Multiple Matching Documents

- **Relying Party:** Utopia Airlines — Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** mDL or Photo ID
- **Risk Assessment:** `NORMAL`

Multiple holder documents satisfy the request. The credential card shows a chevron to open the document chooser, allowing the user to switch from Erika's Driving License to Erika's Photo ID.

| | `main` — Main prompt (Driving License selected) | `chooser` — Document chooser page | `main_switched` — Main prompt (Photo ID selected) |
| :---: | :---: | :---: | :---: |
| **Compose** | <img src="01_normal_single/identity_multiple_matching_docs__main.png" alt="Compose: Identity Verification with Multiple Matching Documents - Main prompt (Driving License selected)" width="260" /> | <img src="01_normal_single/identity_multiple_matching_docs__chooser.png" alt="Compose: Identity Verification with Multiple Matching Documents - Document chooser page" width="260" /> | <img src="01_normal_single/identity_multiple_matching_docs__main_switched.png" alt="Compose: Identity Verification with Multiple Matching Documents - Main prompt (Photo ID selected)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/identity_multiple_matching_docs__main.png" alt="SwiftUI: Identity Verification with Multiple Matching Documents - Main prompt (Driving License selected)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/identity_multiple_matching_docs__chooser.png" alt="SwiftUI: Identity Verification with Multiple Matching Documents - Document chooser page" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/identity_multiple_matching_docs__main_switched.png" alt="SwiftUI: Identity Verification with Multiple Matching Documents - Main prompt (Photo ID selected)" width="260" /> |

<a id="loyalty_card"></a>

### `loyalty_card` — Loyalty Card Verification

- **Relying Party:** Utopia Wholesale — Proximity (ISO 18013-5)
- **Protocol / Credentials:** Loyalty ID mdoc (org.multipaz.loyalty.1)
- **Risk Assessment:** `NORMAL`

A wholesale club point-of-sale terminal requests the holder's Utopia Wholesale Membership card.

| | `main` — Main consent prompt (Loyalty verification) |
| :---: | :---: |
| **Compose** | <img src="01_normal_single/loyalty_card__main.png" alt="Compose: Loyalty Card Verification - Main consent prompt (Loyalty verification)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/loyalty_card__main.png" alt="SwiftUI: Loyalty Card Verification - Main consent prompt (Loyalty verification)" width="260" /> |

<a id="movie_ticket_sdjwt"></a>

### `movie_ticket_sdjwt` — Movie Ticket Verification (SD-JWT VC)

- **Relying Party:** Utopia Cinema — Proximity (ISO 18013-5)
- **Protocol / Credentials:** Keyless SD-JWT VC (Movie Ticket)
- **Risk Assessment:** `NORMAL`

A cinema usher scanner verifies a keyless IETF SD-JWT VC movie ticket for 'Return to Utopia'.

| | `main` — Main consent prompt (Ticket verification) | `doc_details` — Document details page | `requester_info` — Requester info page |
| :---: | :---: | :---: | :---: |
| **Compose** | <img src="01_normal_single/movie_ticket_sdjwt__main.png" alt="Compose: Movie Ticket Verification (SD-JWT VC) - Main consent prompt (Ticket verification)" width="260" /> | <img src="01_normal_single/movie_ticket_sdjwt__doc_details.png" alt="Compose: Movie Ticket Verification (SD-JWT VC) - Document details page" width="260" /> | <img src="01_normal_single/movie_ticket_sdjwt__requester_info.png" alt="Compose: Movie Ticket Verification (SD-JWT VC) - Requester info page" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/movie_ticket_sdjwt__main.png" alt="SwiftUI: Movie Ticket Verification (SD-JWT VC) - Main consent prompt (Ticket verification)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/movie_ticket_sdjwt__doc_details.png" alt="SwiftUI: Movie Ticket Verification (SD-JWT VC) - Document details page" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/movie_ticket_sdjwt__requester_info.png" alt="SwiftUI: Movie Ticket Verification (SD-JWT VC) - Requester info page" width="260" /> |

<a id="unverified_proximity_reader_age_over_21"></a>

### `unverified_proximity_reader_age_over_21` — Unverified Proximity Reader Requesting Non-PII (Signed Certificate Not in Trust List)

- **Relying Party:** Harbor View Lounge — Unverified Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (age_over_21, portrait)
- **Risk Assessment:** `NORMAL (UNTRUSTED_REQUESTER)`

A proximity reader presents a valid X.509 certificate chain whose root CA is not in the wallet's trust list, requesting only non-PII (age_over_21 and portrait). Because the user is in physical proximity and the relying party is already free to capture their likeness, no warning is shown (unlike over-the-Internet or app-to-app requests, which trigger a warning). The Requester Info page still shows 'Unverified requester' with the inspectable certificate chain.

| | `main` — Main prompt (no warning for proximity non-PII) | `requester_info` — Requester info ('Unverified requester') |
| :---: | :---: | :---: |
| **Compose** | <img src="01_normal_single/unverified_proximity_reader_age_over_21__main.png" alt="Compose: Unverified Proximity Reader Requesting Non-PII (Signed Certificate Not in Trust List) - Main prompt (no warning for proximity non-PII)" width="260" /> | <img src="01_normal_single/unverified_proximity_reader_age_over_21__requester_info.png" alt="Compose: Unverified Proximity Reader Requesting Non-PII (Signed Certificate Not in Trust List) - Requester info ('Unverified requester')" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/unverified_proximity_reader_age_over_21__main.png" alt="SwiftUI: Unverified Proximity Reader Requesting Non-PII (Signed Certificate Not in Trust List) - Main prompt (no warning for proximity non-PII)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/unverified_proximity_reader_age_over_21__requester_info.png" alt="SwiftUI: Unverified Proximity Reader Requesting Non-PII (Signed Certificate Not in Trust List) - Requester info ('Unverified requester')" width="260" /> |

<a id="anonymous_proximity_reader_age_over_21"></a>

### `anonymous_proximity_reader_age_over_21` — Anonymous Proximity Reader Requesting Non-PII (No Reader Certificate)

- **Relying Party:** Anonymous Requester — Anonymous Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (age_over_21, portrait)
- **Risk Assessment:** `NORMAL (ANONYMOUS_REQUESTER)`

A proximity reader provides no reader authentication certificate at all while requesting only non-PII (age_over_21 and portrait). Because the user is in physical proximity and the relying party is already free to capture their likeness, no warning is shown (whereas an over-the-Internet or app-to-app request would show a warning). The Requester Info page displays 'Anonymous requester'.

| | `main` — Main prompt (no warning for anonymous proximity non-PII) | `requester_info` — Requester info ('Anonymous requester') |
| :---: | :---: | :---: |
| **Compose** | <img src="01_normal_single/anonymous_proximity_reader_age_over_21__main.png" alt="Compose: Anonymous Proximity Reader Requesting Non-PII (No Reader Certificate) - Main prompt (no warning for anonymous proximity non-PII)" width="260" /> | <img src="01_normal_single/anonymous_proximity_reader_age_over_21__requester_info.png" alt="Compose: Anonymous Proximity Reader Requesting Non-PII (No Reader Certificate) - Requester info ('Anonymous requester')" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/anonymous_proximity_reader_age_over_21__main.png" alt="SwiftUI: Anonymous Proximity Reader Requesting Non-PII (No Reader Certificate) - Main prompt (no warning for anonymous proximity non-PII)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/01_normal_single/anonymous_proximity_reader_age_over_21__requester_info.png" alt="SwiftUI: Anonymous Proximity Reader Requesting Non-PII (No Reader Certificate) - Requester info ('Anonymous requester')" width="260" /> |

---

<a id="02_transactions"></a>

## 2. Transactions — Built-In & Custom Transaction Renderers

Requests attaching transaction data payloads, including built-in SCA payment rendering and custom Compose transaction renderers.

<a id="payment_with_claims"></a>

### `payment_with_claims` — Payment Transaction with Card Claims & Card Switcher

- **Relying Party:** Utopia Marketplace — Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** DigitalPaymentCredential + PaymentTransaction
- **Risk Assessment:** `NORMAL`

Approving a payment transaction while also sharing masked account and holder name claims. Because the wallet holds both Utopia Bank Platinum and Utopia Bank Gold, the user can open the chooser to pick which card to pay with.

| | `main` — Main payment prompt | `chooser` — Payment card chooser (Gold vs Platinum) |
| :---: | :---: | :---: |
| **Compose** | <img src="02_transactions/payment_with_claims__main.png" alt="Compose: Payment Transaction with Card Claims &amp; Card Switcher - Main payment prompt" width="260" /> | <img src="02_transactions/payment_with_claims__chooser.png" alt="Compose: Payment Transaction with Card Claims &amp; Card Switcher - Payment card chooser (Gold vs Platinum)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/02_transactions/payment_with_claims__main.png" alt="SwiftUI: Payment Transaction with Card Claims &amp; Card Switcher - Main payment prompt" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/02_transactions/payment_with_claims__chooser.png" alt="SwiftUI: Payment Transaction with Card Claims &amp; Card Switcher - Payment card chooser (Gold vs Platinum)" width="260" /> |

<a id="payment_confirmation_only"></a>

### `payment_confirmation_only` — Payment Confirmation Only (Zero Attribute Claims)

- **Relying Party:** Utopia Wholesale — Proximity (ISO 18013-5)
- **Protocol / Credentials:** DigitalPaymentCredential (0 claims) + PaymentTransaction
- **Risk Assessment:** `NORMAL`

A cryptographic payment confirmation with zero identity/card attribute claims disclosed.

| | `main` — Main payment confirmation prompt |
| :---: | :---: |
| **Compose** | <img src="02_transactions/payment_confirmation_only__main.png" alt="Compose: Payment Confirmation Only (Zero Attribute Claims) - Main payment confirmation prompt" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/02_transactions/payment_confirmation_only__main.png" alt="SwiftUI: Payment Confirmation Only (Zero Attribute Claims) - Main payment confirmation prompt" width="260" /> |

<a id="transit_fare_custom_renderer"></a>

### `transit_fare_custom_renderer` — Transit Fare Selection (Custom Interactive Transaction Renderer)

- **Relying Party:** Utopia Metro Transit — Proximity (ISO 18013-5)
- **Protocol / Credentials:** DigitalPaymentCredential + GoldenTransitFareTransaction
- **Risk Assessment:** `NORMAL`

Demonstrates a custom TransactionDataRendererCompose with interactive FilterChips allowing the commuter to pick between a Single ride, Day pass, or 7-day pass.

| | `main` — Main transit fare prompt | `tx_details` — Transit fare transaction details page |
| :---: | :---: | :---: |
| **Compose** | <img src="02_transactions/transit_fare_custom_renderer__main.png" alt="Compose: Transit Fare Selection (Custom Interactive Transaction Renderer) - Main transit fare prompt" width="260" /> | <img src="02_transactions/transit_fare_custom_renderer__tx_details.png" alt="Compose: Transit Fare Selection (Custom Interactive Transaction Renderer) - Transit fare transaction details page" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/02_transactions/transit_fare_custom_renderer__main.png" alt="SwiftUI: Transit Fare Selection (Custom Interactive Transaction Renderer) - Main transit fare prompt" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/02_transactions/transit_fare_custom_renderer__tx_details.png" alt="SwiftUI: Transit Fare Selection (Custom Interactive Transaction Renderer) - Transit fare transaction details page" width="260" /> |

<a id="ap2_payment_delegate"></a>

### `ap2_payment_delegate` — AP2 Payment Mandate Delegation (Summary + Signed Mandate Details)

- **Relying Party:** Utopia Airlines — Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** DigitalPaymentCredential + GoldenAp2MandateTransaction
- **Risk Assessment:** `NORMAL`

Delegating payment authority via an Agent Payments Protocol (AP2) intent mandate so an AI travel assistant can purchase flights up to a spending cap when the fare drops. The summary shows the spending cap, delegate agent, and intent, while the details page exposes all signed mandate fields.

| | `main` — Main AP2 delegate mandate prompt | `tx_details` — AP2 mandate transaction details page |
| :---: | :---: | :---: |
| **Compose** | <img src="02_transactions/ap2_payment_delegate__main.png" alt="Compose: AP2 Payment Mandate Delegation (Summary + Signed Mandate Details) - Main AP2 delegate mandate prompt" width="260" /> | <img src="02_transactions/ap2_payment_delegate__tx_details.png" alt="Compose: AP2 Payment Mandate Delegation (Summary + Signed Mandate Details) - AP2 mandate transaction details page" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/02_transactions/ap2_payment_delegate__main.png" alt="SwiftUI: AP2 Payment Mandate Delegation (Summary + Signed Mandate Details) - Main AP2 delegate mandate prompt" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/02_transactions/ap2_payment_delegate__tx_details.png" alt="SwiftUI: AP2 Payment Mandate Delegation (Summary + Signed Mandate Details) - AP2 mandate transaction details page" width="260" /> |

---

<a id="03_multi_credential"></a>

## 3. Multi-Credential & Optional Combinations

Multi-credential requests exercising compound solutions, alternative credential types, and optional use-cases.

<a id="mdl_and_boarding_pass_single_use_case"></a>

### `mdl_and_boarding_pass_single_use_case` — Compound Solution: ID + Boarding Pass in a Single Use-Case

- **Relying Party:** Utopia Airlines — Proximity (ISO 18013-5)
- **Protocol / Credentials:** (mDL + Boarding Pass) OR (Photo ID + Boarding Pass)
- **Risk Assessment:** `NORMAL`

A single use-case whose options each pair an identity document with a boarding pass. The identity document card renders a chevron while the invariant boarding pass card does not; the chooser displays compound multi-card radio options.

| | `main` — Main prompt (2 credentials in 1 use-case) | `chooser` — Compound solution chooser |
| :---: | :---: | :---: |
| **Compose** | <img src="03_multi_credential/mdl_and_boarding_pass_single_use_case__main.png" alt="Compose: Compound Solution: ID + Boarding Pass in a Single Use-Case - Main prompt (2 credentials in 1 use-case)" width="260" /> | <img src="03_multi_credential/mdl_and_boarding_pass_single_use_case__chooser.png" alt="Compose: Compound Solution: ID + Boarding Pass in a Single Use-Case - Compound solution chooser" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/03_multi_credential/mdl_and_boarding_pass_single_use_case__main.png" alt="SwiftUI: Compound Solution: ID + Boarding Pass in a Single Use-Case - Main prompt (2 credentials in 1 use-case)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/03_multi_credential/mdl_and_boarding_pass_single_use_case__chooser.png" alt="SwiftUI: Compound Solution: ID + Boarding Pass in a Single Use-Case - Compound solution chooser" width="260" /> |

<a id="mdl_or_boarding_pass"></a>

### `mdl_or_boarding_pass` — Alternative Credential Types: mDL OR Boarding Pass

- **Relying Party:** Utopia Airlines Lounge — Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL OR Boarding Pass
- **Risk Assessment:** `NORMAL`

A lounge reader accepts either a Driving License or a Boarding Pass to verify lounge entry.

| | `main` — Main prompt (mDL selected) | `chooser` — Chooser between mDL and Boarding Pass |
| :---: | :---: | :---: |
| **Compose** | <img src="03_multi_credential/mdl_or_boarding_pass__main.png" alt="Compose: Alternative Credential Types: mDL OR Boarding Pass - Main prompt (mDL selected)" width="260" /> | <img src="03_multi_credential/mdl_or_boarding_pass__chooser.png" alt="Compose: Alternative Credential Types: mDL OR Boarding Pass - Chooser between mDL and Boarding Pass" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/03_multi_credential/mdl_or_boarding_pass__main.png" alt="SwiftUI: Alternative Credential Types: mDL OR Boarding Pass - Main prompt (mDL selected)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/03_multi_credential/mdl_or_boarding_pass__chooser.png" alt="SwiftUI: Alternative Credential Types: mDL OR Boarding Pass - Chooser between mDL and Boarding Pass" width="260" /> |

<a id="mandatory_mdl_and_optional_loyalty"></a>

### `mandatory_mdl_and_optional_loyalty` — Mandatory mDL + Optional Loyalty Card (Opting Out)

- **Relying Party:** Utopia Car Rentals — Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** Required mDL + Optional Loyalty ID
- **Risk Assessment:** `NORMAL`

Two separate use-cases (driving privileges + identity verification, and loyalty verification) resulting in a compound purpose subtitle ('is requesting your driving privileges, identity, and loyalty membership'). The user can open the optional card's chooser and select 'Don't share anything', dynamically updating the purpose subtitle to 'is requesting your driving privileges and identity'.

| | `main_both_selected` — Main prompt (both selected) | `chooser_optional` — Optional credential chooser ('Don't share anything') | `main_optional_omitted` — Main prompt ('Don't share anything' selected) |
| :---: | :---: | :---: | :---: |
| **Compose** | <img src="03_multi_credential/mandatory_mdl_and_optional_loyalty__main_both_selected.png" alt="Compose: Mandatory mDL + Optional Loyalty Card (Opting Out) - Main prompt (both selected)" width="260" /> | <img src="03_multi_credential/mandatory_mdl_and_optional_loyalty__chooser_optional.png" alt="Compose: Mandatory mDL + Optional Loyalty Card (Opting Out) - Optional credential chooser ('Don't share anything')" width="260" /> | <img src="03_multi_credential/mandatory_mdl_and_optional_loyalty__main_optional_omitted.png" alt="Compose: Mandatory mDL + Optional Loyalty Card (Opting Out) - Main prompt ('Don't share anything' selected)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/03_multi_credential/mandatory_mdl_and_optional_loyalty__main_both_selected.png" alt="SwiftUI: Mandatory mDL + Optional Loyalty Card (Opting Out) - Main prompt (both selected)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/03_multi_credential/mandatory_mdl_and_optional_loyalty__chooser_optional.png" alt="SwiftUI: Mandatory mDL + Optional Loyalty Card (Opting Out) - Optional credential chooser ('Don't share anything')" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/03_multi_credential/mandatory_mdl_and_optional_loyalty__main_optional_omitted.png" alt="SwiftUI: Mandatory mDL + Optional Loyalty Card (Opting Out) - Main prompt ('Don't share anything' selected)" width="260" /> |

<a id="four_movie_tickets_multiple"></a>

### `four_movie_tickets_multiple` — Four Movie Tickets to One Movie (multiple = true)

- **Relying Party:** Utopia Cinema — Proximity (OpenID4VP DCQL)
- **Protocol / Credentials:** UtopiaMovieTicket (multiple = true, 4 matching tickets)
- **Risk Assessment:** `NORMAL`

A cinema ticket scanner requests movie tickets with multiple = true. All four tickets to 'Return to Utopia' (Seats G14–G17) are selected by default and rendered as stacked credential cards sharing a single claims section. The chooser uses checkboxes so individual tickets can be toggled on or off.

| `main_all_selected` — Main prompt (all 4 movie tickets selected & stacked) | `doc_details` — Details page showing all 4 movie tickets and their claim values | `chooser_all_checked` — Multi-select chooser with all 4 movie tickets checked | `chooser_three_checked` — Multi-select chooser after unchecking Seat G17 | `main_three_selected` — Main prompt (3 movie tickets selected & stacked) |
| :---: | :---: | :---: | :---: | :---: |
| <img src="03_multi_credential/four_movie_tickets_multiple__main_all_selected.png" alt="Compose: Four Movie Tickets to One Movie (multiple = true) - Main prompt (all 4 movie tickets selected &amp; stacked)" width="260" /> | <img src="03_multi_credential/four_movie_tickets_multiple__doc_details.png" alt="Compose: Four Movie Tickets to One Movie (multiple = true) - Details page showing all 4 movie tickets and their claim values" width="260" /> | <img src="03_multi_credential/four_movie_tickets_multiple__chooser_all_checked.png" alt="Compose: Four Movie Tickets to One Movie (multiple = true) - Multi-select chooser with all 4 movie tickets checked" width="260" /> | <img src="03_multi_credential/four_movie_tickets_multiple__chooser_three_checked.png" alt="Compose: Four Movie Tickets to One Movie (multiple = true) - Multi-select chooser after unchecking Seat G17" width="260" /> | <img src="03_multi_credential/four_movie_tickets_multiple__main_three_selected.png" alt="Compose: Four Movie Tickets to One Movie (multiple = true) - Main prompt (3 movie tickets selected &amp; stacked)" width="260" /> |

<a id="six_boarding_passes_two_flights"></a>

### `six_boarding_passes_two_flights` — Family Boarding Passes Across Two Flights: UT 815 & UT 516 (uniqueDocSetRequired = false)

- **Relying Party:** Utopia Airlines — Proximity (ISO 18013-5)
- **Protocol / Credentials:** UtopiaBoardingPass (uniqueDocSetRequired = false, 3x UT 815 + 3x UT 516)
- **Risk Assessment:** `NORMAL`

An airline gate reader sets uniqueDocSetRequired = false to accept multiple family boarding passes in one tap. The wallet holds three boarding passes for flight UT 815 and three boarding passes for flight UT 516; all six are initially selected and stacked, and the traveler can open the multi-select chooser to uncheck the three UT 516 passes so only the three UT 815 passes are shared. To select only boarding passes for the flight at that gate automatically, the wallet can filter using information embedded by the reader in DocRequestInfo, as demonstrated in the following three_boarding_passes_with_filtering scenario.

| `main_all_six_selected` — Main prompt (all 6 boarding passes selected & stacked) | `chooser_all_six_checked` — Multi-select chooser with all 6 boarding passes checked | `chooser_ut815_only` — Multi-select chooser with only the 3 UT 815 boarding passes checked | `main_ut815_selected` — Main prompt (3 UT 815 boarding passes selected & stacked) |
| :---: | :---: | :---: | :---: |
| <img src="03_multi_credential/six_boarding_passes_two_flights__main_all_six_selected.png" alt="Compose: Family Boarding Passes Across Two Flights: UT 815 &amp; UT 516 (uniqueDocSetRequired = false) - Main prompt (all 6 boarding passes selected &amp; stacked)" width="260" /> | <img src="03_multi_credential/six_boarding_passes_two_flights__chooser_all_six_checked.png" alt="Compose: Family Boarding Passes Across Two Flights: UT 815 &amp; UT 516 (uniqueDocSetRequired = false) - Multi-select chooser with all 6 boarding passes checked" width="260" /> | <img src="03_multi_credential/six_boarding_passes_two_flights__chooser_ut815_only.png" alt="Compose: Family Boarding Passes Across Two Flights: UT 815 &amp; UT 516 (uniqueDocSetRequired = false) - Multi-select chooser with only the 3 UT 815 boarding passes checked" width="260" /> | <img src="03_multi_credential/six_boarding_passes_two_flights__main_ut815_selected.png" alt="Compose: Family Boarding Passes Across Two Flights: UT 815 &amp; UT 516 (uniqueDocSetRequired = false) - Main prompt (3 UT 815 boarding passes selected &amp; stacked)" width="260" /> |

<a id="three_boarding_passes_with_filtering"></a>

### `three_boarding_passes_with_filtering` — Family Boarding Passes Filtered by Flight Number in DocRequestInfo (uniqueDocSetRequired = false)

- **Relying Party:** Utopia Airlines — Proximity (ISO 18013-5)
- **Protocol / Credentials:** UtopiaBoardingPass (uniqueDocSetRequired = false, DocRequestInfo flight_number = UT 815)
- **Risk Assessment:** `NORMAL`

The same wallet holding six boarding passes across flights UT 815 and UT 516, matched to flight UT 815 using the flight number embedded by the reader in DocRequestInfo. Only the three boarding passes for flight UT 815 are matched, selected by default, and stacked.

| `main` — Main prompt (3 UT 815 boarding passes matched & stacked) | `doc_details` — Details page showing the 3 matched UT 815 boarding passes | `chooser` — Multi-select chooser with the 3 matched UT 815 boarding passes |
| :---: | :---: | :---: |
| <img src="03_multi_credential/three_boarding_passes_with_filtering__main.png" alt="Compose: Family Boarding Passes Filtered by Flight Number in DocRequestInfo (uniqueDocSetRequired = false) - Main prompt (3 UT 815 boarding passes matched &amp; stacked)" width="260" /> | <img src="03_multi_credential/three_boarding_passes_with_filtering__doc_details.png" alt="Compose: Family Boarding Passes Filtered by Flight Number in DocRequestInfo (uniqueDocSetRequired = false) - Details page showing the 3 matched UT 815 boarding passes" width="260" /> | <img src="03_multi_credential/three_boarding_passes_with_filtering__chooser.png" alt="Compose: Family Boarding Passes Filtered by Flight Number in DocRequestInfo (uniqueDocSetRequired = false) - Multi-select chooser with the 3 matched UT 815 boarding passes" width="260" /> |

---

<a id="04_encryption"></a>

## 4. Claim-Level Encryption (Intermediary + Encryption Target)

ISO/IEC 18013-5 second-edition DocRequestEncryption where an intermediary reader forwards encrypted datagroups to an upstream authority.

<a id="trusted_requester_trusted_target"></a>

### `trusted_requester_trusted_target` — Trusted Intermediary + Trusted Encryption Target (Transient)

- **Relying Party:** Utopia Airlines -> encrypted for Utopia Border Protection — Proximity (ISO 18013-5)
- **Protocol / Credentials:** Photo ID (plain org.iso.23220.2 + HPKE-encrypted datagroups)
- **Risk Assessment:** `NORMAL`

Utopia Airlines verifies basic identity while forwarding HPKE-encrypted eMRTD datagroups (SOD, DG1, DG2) to Utopia Border Protection. The Requester Info page displays trust cards and certificate chains for both entities.

| | `main` — Main prompt (plain + encrypted sections) | `requester_info` — Requester info (requester + encryption target) | `enc_target_cert_chain` — Encryption target certificate chain |
| :---: | :---: | :---: | :---: |
| **Compose** | <img src="04_encryption/trusted_requester_trusted_target__main.png" alt="Compose: Trusted Intermediary + Trusted Encryption Target (Transient) - Main prompt (plain + encrypted sections)" width="260" /> | <img src="04_encryption/trusted_requester_trusted_target__requester_info.png" alt="Compose: Trusted Intermediary + Trusted Encryption Target (Transient) - Requester info (requester + encryption target)" width="260" /> | <img src="04_encryption/trusted_requester_trusted_target__enc_target_cert_chain.png" alt="Compose: Trusted Intermediary + Trusted Encryption Target (Transient) - Encryption target certificate chain" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/04_encryption/trusted_requester_trusted_target__main.png" alt="SwiftUI: Trusted Intermediary + Trusted Encryption Target (Transient) - Main prompt (plain + encrypted sections)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/04_encryption/trusted_requester_trusted_target__requester_info.png" alt="SwiftUI: Trusted Intermediary + Trusted Encryption Target (Transient) - Requester info (requester + encryption target)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/04_encryption/trusted_requester_trusted_target__enc_target_cert_chain.png" alt="SwiftUI: Trusted Intermediary + Trusted Encryption Target (Transient) - Encryption target certificate chain" width="260" /> |

<a id="trusted_requester_trusted_target_multiple_choices"></a>

### `trusted_requester_trusted_target_multiple_choices` — Trusted Intermediary + Trusted Encryption Target (Multiple Matching Documents)

- **Relying Party:** Utopia Airlines -> encrypted for Utopia Border Protection — Proximity (ISO 18013-5)
- **Protocol / Credentials:** Photo ID (2 holder Photo IDs yielding 4 plain/encrypted combinations)
- **Risk Assessment:** `NORMAL`

When the wallet holds both Erika's Photo ID and Erika's Photo ID #2, same-document combinations render as a single grouped card with an inline chevron, while mixed-document combinations (such as Erika's Photo ID for Utopia Airlines and Erika's Photo ID #2 for Utopia Border Protection) render as two separate document cards each with an inline chevron.

| | `main` — Main prompt (Erika's Photo ID selected for both) | `chooser` — Solution chooser (4 combinations across 2 Photo IDs) | `main_switched` — Main prompt (Erika's Photo ID + Erika's Photo ID #2 selected) |
| :---: | :---: | :---: | :---: |
| **Compose** | <img src="04_encryption/trusted_requester_trusted_target_multiple_choices__main.png" alt="Compose: Trusted Intermediary + Trusted Encryption Target (Multiple Matching Documents) - Main prompt (Erika's Photo ID selected for both)" width="260" /> | <img src="04_encryption/trusted_requester_trusted_target_multiple_choices__chooser.png" alt="Compose: Trusted Intermediary + Trusted Encryption Target (Multiple Matching Documents) - Solution chooser (4 combinations across 2 Photo IDs)" width="260" /> | <img src="04_encryption/trusted_requester_trusted_target_multiple_choices__main_switched.png" alt="Compose: Trusted Intermediary + Trusted Encryption Target (Multiple Matching Documents) - Main prompt (Erika's Photo ID + Erika's Photo ID #2 selected)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/04_encryption/trusted_requester_trusted_target_multiple_choices__main.png" alt="SwiftUI: Trusted Intermediary + Trusted Encryption Target (Multiple Matching Documents) - Main prompt (Erika's Photo ID selected for both)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/04_encryption/trusted_requester_trusted_target_multiple_choices__chooser.png" alt="SwiftUI: Trusted Intermediary + Trusted Encryption Target (Multiple Matching Documents) - Solution chooser (4 combinations across 2 Photo IDs)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/04_encryption/trusted_requester_trusted_target_multiple_choices__main_switched.png" alt="SwiftUI: Trusted Intermediary + Trusted Encryption Target (Multiple Matching Documents) - Main prompt (Erika's Photo ID + Erika's Photo ID #2 selected)" width="260" /> |

<a id="trusted_requester_trusted_target_retained"></a>

### `trusted_requester_trusted_target_retained` — Trusted Intermediary + Trusted Encryption Target (Retained)

- **Relying Party:** Utopia Airlines -> encrypted & stored by Utopia Border Protection — Proximity (ISO 18013-5)
- **Protocol / Credentials:** Photo ID (intentToRetain = true on plain & encrypted claims)
- **Risk Assessment:** `NORMAL`

Same border-crossing flow with intentToRetain = true, changing the encrypted section phrasing to indicate that Utopia Airlines will forward the encrypted claims to be stored by Utopia Border Protection.

| | `main` — Main prompt (encrypted & stored) |
| :---: | :---: |
| **Compose** | <img src="04_encryption/trusted_requester_trusted_target_retained__main.png" alt="Compose: Trusted Intermediary + Trusted Encryption Target (Retained) - Main prompt (encrypted &amp; stored)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/04_encryption/trusted_requester_trusted_target_retained__main.png" alt="SwiftUI: Trusted Intermediary + Trusted Encryption Target (Retained) - Main prompt (encrypted &amp; stored)" width="260" /> |

---

<a id="05_issuer_metadata"></a>

## 5. Documents with Issuer-Provided Metadata (MpzPass)

Credentials whose document type is unknown to the wallet's DocumentTypeRepository, rendered using issuer-provided display and claim metadata embedded in the credential container (here loaded from mpzpass/utopia_mini_mart_loyalty_card_for_erika.mpzpass with custom doctype org.example.loyalty.1).

<a id="mpzpass_loyalty_card"></a>

### `mpzpass_loyalty_card` — MpzPass Loyalty Card with Issuer-Provided Metadata

- **Relying Party:** Utopia Marketplace — Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** MpzPass mdoc (org.example.loyalty.1 from mpzpass/utopia_mini_mart_loyalty_card_for_erika.mpzpass)
- **Risk Assessment:** `NORMAL`

A relying party requests claims from 'Erika's Mini Mart Loyalty Card' (loaded from mpzpass/utopia_mini_mart_loyalty_card_for_erika.mpzpass), whose doctype org.example.loyalty.1 is not registered in the wallet's DocumentTypeRepository. Because the MpzPass includes issuer-provided metadata, the wallet synthesizes a DocumentType via Document.getDocumentType(), rendering the card art, icons, collapsed 'Name' group (for given_name and family_name), and typed Date/Number values on the details page while classifying the request as NORMAL.

| | `main` — Main consent prompt (MpzPass with issuer metadata) | `doc_details` — Document details page (typed Date & Number claims) |
| :---: | :---: | :---: |
| **Compose** | <img src="05_issuer_metadata/mpzpass_loyalty_card__main.png" alt="Compose: MpzPass Loyalty Card with Issuer-Provided Metadata - Main consent prompt (MpzPass with issuer metadata)" width="260" /> | <img src="05_issuer_metadata/mpzpass_loyalty_card__doc_details.png" alt="Compose: MpzPass Loyalty Card with Issuer-Provided Metadata - Document details page (typed Date &amp; Number claims)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/05_issuer_metadata/mpzpass_loyalty_card__main.png" alt="SwiftUI: MpzPass Loyalty Card with Issuer-Provided Metadata - Main consent prompt (MpzPass with issuer metadata)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/05_issuer_metadata/mpzpass_loyalty_card__doc_details.png" alt="SwiftUI: MpzPass Loyalty Card with Issuer-Provided Metadata - Document details page (typed Date &amp; Number claims)" width="260" /> |

<a id="mpzpass_loyalty_card_danish"></a>

### `mpzpass_loyalty_card_danish` — MpzPass Loyalty Card — Localized Issuer Metadata (Danish da-DK)

- **Relying Party:** Utopia Marketplace — Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** MpzPass mdoc (org.example.loyalty.1 from mpzpass/utopia_mini_mart_loyalty_card_for_erika.mpzpass)
- **Risk Assessment:** `NORMAL (da-DK)`

The same request for mpzpass/utopia_mini_mart_loyalty_card_for_erika.mpzpass rendered under the Danish (da-DK) locale, demonstrating that the Danish claim and group display names embedded in the MpzPass metadata ('Navn', 'Fornavne', 'Efternavn', 'Forhandler', 'Medlemsnummer', 'Niveau', 'Bonuspoint', 'Gyldig fra', 'Gyldig indtil') are automatically selected alongside the localized wallet UI.

| `main` — Main consent prompt with Danish MpzPass metadata (da-DK) | `doc_details` — Document details page with Danish MpzPass metadata (da-DK) |
| :---: | :---: |
| <img src="05_issuer_metadata/mpzpass_loyalty_card_danish__main.png" alt="Compose: MpzPass Loyalty Card — Localized Issuer Metadata (Danish da-DK) - Main consent prompt with Danish MpzPass metadata (da-DK)" width="260" /> | <img src="05_issuer_metadata/mpzpass_loyalty_card_danish__doc_details.png" alt="Compose: MpzPass Loyalty Card — Localized Issuer Metadata (Danish da-DK) - Document details page with Danish MpzPass metadata (da-DK)" width="260" /> |

---

<a id="06_warning_untrusted"></a>

## 6. Warning (Yellow) — Unverified Requesters, Unverified Encryption Targets & Proximity Anonymous PII

Requests triggering ConsentRiskLevel.WARNING, which renders a top-anchored yellow warning box without a 5-second cool-off timer. Includes over-the-Internet and app-to-app requests for non-PII, unverified requesters (who signed with a certificate not in the wallet's trust list) requesting PII, retention, or transaction data, unverified encryption targets with a certificate chain, and anonymous proximity readers requesting PII.

<a id="unverified_website_age_over_18"></a>

### `unverified_website_age_over_18` — Unverified Website Requesting Age Over 18

- **Relying Party:** https://age-check.utopia-gaming.example.com — Unverified Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** mDL (age_over_18, portrait)
- **Risk Assessment:** `WARNING (UNTRUSTED_REQUESTER)`

An over-the-Internet website origin not in the wallet's trust list requests age_over_18 and portrait. Unlike a proximity request, an over-the-Internet request for non-PII triggers a WARNING: the headline displays the origin URL and the yellow warning box states that the website is unknown.

| | `main` — Main prompt (untrusted website warning) | `requester_info` — Requester info (Website origin + client ID) |
| :---: | :---: | :---: |
| **Compose** | <img src="06_warning_untrusted/unverified_website_age_over_18__main.png" alt="Compose: Unverified Website Requesting Age Over 18 - Main prompt (untrusted website warning)" width="260" /> | <img src="06_warning_untrusted/unverified_website_age_over_18__requester_info.png" alt="Compose: Unverified Website Requesting Age Over 18 - Requester info (Website origin + client ID)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/06_warning_untrusted/unverified_website_age_over_18__main.png" alt="SwiftUI: Unverified Website Requesting Age Over 18 - Main prompt (untrusted website warning)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/06_warning_untrusted/unverified_website_age_over_18__requester_info.png" alt="SwiftUI: Unverified Website Requesting Age Over 18 - Requester info (Website origin + client ID)" width="260" /> |

<a id="unverified_app_age_over_21"></a>

### `unverified_app_age_over_21` — Unverified Local Android App Requesting Age Over 21

- **Relying Party:** Utopia Nightclub Scanner (org.utopia.nightclub.scanner) — App-to-app
- **Protocol / Credentials:** mDL (age_over_21, portrait)
- **Risk Assessment:** `WARNING (ANONYMOUS_REQUESTER)`

An app-to-app request from a local Android application not in a trust list requesting age_over_21 and portrait. Unlike a proximity request, an app-to-app request for non-PII triggers a WARNING: the prompt resolves the app name and icon via PackageManager and displays an unknown application warning.

| | `main` — Main prompt (untrusted app warning) |
| :---: | :---: |
| **Compose** | <img src="06_warning_untrusted/unverified_app_age_over_21__main.png" alt="Compose: Unverified Local Android App Requesting Age Over 21 - Main prompt (untrusted app warning)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/06_warning_untrusted/unverified_app_age_over_21__main.png" alt="SwiftUI: Unverified Local Android App Requesting Age Over 21 - Main prompt (untrusted app warning)" width="260" /> |

<a id="untrusted_requester_pii_requested"></a>

### `untrusted_requester_pii_requested` — Unverified Reader (With Certificate) Asking for Personally Identifiable Information (PII)

- **Relying Party:** Harbor View Lounge — Unverified Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (given_name, family_name, birth_date, portrait)
- **Risk Assessment:** `WARNING (UNTRUSTED_REQUESTER, PII_REQUESTED)`

An unverified verifier that signed the request with a certificate not in the wallet's trust list requests PII attributes. Because the verifier presented a certificate chain (or is in physical proximity), this is classified as WARNING rather than HIGH_RISK: a yellow warning box is anchored at the top without a 5-second cool-off timer.

| | `main` — Main prompt (unverified reader requesting PII) | `requester_info` — Requester info ('Unverified requester') |
| :---: | :---: | :---: |
| **Compose** | <img src="06_warning_untrusted/untrusted_requester_pii_requested__main.png" alt="Compose: Unverified Reader (With Certificate) Asking for Personally Identifiable Information (PII) - Main prompt (unverified reader requesting PII)" width="260" /> | <img src="06_warning_untrusted/untrusted_requester_pii_requested__requester_info.png" alt="Compose: Unverified Reader (With Certificate) Asking for Personally Identifiable Information (PII) - Requester info ('Unverified requester')" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/06_warning_untrusted/untrusted_requester_pii_requested__main.png" alt="SwiftUI: Unverified Reader (With Certificate) Asking for Personally Identifiable Information (PII) - Main prompt (unverified reader requesting PII)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/06_warning_untrusted/untrusted_requester_pii_requested__requester_info.png" alt="SwiftUI: Unverified Reader (With Certificate) Asking for Personally Identifiable Information (PII) - Requester info ('Unverified requester')" width="260" /> |

<a id="trusted_requester_untrusted_encryption_target"></a>

### `trusted_requester_untrusted_encryption_target` — Trusted Intermediary + Unverified Encryption Target (With Certificate)

- **Relying Party:** Utopia Airlines -> encrypted for Unverified Recipient — Proximity (ISO 18013-5)
- **Protocol / Credentials:** Photo ID (plain org.iso.23220.2 + encrypted datagroups to unverified certificate)
- **Risk Assessment:** `WARNING (UNTRUSTED_ENCRYPTION_TARGET)`

Utopia Airlines is trusted, and the encryption recipient for the passport datagroups provided an X.509 certificate chain whose root CA is not in the wallet's trust list. Because the encryption target provided a certificate chain, this is classified as WARNING.

| | `main` — Main prompt (unverified encryption target warning) | `requester_info` — Requester info (verified requester + unverified target) |
| :---: | :---: | :---: |
| **Compose** | <img src="06_warning_untrusted/trusted_requester_untrusted_encryption_target__main.png" alt="Compose: Trusted Intermediary + Unverified Encryption Target (With Certificate) - Main prompt (unverified encryption target warning)" width="260" /> | <img src="06_warning_untrusted/trusted_requester_untrusted_encryption_target__requester_info.png" alt="Compose: Trusted Intermediary + Unverified Encryption Target (With Certificate) - Requester info (verified requester + unverified target)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/06_warning_untrusted/trusted_requester_untrusted_encryption_target__main.png" alt="SwiftUI: Trusted Intermediary + Unverified Encryption Target (With Certificate) - Main prompt (unverified encryption target warning)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/06_warning_untrusted/trusted_requester_untrusted_encryption_target__requester_info.png" alt="SwiftUI: Trusted Intermediary + Unverified Encryption Target (With Certificate) - Requester info (verified requester + unverified target)" width="260" /> |

<a id="anonymous_requester_trusted_target"></a>

### `anonymous_requester_trusted_target` — Anonymous Proximity Intermediary Kiosk + Trusted Encryption Target

- **Relying Party:** Anonymous Kiosk -> encrypted for Utopia Border Protection — Proximity (ISO 18013-5)
- **Protocol / Credentials:** Photo ID (HPKE-encrypted datagroups only)
- **Risk Assessment:** `WARNING (ANONYMOUS_REQUESTER, PII_REQUESTED)`

An unauthenticated proximity airport kiosk forwards HPKE-encrypted passport datagroups to a verified Utopia Border Protection key. Because this is a proximity request, an anonymous reader requesting PII is classified as WARNING (and a wallet whose threat model accepts an untrusted intermediary forwarding encrypted claims to a trusted target could override classifyRiskFn to classify this as NORMAL).

| | `main` — Main prompt (anonymous proximity kiosk + trusted encryption target) | `requester_info` — Requester info (anonymous requester + verified target) |
| :---: | :---: | :---: |
| **Compose** | <img src="06_warning_untrusted/anonymous_requester_trusted_target__main.png" alt="Compose: Anonymous Proximity Intermediary Kiosk + Trusted Encryption Target - Main prompt (anonymous proximity kiosk + trusted encryption target)" width="260" /> | <img src="06_warning_untrusted/anonymous_requester_trusted_target__requester_info.png" alt="Compose: Anonymous Proximity Intermediary Kiosk + Trusted Encryption Target - Requester info (anonymous requester + verified target)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/06_warning_untrusted/anonymous_requester_trusted_target__main.png" alt="SwiftUI: Anonymous Proximity Intermediary Kiosk + Trusted Encryption Target - Main prompt (anonymous proximity kiosk + trusted encryption target)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/06_warning_untrusted/anonymous_requester_trusted_target__requester_info.png" alt="SwiftUI: Anonymous Proximity Intermediary Kiosk + Trusted Encryption Target - Requester info (anonymous requester + verified target)" width="260" /> |

<a id="untrusted_requester_intent_to_retain"></a>

### `untrusted_requester_intent_to_retain` — Unverified Over-the-Internet Requester (With Certificate) Asking to Retain Portrait

- **Relying Party:** https://age-check.utopia-gaming.example.com — Unverified Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** mDL (age_over_21 & portrait with intentToRetain = true)
- **Risk Assessment:** `WARNING (UNTRUSTED_REQUESTER, INTENT_TO_RETAIN)`

An unverified over-the-Internet website that signed the request with a certificate not in the wallet's trust list requests age_over_21 and portrait with intentToRetain = true, adding the INTENT_TO_RETAIN warning paragraph inside the yellow warning box.

| | `main` — Main prompt (unverified requester retention warning) |
| :---: | :---: |
| **Compose** | <img src="06_warning_untrusted/untrusted_requester_intent_to_retain__main.png" alt="Compose: Unverified Over-the-Internet Requester (With Certificate) Asking to Retain Portrait - Main prompt (unverified requester retention warning)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/06_warning_untrusted/untrusted_requester_intent_to_retain__main.png" alt="SwiftUI: Unverified Over-the-Internet Requester (With Certificate) Asking to Retain Portrait - Main prompt (unverified requester retention warning)" width="260" /> |

<a id="untrusted_requester_transaction_data"></a>

### `untrusted_requester_transaction_data` — Unverified Requester (With Certificate) Attaching Transaction Data

- **Relying Party:** https://pay.unverified-merchant.example.com — Unverified Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** DigitalPaymentCredential + PaymentTransaction
- **Risk Assessment:** `WARNING (UNTRUSTED_REQUESTER, TRANSACTION_DATA)`

An unverified website that signed the request with a certificate not in the wallet's trust list attaches a payment transaction payload, triggering ConsentRiskReason.TRANSACTION_DATA at the WARNING level.

| | `main` — Main prompt (unverified requester transaction warning) |
| :---: | :---: |
| **Compose** | <img src="06_warning_untrusted/untrusted_requester_transaction_data__main.png" alt="Compose: Unverified Requester (With Certificate) Attaching Transaction Data - Main prompt (unverified requester transaction warning)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/06_warning_untrusted/untrusted_requester_transaction_data__main.png" alt="SwiftUI: Unverified Requester (With Certificate) Attaching Transaction Data - Main prompt (unverified requester transaction warning)" width="260" /> |

---

<a id="07_high_risk"></a>

## 7. High-Risk (Red) & Cool-Off Safeguards

Requests triggering ConsentRiskLevel.HIGH_RISK due to anonymous remote/app requesters asking for PII, retention, or transaction data, over-broad/unusual claim combinations, mismatched purpose hints, or anonymous encryption targets with no certificate chain. Enforces a 5-second cool-off timer on the Continue button.

<a id="anonymous_website_pii_requested"></a>

### `anonymous_website_pii_requested` — Anonymous Website (No Certificate) Asking for Personally Identifiable Information (PII)

- **Relying Party:** https://verify.unknown-portal.example.com — Anonymous Over-the-Internet
- **Protocol / Credentials:** mDL (given_name, family_name, birth_date, portrait)
- **Risk Assessment:** `HIGH_RISK (ANONYMOUS_REQUESTER, PII_REQUESTED)`

An anonymous over-the-Internet website (providing no X.509 signature certificate at all) requests PII attributes. A red danger box is anchored at the top and the primary button is disabled in the 'Wait' cool-off state for 5 seconds before transitioning to 'Continue'. The user can tap 'More information' to inspect the anonymous requester details.

| | `main_waiting` — Main prompt ('Wait' cool-off active) | `main_ready` — Main prompt ('Continue' enabled after 5s) | `requester_info` — Requester info ('Anonymous requester') |
| :---: | :---: | :---: | :---: |
| **Compose** | <img src="07_high_risk/anonymous_website_pii_requested__main_waiting.png" alt="Compose: Anonymous Website (No Certificate) Asking for Personally Identifiable Information (PII) - Main prompt ('Wait' cool-off active)" width="260" /> | <img src="07_high_risk/anonymous_website_pii_requested__main_ready.png" alt="Compose: Anonymous Website (No Certificate) Asking for Personally Identifiable Information (PII) - Main prompt ('Continue' enabled after 5s)" width="260" /> | <img src="07_high_risk/anonymous_website_pii_requested__requester_info.png" alt="Compose: Anonymous Website (No Certificate) Asking for Personally Identifiable Information (PII) - Requester info ('Anonymous requester')" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/07_high_risk/anonymous_website_pii_requested__main_waiting.png" alt="SwiftUI: Anonymous Website (No Certificate) Asking for Personally Identifiable Information (PII) - Main prompt ('Wait' cool-off active)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/07_high_risk/anonymous_website_pii_requested__main_ready.png" alt="SwiftUI: Anonymous Website (No Certificate) Asking for Personally Identifiable Information (PII) - Main prompt ('Continue' enabled after 5s)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/07_high_risk/anonymous_website_pii_requested__requester_info.png" alt="SwiftUI: Anonymous Website (No Certificate) Asking for Personally Identifiable Information (PII) - Requester info ('Anonymous requester')" width="260" /> |

<a id="trusted_requester_unusual_overbroad"></a>

### `trusted_requester_unusual_overbroad` — Trusted Requester Making an Over-Broad / Unusual Request (Scroll + Cool-Off)

- **Relying Party:** Utopia Vineyards — Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (all 20+ data elements)
- **Risk Assessment:** `HIGH_RISK (UNUSUAL_REQUEST)`

Even though Utopia Vineyards is in the trust list, requesting every mDL attribute cannot be classified into any standard PresentmentPurpose. Because the claim list overflows the viewport, the user must first scroll to the bottom ('More') before the 5-second 'Wait' cool-off timer starts.

| | `main_top` — Top of prompt ('More' scroll button) | `main_scrolled_waiting` — Scrolled to bottom ('Wait' cool-off) | `main_scrolled_ready` — Cool-off complete ('Continue' enabled) |
| :---: | :---: | :---: | :---: |
| **Compose** | <img src="07_high_risk/trusted_requester_unusual_overbroad__main_top.png" alt="Compose: Trusted Requester Making an Over-Broad / Unusual Request (Scroll + Cool-Off) - Top of prompt ('More' scroll button)" width="260" /> | <img src="07_high_risk/trusted_requester_unusual_overbroad__main_scrolled_waiting.png" alt="Compose: Trusted Requester Making an Over-Broad / Unusual Request (Scroll + Cool-Off) - Scrolled to bottom ('Wait' cool-off)" width="260" /> | <img src="07_high_risk/trusted_requester_unusual_overbroad__main_scrolled_ready.png" alt="Compose: Trusted Requester Making an Over-Broad / Unusual Request (Scroll + Cool-Off) - Cool-off complete ('Continue' enabled)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/07_high_risk/trusted_requester_unusual_overbroad__main_top.png" alt="SwiftUI: Trusted Requester Making an Over-Broad / Unusual Request (Scroll + Cool-Off) - Top of prompt ('More' scroll button)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/07_high_risk/trusted_requester_unusual_overbroad__main_scrolled_waiting.png" alt="SwiftUI: Trusted Requester Making an Over-Broad / Unusual Request (Scroll + Cool-Off) - Scrolled to bottom ('Wait' cool-off)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/07_high_risk/trusted_requester_unusual_overbroad__main_scrolled_ready.png" alt="SwiftUI: Trusted Requester Making an Over-Broad / Unusual Request (Scroll + Cool-Off) - Cool-off complete ('Continue' enabled)" width="260" /> |

<a id="trusted_requester_mismatched_purpose_hint"></a>

### `trusted_requester_mismatched_purpose_hint` — Trusted Requester with Mismatched ISO 18013-5 Purpose Hint

- **Relying Party:** Utopia Vineyards — Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (purposeHint = Age Verification, but requests name & address)
- **Risk Assessment:** `HIGH_RISK (UNUSUAL_REQUEST)`

The reader declares the ISO 18013-5 purposeHint for Age Verification (1) in DeviceRequestInfo, but actually requests given_name, family_name, and resident_address.

| | `main` — Main prompt (mismatched purpose hint danger) |
| :---: | :---: |
| **Compose** | <img src="07_high_risk/trusted_requester_mismatched_purpose_hint__main.png" alt="Compose: Trusted Requester with Mismatched ISO 18013-5 Purpose Hint - Main prompt (mismatched purpose hint danger)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/07_high_risk/trusted_requester_mismatched_purpose_hint__main.png" alt="SwiftUI: Trusted Requester with Mismatched ISO 18013-5 Purpose Hint - Main prompt (mismatched purpose hint danger)" width="260" /> |

<a id="trusted_requester_anonymous_encryption_target"></a>

### `trusted_requester_anonymous_encryption_target` — Trusted Intermediary + Anonymous Encryption Target (No Certificate Chain)

- **Relying Party:** Utopia Airlines -> encrypted for Anonymous Recipient — Proximity (ISO 18013-5)
- **Protocol / Credentials:** Photo ID (plain org.iso.23220.2 + encrypted datagroups to raw public key)
- **Risk Assessment:** `HIGH_RISK (UNTRUSTED_ENCRYPTION_TARGET)`

Utopia Airlines is trusted, but the encryption recipient for the passport datagroups provided only a raw public key with no X.509 certificate chain at all.

| | `main` — Main prompt (anonymous encryption target danger) | `requester_info` — Requester info (verified requester + anonymous target) |
| :---: | :---: | :---: |
| **Compose** | <img src="07_high_risk/trusted_requester_anonymous_encryption_target__main.png" alt="Compose: Trusted Intermediary + Anonymous Encryption Target (No Certificate Chain) - Main prompt (anonymous encryption target danger)" width="260" /> | <img src="07_high_risk/trusted_requester_anonymous_encryption_target__requester_info.png" alt="Compose: Trusted Intermediary + Anonymous Encryption Target (No Certificate Chain) - Requester info (verified requester + anonymous target)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/07_high_risk/trusted_requester_anonymous_encryption_target__main.png" alt="SwiftUI: Trusted Intermediary + Anonymous Encryption Target (No Certificate Chain) - Main prompt (anonymous encryption target danger)" width="260" /> | <img src="../../multipaz-swiftui/golden-screenshots/07_high_risk/trusted_requester_anonymous_encryption_target__requester_info.png" alt="SwiftUI: Trusted Intermediary + Anonymous Encryption Target (No Certificate Chain) - Requester info (verified requester + anonymous target)" width="260" /> |

<a id="anonymous_website_intent_to_retain"></a>

### `anonymous_website_intent_to_retain` — Anonymous Over-the-Internet Requester (No Certificate) Asking to Retain Portrait

- **Relying Party:** https://age-check.utopia-gaming.example.com — Anonymous Over-the-Internet
- **Protocol / Credentials:** mDL (age_over_21 & portrait with intentToRetain = true)
- **Risk Assessment:** `HIGH_RISK (ANONYMOUS_REQUESTER, INTENT_TO_RETAIN)`

An anonymous over-the-Internet website (providing no X.509 signature certificate) requests age_over_21 and portrait with intentToRetain = true. For anonymous non-proximity requests, retaining a claim with sensitivity >= PORTRAIT_IMAGE escalates the prompt from WARNING to HIGH_RISK.

| | `main` — Main prompt (anonymous website retention danger) |
| :---: | :---: |
| **Compose** | <img src="07_high_risk/anonymous_website_intent_to_retain__main.png" alt="Compose: Anonymous Over-the-Internet Requester (No Certificate) Asking to Retain Portrait - Main prompt (anonymous website retention danger)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/07_high_risk/anonymous_website_intent_to_retain__main.png" alt="SwiftUI: Anonymous Over-the-Internet Requester (No Certificate) Asking to Retain Portrait - Main prompt (anonymous website retention danger)" width="260" /> |

<a id="anonymous_website_transaction_data"></a>

### `anonymous_website_transaction_data` — Anonymous Over-the-Internet Requester (No Certificate) Attaching Transaction Data

- **Relying Party:** https://pay.unverified-merchant.example.com — Anonymous Over-the-Internet
- **Protocol / Credentials:** DigitalPaymentCredential + PaymentTransaction
- **Risk Assessment:** `HIGH_RISK (ANONYMOUS_REQUESTER, TRANSACTION_DATA)`

An anonymous website (providing no X.509 signature certificate) attaches a payment transaction payload, triggering ConsentRiskReason.TRANSACTION_DATA at the HIGH_RISK level.

| | `main` — Main prompt (anonymous website transaction danger) |
| :---: | :---: |
| **Compose** | <img src="07_high_risk/anonymous_website_transaction_data__main.png" alt="Compose: Anonymous Over-the-Internet Requester (No Certificate) Attaching Transaction Data - Main prompt (anonymous website transaction danger)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/07_high_risk/anonymous_website_transaction_data__main.png" alt="SwiftUI: Anonymous Over-the-Internet Requester (No Certificate) Attaching Transaction Data - Main prompt (anonymous website transaction danger)" width="260" /> |

---

<a id="08_custom_policy"></a>

## 8. Custom Risk & Purpose Classification Callbacks

Demonstrates overriding classifyPurposesFn and classifyRiskFn on Consent to enforce custom wallet security policies.

<a id="custom_warning_for_trusted_rp_retaining_data"></a>

### `custom_warning_for_trusted_rp_retaining_data` — Custom Policy: Elevate Trusted RP Data Retention to WARNING

- **Relying Party:** Utopia Marketplace — Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** mDL (retained name & address) + custom classifyRiskFn
- **Risk Assessment:** `WARNING (INTENT_TO_RETAIN via custom classifyRiskFn)`

A privacy-focused wallet overrides classifyRiskFn so that even when a relying party is in a trust list, any request with intentToRetain = true displays a top yellow warning box highlighting data retention.

| | `main` — Main prompt (custom retention warning) |
| :---: | :---: |
| **Compose** | <img src="08_custom_policy/custom_warning_for_trusted_rp_retaining_data__main.png" alt="Compose: Custom Policy: Elevate Trusted RP Data Retention to WARNING - Main prompt (custom retention warning)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/08_custom_policy/custom_warning_for_trusted_rp_retaining_data__main.png" alt="SwiftUI: Custom Policy: Elevate Trusted RP Data Retention to WARNING - Main prompt (custom retention warning)" width="260" /> |

<a id="custom_normal_for_allowlisted_first_party_app"></a>

### `custom_normal_for_allowlisted_first_party_app` — Custom Policy: Allowlist First-Party Verifier App as NORMAL

- **Relying Party:** Utopia Wallet Verifier (gov.utopia.wallet.verifier) — App-to-app
- **Protocol / Credentials:** mDL (age_over_21, portrait) + custom classifyRiskFn
- **Risk Assessment:** `NORMAL (via custom classifyRiskFn)`

A wallet overrides classifyRiskFn to treat its companion first-party verifier app (gov.utopia.wallet.verifier) as NORMAL even when invoked without an X.509 reader certificate.

| | `main` — Main prompt (allowlisted app as NORMAL) |
| :---: | :---: |
| **Compose** | <img src="08_custom_policy/custom_normal_for_allowlisted_first_party_app__main.png" alt="Compose: Custom Policy: Allowlist First-Party Verifier App as NORMAL - Main prompt (allowlisted app as NORMAL)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/08_custom_policy/custom_normal_for_allowlisted_first_party_app__main.png" alt="SwiftUI: Custom Policy: Allowlist First-Party Verifier App as NORMAL - Main prompt (allowlisted app as NORMAL)" width="260" /> |

---

<a id="09_dark_mode"></a>

## 9. Dark Theme Coverage

Verifies dark-theme surface contrast, segmented container colors, warning/danger box palettes, and sub-page legibility.

<a id="normal_age_verification"></a>

### `normal_age_verification` — Dark Mode — Normal Age Verification

- **Relying Party:** Utopia Vineyards — Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (age_over_21, portrait)
- **Risk Assessment:** `NORMAL (Dark Theme)`

Dark mode rendering of the standard NORMAL consent prompt with surfaceContainerHigh segmented containers.

| | `main` — Main prompt (Dark mode NORMAL) |
| :---: | :---: |
| **Compose** | <img src="09_dark_mode/normal_age_verification__main.png" alt="Compose: Dark Mode — Normal Age Verification - Main prompt (Dark mode NORMAL)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/09_dark_mode/normal_age_verification__main.png" alt="SwiftUI: Dark Mode — Normal Age Verification - Main prompt (Dark mode NORMAL)" width="260" /> |

<a id="warning_untrusted_website"></a>

### `warning_untrusted_website` — Dark Mode — Warning (Untrusted Over-the-Internet Requester)

- **Relying Party:** https://age-check.utopia-gaming.example.com — Unverified Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** mDL (age_over_21, portrait)
- **Risk Assessment:** `WARNING (Dark Theme)`

Dark mode rendering of the amber warning box (#3E3012 fill / #F9AB00 border) for an untrusted over-the-Internet request.

| | `main` — Main prompt (Dark mode WARNING) |
| :---: | :---: |
| **Compose** | <img src="09_dark_mode/warning_untrusted_website__main.png" alt="Compose: Dark Mode — Warning (Untrusted Over-the-Internet Requester) - Main prompt (Dark mode WARNING)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/09_dark_mode/warning_untrusted_website__main.png" alt="SwiftUI: Dark Mode — Warning (Untrusted Over-the-Internet Requester) - Main prompt (Dark mode WARNING)" width="260" /> |

<a id="high_risk_pii_requested"></a>

### `high_risk_pii_requested` — Dark Mode — High-Risk (Anonymous Website Requesting PII)

- **Relying Party:** https://verify.unknown-portal.example.com — Anonymous Over-the-Internet
- **Protocol / Credentials:** mDL (given_name, family_name, birth_date, portrait)
- **Risk Assessment:** `HIGH_RISK (Dark Theme)`

Dark mode rendering of the red danger box (#4A1C1A fill / #B3261E border) and disabled 'Wait' cool-off button for an anonymous website requesting PII.

| | `main` — Main prompt (Dark mode HIGH_RISK) |
| :---: | :---: |
| **Compose** | <img src="09_dark_mode/high_risk_pii_requested__main.png" alt="Compose: Dark Mode — High-Risk (Anonymous Website Requesting PII) - Main prompt (Dark mode HIGH_RISK)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/09_dark_mode/high_risk_pii_requested__main.png" alt="SwiftUI: Dark Mode — High-Risk (Anonymous Website Requesting PII) - Main prompt (Dark mode HIGH_RISK)" width="260" /> |

<a id="requester_info_with_encryption_target"></a>

### `requester_info_with_encryption_target` — Dark Mode — Requester Info Page with Encryption Target

- **Relying Party:** Utopia Airlines -> Utopia Border Protection — Proximity (ISO 18013-5)
- **Protocol / Credentials:** Photo ID (plain + encrypted datagroups)
- **Risk Assessment:** `NORMAL (Dark Theme)`

Dark mode rendering of the Requester Info sub-page showing verified requester and encryption target trust cards, history, and certificate chain entries.

| | `requester_info` — Requester info page (Dark mode) |
| :---: | :---: |
| **Compose** | <img src="09_dark_mode/requester_info_with_encryption_target__requester_info.png" alt="Compose: Dark Mode — Requester Info Page with Encryption Target - Requester info page (Dark mode)" width="260" /> |
| **SwiftUI** | <img src="../../multipaz-swiftui/golden-screenshots/09_dark_mode/requester_info_with_encryption_target__requester_info.png" alt="SwiftUI: Dark Mode — Requester Info Page with Encryption Target - Requester info page (Dark mode)" width="260" /> |

---

<a id="10_localization"></a>

## 10. Localization — Danish & Right-to-Left (Arabic)

Verifies complete translation of all Consent UI strings, localized document/claim names, date formatting, and Right-to-Left (RTL) layout mirroring across Danish (da-DK) and Arabic (ar-SA).

<a id="danish_normal_with_subpages"></a>

### `danish_normal_with_subpages` — Danish (da-DK) — Full Flow Across Main, Details, Chooser, Requester Info & Certificate Chain

- **Relying Party:** Utopia Car Rentals — Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** mDL OR Photo ID with partial retention
- **Risk Assessment:** `NORMAL (da-DK)`

Full Danish (da-DK) localization of the main consent prompt (with both shared and stored claim sections, credential switcher, and returning-user RP info box), Document Details ('Se detaljer' -> 'Detaljer'), Document Chooser ('Vælg hvad der skal deles'), Requester Info ('Anmoders information'), and X.509 Certificate Chain viewer ('Certifikatkæde').

| `main` — Main prompt (Danish da-DK) | `doc_details` — Document details page (Danish da-DK) | `chooser` — Document chooser page (Danish da-DK) | `requester_info` — Requester info page (Danish da-DK) | `cert_chain` — X.509 Certificate chain viewer (Danish da-DK) |
| :---: | :---: | :---: | :---: | :---: |
| <img src="10_localization/danish_normal_with_subpages__main.png" alt="Compose: Danish (da-DK) — Full Flow Across Main, Details, Chooser, Requester Info &amp; Certificate Chain - Main prompt (Danish da-DK)" width="260" /> | <img src="10_localization/danish_normal_with_subpages__doc_details.png" alt="Compose: Danish (da-DK) — Full Flow Across Main, Details, Chooser, Requester Info &amp; Certificate Chain - Document details page (Danish da-DK)" width="260" /> | <img src="10_localization/danish_normal_with_subpages__chooser.png" alt="Compose: Danish (da-DK) — Full Flow Across Main, Details, Chooser, Requester Info &amp; Certificate Chain - Document chooser page (Danish da-DK)" width="260" /> | <img src="10_localization/danish_normal_with_subpages__requester_info.png" alt="Compose: Danish (da-DK) — Full Flow Across Main, Details, Chooser, Requester Info &amp; Certificate Chain - Requester info page (Danish da-DK)" width="260" /> | <img src="10_localization/danish_normal_with_subpages__cert_chain.png" alt="Compose: Danish (da-DK) — Full Flow Across Main, Details, Chooser, Requester Info &amp; Certificate Chain - X.509 Certificate chain viewer (Danish da-DK)" width="260" /> |

<a id="danish_payment_with_tip"></a>

### `danish_payment_with_tip` — Danish (da-DK) — Payment Transaction with Tip Selector

- **Relying Party:** Utopia Marketplace — Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** Digital Payment Credential + PaymentTransaction ($42.50 USD)
- **Risk Assessment:** `NORMAL (da-DK)`

Danish localization of the built-in PaymentTransaction card showing payee ('Modtager'), amount ('Beløb: 42.50 USD' -> 'Beløb: 51.00 USD (drikkepenge: 8.50 USD)'), and tip selector chips ('Tilføj drikkepenge', 'Ingen drikkepenge').

| `main` — Payment prompt with 'Ingen drikkepenge' selected (Danish da-DK) | `tip_20_percent` — Payment prompt with 20% tip selected (Danish da-DK) |
| :---: | :---: |
| <img src="10_localization/danish_payment_with_tip__main.png" alt="Compose: Danish (da-DK) — Payment Transaction with Tip Selector - Payment prompt with 'Ingen drikkepenge' selected (Danish da-DK)" width="260" /> | <img src="10_localization/danish_payment_with_tip__tip_20_percent.png" alt="Compose: Danish (da-DK) — Payment Transaction with Tip Selector - Payment prompt with 20% tip selected (Danish da-DK)" width="260" /> |

<a id="danish_high_risk_pii_and_retention"></a>

### `danish_high_risk_pii_and_retention` — Danish (da-DK) — High-Risk Anonymous Website Requesting PII & Retention

- **Relying Party:** https://verify.unknown-portal.example.com — Anonymous Over-the-Internet
- **Protocol / Credentials:** mDL (given_name, family_name, birth_date, portrait) retained
- **Risk Assessment:** `HIGH_RISK (da-DK)`

Danish localization of the red high-risk warning card (with bullets for PII requested and intent to retain) and the 5-second 'Vent…' -> 'Fortsæt' cool-off button.

| `waiting` — High-risk prompt during cool-off ('Vent…', Danish da-DK) | `ready` — High-risk prompt after cool-off ('Fortsæt', Danish da-DK) |
| :---: | :---: |
| <img src="10_localization/danish_high_risk_pii_and_retention__waiting.png" alt="Compose: Danish (da-DK) — High-Risk Anonymous Website Requesting PII &amp; Retention - High-risk prompt during cool-off ('Vent…', Danish da-DK)" width="260" /> | <img src="10_localization/danish_high_risk_pii_and_retention__ready.png" alt="Compose: Danish (da-DK) — High-Risk Anonymous Website Requesting PII &amp; Retention - High-risk prompt after cool-off ('Fortsæt', Danish da-DK)" width="260" /> |

<a id="arabic_rtl_normal_with_subpages"></a>

### `arabic_rtl_normal_with_subpages` — Arabic RTL (ar-SA) — Full Flow Across Main, Details, Chooser, Requester Info & Certificate Chain

- **Relying Party:** Utopia Car Rentals — Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** mDL OR Photo ID with partial retention
- **Risk Assessment:** `NORMAL (ar-SA RTL)`

Full Arabic (ar-SA) Right-to-Left (RTL) rendering across the main consent prompt, Document Details, Document Chooser, Requester Info, and X.509 Certificate Chain viewer. Verifies RTL row alignment, right-aligned Arabic typography, and auto-mirrored navigation/chevron/external-link icons.

| `main` — Main prompt (Arabic ar-SA RTL) | `doc_details` — Document details page (Arabic ar-SA RTL) | `chooser` — Document chooser page (Arabic ar-SA RTL) | `requester_info` — Requester info page (Arabic ar-SA RTL) | `cert_chain` — X.509 Certificate chain viewer (Arabic ar-SA RTL) |
| :---: | :---: | :---: | :---: | :---: |
| <img src="10_localization/arabic_rtl_normal_with_subpages__main.png" alt="Compose: Arabic RTL (ar-SA) — Full Flow Across Main, Details, Chooser, Requester Info &amp; Certificate Chain - Main prompt (Arabic ar-SA RTL)" width="260" /> | <img src="10_localization/arabic_rtl_normal_with_subpages__doc_details.png" alt="Compose: Arabic RTL (ar-SA) — Full Flow Across Main, Details, Chooser, Requester Info &amp; Certificate Chain - Document details page (Arabic ar-SA RTL)" width="260" /> | <img src="10_localization/arabic_rtl_normal_with_subpages__chooser.png" alt="Compose: Arabic RTL (ar-SA) — Full Flow Across Main, Details, Chooser, Requester Info &amp; Certificate Chain - Document chooser page (Arabic ar-SA RTL)" width="260" /> | <img src="10_localization/arabic_rtl_normal_with_subpages__requester_info.png" alt="Compose: Arabic RTL (ar-SA) — Full Flow Across Main, Details, Chooser, Requester Info &amp; Certificate Chain - Requester info page (Arabic ar-SA RTL)" width="260" /> | <img src="10_localization/arabic_rtl_normal_with_subpages__cert_chain.png" alt="Compose: Arabic RTL (ar-SA) — Full Flow Across Main, Details, Chooser, Requester Info &amp; Certificate Chain - X.509 Certificate chain viewer (Arabic ar-SA RTL)" width="260" /> |

<a id="arabic_rtl_payment_with_tip"></a>

### `arabic_rtl_payment_with_tip` — Arabic RTL (ar-SA) — Payment Transaction with Tip Selector

- **Relying Party:** Utopia Marketplace — Over-the-Internet (ISO 18013-7)
- **Protocol / Credentials:** Digital Payment Credential + PaymentTransaction ($42.50 USD)
- **Risk Assessment:** `NORMAL (ar-SA RTL)`

Arabic RTL rendering of the built-in PaymentTransaction card with right-aligned payee/amount rows and RTL tip selector chips.

| `main` — Payment prompt with 'بدون إكرامية' selected (Arabic ar-SA RTL) | `tip_20_percent` — Payment prompt with 20% tip selected (Arabic ar-SA RTL) |
| :---: | :---: |
| <img src="10_localization/arabic_rtl_payment_with_tip__main.png" alt="Compose: Arabic RTL (ar-SA) — Payment Transaction with Tip Selector - Payment prompt with 'بدون إكرامية' selected (Arabic ar-SA RTL)" width="260" /> | <img src="10_localization/arabic_rtl_payment_with_tip__tip_20_percent.png" alt="Compose: Arabic RTL (ar-SA) — Payment Transaction with Tip Selector - Payment prompt with 20% tip selected (Arabic ar-SA RTL)" width="260" /> |

<a id="arabic_rtl_high_risk_pii_and_retention"></a>

### `arabic_rtl_high_risk_pii_and_retention` — Arabic RTL (ar-SA) — High-Risk Anonymous Website with RTL Cool-Off Progress Bar

- **Relying Party:** https://verify.unknown-portal.example.com — Anonymous Over-the-Internet
- **Protocol / Credentials:** mDL (given_name, family_name, birth_date, portrait) retained
- **Risk Assessment:** `HIGH_RISK (ar-SA RTL)`

Arabic RTL rendering of the red high-risk warning card, RTL button placement ('إلغاء' on the right, 'انتظر…' / 'متابعة' on the left), and the 5-second cool-off progress bar filling from right to left.

| `waiting` — High-risk prompt during cool-off with RTL progress fill (Arabic ar-SA RTL) | `ready` — High-risk prompt after cool-off ('متابعة', Arabic ar-SA RTL) |
| :---: | :---: |
| <img src="10_localization/arabic_rtl_high_risk_pii_and_retention__waiting.png" alt="Compose: Arabic RTL (ar-SA) — High-Risk Anonymous Website with RTL Cool-Off Progress Bar - High-risk prompt during cool-off with RTL progress fill (Arabic ar-SA RTL)" width="260" /> | <img src="10_localization/arabic_rtl_high_risk_pii_and_retention__ready.png" alt="Compose: Arabic RTL (ar-SA) — High-Risk Anonymous Website with RTL Cool-Off Progress Bar - High-risk prompt after cool-off ('متابعة', Arabic ar-SA RTL)" width="260" /> |

---

<a id="11_biometric_prompt"></a>

## 11. Combined Consent & Biometric Authentication (Android EmbeddedBiometricPrompt)

Android 17 QPR2+ (API 37.2) EmbeddedBiometricPrompt combining simple, well-defined non-PII proximity age verification requests with biometric authentication in a single bottom sheet. Captured on an Android 17 QPR2 device via `./multipaz-compose/golden-screenshots/update-biometric-goldens.sh` across eight requester and claim combinations.

<a id="trusted_requester_age_over_21"></a>

### `trusted_requester_age_over_21` — Trusted Proximity Requester Requesting Age Over 21 (Without Portrait)

- **Relying Party:** Utopia Marketplace — Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (age_over_21)
- **Risk Assessment:** `NORMAL (SimpleConsentClaim.AgeOver(21))`

A trusted proximity relying party in the wallet's trust list requests only age_over_21 without portrait. The embedded biometric prompt displays the relying party's verified icon and display name, stating that Multipaz is using Erika's driving license to share only that the holder is older than 21, and not any other identifying data, with a 'More details' link to expand to the full consent bottom sheet.

| `main` — EmbeddedBiometricPrompt (Trusted RP, age_over_21) |
| :---: |
| <img src="11_biometric_prompt/trusted_requester_age_over_21__main.png" alt="Compose: Trusted Proximity Requester Requesting Age Over 21 (Without Portrait) - EmbeddedBiometricPrompt (Trusted RP, age_over_21)" width="260" /> |

<a id="trusted_requester_age_over_21_and_portrait"></a>

### `trusted_requester_age_over_21_and_portrait` — Trusted Proximity Requester Requesting Age Over 21 & Portrait

- **Relying Party:** Utopia Marketplace — Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (age_over_21, portrait)
- **Risk Assessment:** `NORMAL (SimpleConsentClaim.AgeOverAndPortrait(21))`

A trusted proximity relying party in the wallet's trust list requests age_over_21 and portrait. The embedded biometric prompt displays the relying party's verified icon and display name, stating that Multipaz is using Erika's driving license to share only that the holder is older than 21 and their portrait, and not any other identifying data.

| `main` — EmbeddedBiometricPrompt (Trusted RP, age_over_21 + portrait) |
| :---: |
| <img src="11_biometric_prompt/trusted_requester_age_over_21_and_portrait__main.png" alt="Compose: Trusted Proximity Requester Requesting Age Over 21 &amp; Portrait - EmbeddedBiometricPrompt (Trusted RP, age_over_21 + portrait)" width="260" /> |

<a id="unknown_requester_age_over_21"></a>

### `unknown_requester_age_over_21` — Unverified Proximity Requester Requesting Age Over 21 (Without Portrait)

- **Relying Party:** Dave's Bookstore (Not in trust list) — Unverified Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (age_over_21)
- **Risk Assessment:** `NORMAL (SimpleConsentClaim.AgeOver(21))`

A proximity reader presents a reader authentication certificate whose root CA is not in the wallet's trust list, requesting only age_over_21 without portrait. The embedded biometric prompt displays the generic business icon and 'An unknown requester wants to know if you're older than 21', stating that Multipaz is using Erika's driving license to share only that the holder is older than 21, and not any other identifying data.

| `main` — EmbeddedBiometricPrompt (Unknown RP, age_over_21) |
| :---: |
| <img src="11_biometric_prompt/unknown_requester_age_over_21__main.png" alt="Compose: Unverified Proximity Requester Requesting Age Over 21 (Without Portrait) - EmbeddedBiometricPrompt (Unknown RP, age_over_21)" width="260" /> |

<a id="unknown_requester_age_over_21_and_portrait"></a>

### `unknown_requester_age_over_21_and_portrait` — Unverified Proximity Requester Requesting Age Over 21 & Portrait

- **Relying Party:** Dave's Bookstore (Not in trust list) — Unverified Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (age_over_21, portrait)
- **Risk Assessment:** `NORMAL (SimpleConsentClaim.AgeOverAndPortrait(21))`

A proximity reader presents a reader authentication certificate whose root CA is not in the wallet's trust list, requesting age_over_21 and portrait. The embedded biometric prompt displays the generic business icon and 'An unknown requester wants to know if you're older than 21', stating that Multipaz is using Erika's driving license to share only that the holder is older than 21 and their portrait, and not any other identifying data.

| `main` — EmbeddedBiometricPrompt (Unknown RP, age_over_21 + portrait) |
| :---: |
| <img src="11_biometric_prompt/unknown_requester_age_over_21_and_portrait__main.png" alt="Compose: Unverified Proximity Requester Requesting Age Over 21 &amp; Portrait - EmbeddedBiometricPrompt (Unknown RP, age_over_21 + portrait)" width="260" /> |

<a id="anonymous_requester_age_over_21"></a>

### `anonymous_requester_age_over_21` — Anonymous Proximity Requester Requesting Age Over 21 (Without Portrait)

- **Relying Party:** Anonymous Requester — Anonymous Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (age_over_21)
- **Risk Assessment:** `NORMAL (SimpleConsentClaim.AgeOver(21))`

A proximity reader provides no reader authentication certificate at all while requesting only age_over_21 without portrait. The embedded biometric prompt displays the generic business icon and 'An anonymous requester wants to know if you're older than 21', stating that Multipaz is using Erika's driving license to share only that the holder is older than 21, and not any other identifying data.

| `main` — EmbeddedBiometricPrompt (Anonymous RP, age_over_21) |
| :---: |
| <img src="11_biometric_prompt/anonymous_requester_age_over_21__main.png" alt="Compose: Anonymous Proximity Requester Requesting Age Over 21 (Without Portrait) - EmbeddedBiometricPrompt (Anonymous RP, age_over_21)" width="260" /> |

<a id="anonymous_requester_age_over_21_and_portrait"></a>

### `anonymous_requester_age_over_21_and_portrait` — Anonymous Proximity Requester Requesting Age Over 21 & Portrait

- **Relying Party:** Anonymous Requester — Anonymous Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (age_over_21, portrait)
- **Risk Assessment:** `NORMAL (SimpleConsentClaim.AgeOverAndPortrait(21))`

A proximity reader provides no reader authentication certificate at all while requesting age_over_21 and portrait. The embedded biometric prompt displays the generic business icon and 'An anonymous requester wants to know if you're older than 21', stating that Multipaz is using Erika's driving license to share only that the holder is older than 21 and their portrait, and not any other identifying data.

| `main` — EmbeddedBiometricPrompt (Anonymous RP, age_over_21 + portrait) |
| :---: |
| <img src="11_biometric_prompt/anonymous_requester_age_over_21_and_portrait__main.png" alt="Compose: Anonymous Proximity Requester Requesting Age Over 21 &amp; Portrait - EmbeddedBiometricPrompt (Anonymous RP, age_over_21 + portrait)" width="260" /> |

<a id="trusted_requester_age_over_50_and_portrait"></a>

### `trusted_requester_age_over_50_and_portrait` — Trusted Proximity Requester Requesting Age Over 50 (Falling Back to Age in Years) & Portrait

- **Relying Party:** Utopia Marketplace — Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (age_over_50 -> age_in_years, portrait, issuing_authority, issuing_country)
- **Risk Assessment:** `NORMAL (SimpleConsentClaim.AgeInYearsAndPortrait)`

A trusted proximity relying party requests age_over_50 with alternative data elements (age_in_years, birth_date) along with portrait and issuer metadata. Because age_over_50 is not present in Erika's driving license, the request falls back to age_in_years and the embedded biometric prompt displays 'Utopia Marketplace wants to know your age' and states that Multipaz is sharing only the holder's age and portrait, and not any other identifying data.

| `main` — EmbeddedBiometricPrompt (Trusted RP, age_over_50 -> age_in_years + portrait) |
| :---: |
| <img src="11_biometric_prompt/trusted_requester_age_over_50_and_portrait__main.png" alt="Compose: Trusted Proximity Requester Requesting Age Over 50 (Falling Back to Age in Years) &amp; Portrait - EmbeddedBiometricPrompt (Trusted RP, age_over_50 -&gt; age_in_years + portrait)" width="260" /> |

<a id="trusted_requester_age_over_65_and_portrait"></a>

### `trusted_requester_age_over_65_and_portrait` — Trusted Proximity Requester Requesting Age Over 65 (False in Credential) & Portrait

- **Relying Party:** Utopia Marketplace — Proximity (ISO 18013-5)
- **Protocol / Credentials:** mDL (age_over_65 = false, portrait, issuing_authority, issuing_country)
- **Risk Assessment:** `NORMAL (SimpleConsentClaim.NotAgeOverAndPortrait(65))`

A trusted proximity relying party requests age_over_65 along with portrait and issuer metadata. Because age_over_65 is present in Erika's driving license with value false (Erika is 53), the embedded biometric prompt displays 'Utopia Marketplace wants to know if you're older than 65' and states that Multipaz is sharing only that the holder is not older than 65 and their portrait, and not any other identifying data.

| `main` — EmbeddedBiometricPrompt (Trusted RP, age_over_65 = false + portrait) |
| :---: |
| <img src="11_biometric_prompt/trusted_requester_age_over_65_and_portrait__main.png" alt="Compose: Trusted Proximity Requester Requesting Age Over 65 (False in Credential) &amp; Portrait - EmbeddedBiometricPrompt (Trusted RP, age_over_65 = false + portrait)" width="260" /> |

