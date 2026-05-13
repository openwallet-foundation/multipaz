# Multipaz Doctypes Module

This module contains document type definitions and metadata for various identity and credential documents supported by the Multipaz SDK. It includes built-in support for ISO mDL (Mobile Driving License), EU Personal ID, Photo ID, and many other document types.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Adding a New Document Type](#adding-a-new-document-type)
- [Adding Translations](#adding-translations)
- [Document Type Reference](#document-type-reference)
- [Localization System](#localization-system)

## Overview

The `multipaz-doctypes` module provides:

- **Document Type Definitions**: Metadata for various identity documents (mDL, ID cards, etc.)
- **Attribute Definitions**: Display names and descriptions for document attributes
- **Sample Requests**: Pre-configured data element requests for common use cases
- **Localization**: Multi-language support for all document-related strings

### Supported Document Types

- **Driving License** (ISO mDL)
- **EU Personal ID**
- **Photo ID**
- **Vehicle Registration**
- **Vaccination Document**
- **Age Verification**
- **Google Wallet ID Pass**
- **Aadhaar**

> Note: Multipaz-Utopia-specific document types (Movie Ticket, Boarding Pass,
> Naturalization Certificate, German Personal ID, Certificate of Residence,
> Digital Payment Credential, Loyalty Card) and their localized strings live
> in the [`multipaz-utopia`](../multipaz-utopia) module. Applications that
> only need standardized document types do not pull in those resources.

## Architecture

### Key Components

```
multipaz-doctypes/
├── src/commonMain/
│   ├── kotlin/org/multipaz/documenttype/knowntypes/    # Document definitions
│   │   ├── DrivingLicense.kt
│   │   ├── EUPersonalID.kt
│   │   └── ...
│   ├── kotlin/org/multipaz/doctypes/localization/      # Localization system
│   │   ├── LocalizedStrings.kt                           # String lookup
│   │   └── NativeLocale.kt                               # Platform locale
│   └── lokalize/                                         # Translation source files
│       ├── values/strings.json                           # English (base)
│       ├── values-es/strings.json                        # Spanish
│       ├── values-de/strings.json                        # German
│       └── ...
└── build/generated/.../.../generated/                      # Generated code
    ├── GeneratedTranslations.kt                          # Auto-generated translations map
    └── GeneratedStringKeys.kt                            # Auto-generated string key constants
```

### Localization Flow

1. **Base Keys** (`lokalize/values/strings.json`): Source of truth for all string keys
2. **JSON Resources** (`lokalize/values*/strings.json`): Translation files in JSON format
3. **Generated Code** (`GeneratedTranslations.kt`, `GeneratedStringKeys.kt`): Auto-generated Kotlin maps and key constants from JSON
4. **Runtime Access** (`LocalizedStrings.kt`): Platform-aware string lookup

## Adding a New Document Type

To add a new document type, follow these steps:

### Step 1: Create the Document Type Object

Create a new Kotlin file in `src/commonMain/kotlin/org/multipaz/documenttype/knowntypes/`:

```text
package org.multipaz.documenttype.knowntypes

import org.multipaz.cbor.toDataItem
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import org.multipaz.doctypes.localization.GeneratedStringKeys
import org.multipaz.doctypes.localization.LocalizedStrings

object MyNewDocument {
    const val DOCTYPE = "com.example.mydoc.1"
    const val NAMESPACE = "com.example.mydoc.1"

    fun getDocumentType(): DocumentType {
        return DocumentType.Builder(
            LocalizedStrings.getString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_MY_NEW_DOC)
        )
            .addMdocDocumentType(DOCTYPE)
            // Add attributes
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "family_name",
                displayName = LocalizedStrings.getString(GeneratedStringKeys.MY_NEW_DOC_ATTRIBUTE_FAMILY_NAME),
                description = LocalizedStrings.getString(GeneratedStringKeys.MY_NEW_DOC_DESCRIPTION_FAMILY_NAME),
                mandatory = true,
                mdocNamespace = NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = "Doe".toDataItem()
            )
            // Add more attributes...
            // Add sample requests
            .addSampleRequest(
                id = "mandatory",
                displayName = LocalizedStrings.getString(GeneratedStringKeys.MY_NEW_DOC_REQUEST_MANDATORY),
                mdocDataElements = mapOf(
                    NAMESPACE to mapOf(
                        "family_name" to false
                    )
                )
            )
            .build()
    }
}
```

### Step 2: Add Base Key Names to English Strings

Define new key names in `src/commonMain/lokalize/values/strings.json` (this is now the source of truth; key constants are generated):

```json
{
  "document_display_name_my_new_doc": "My New Document",
  "my_new_doc_attribute_family_name": "Family name",
  "my_new_doc_attribute_given_name": "Given name",
  "my_new_doc_description_family_name": "The family name of the document holder",
  "my_new_doc_description_given_name": "The given name of the document holder",
  "my_new_doc_request_mandatory": "Mandatory data elements",
  "my_new_doc_request_all": "All data elements"
}
```

### Step 3: Add Base Translations

Add English (base) translations in `src/commonMain/lokalize/values/strings.json`:

```json
{
  "document_display_name_my_new_doc": "My New Document",
  "my_new_doc_attribute_family_name": "Family name",
  "my_new_doc_attribute_given_name": "Given name",
  "my_new_doc_description_family_name": "The family name of the document holder",
  "my_new_doc_description_given_name": "The given name of the document holder",
  "my_new_doc_request_mandatory": "Mandatory data elements",
  "my_new_doc_request_all": "All data elements"
}
```

### Step 4: Generate Translations

Run the lokalize plugin to generate translations for all supported languages:

```bash
# Check which translations are missing
./gradlew :multipaz-doctypes:lokalizeCheck

# Generate missing translations (requires API key)
export LOKALIZE_API_KEY="your-api-key"
./gradlew :multipaz-doctypes:lokalizeFix
```

### Step 5: Verify and Build

Verify all translations are complete:

```bash
./gradlew :multipaz-doctypes:lokalizeCheck
```

Then generate the Kotlin code from JSON:

```bash
./gradlew :multipaz-doctypes:generateMultipazStrings
```

## Adding Translations

To add or update translations for existing document types:

### The 4-Step Translation Process

#### 1. Add Key Names to `values/strings.json`

Define new keys in the base English resource (compile-time constants are generated from this file):

```json
{
  "document_display_name_my_doc": "My Document",
  "my_doc_attribute_field_name": "Field Name",
  "my_doc_description_field_name": "Description of what this field represents"
}
```

#### 2. Create Values in `commonMain/lokalize/values`

Add base (English) strings to `src/commonMain/lokalize/values/strings.json`:

```json
{
  "document_display_name_my_doc": "My Document",
  "my_doc_attribute_field_name": "Field Name",
  "my_doc_description_field_name": "Description of what this field represents"
}
```

#### 3. Run `lokalizeFix` to Get Translations

Generate translations for all target locales:

```bash
# Set your API key
export LOKALIZE_API_KEY="your-api-key"

# Generate all translations
./gradlew :multipaz-doctypes:lokalizeFix
```

This will create/update translation files in:
- `values-es/strings.json` (Spanish)
- `values-de/strings.json` (German)
- `values-fr/strings.json` (French)
- `values-ja/strings.json` (Japanese)
- ... and all other configured locales

#### 4. Run `lokalizeCheck` to Verify

Verify all translations are complete:

```bash
./gradlew :multipaz-doctypes:lokalizeCheck
```

The build will fail if any translations are missing (when `failOnMissing = true`).

### Supported Languages

The module currently supports the following languages:

| Language | Code | Directory |
|----------|------|-----------|
| English (base) | `en` | `values/` |
| Arabic | `ar` | `values-ar/` |
| Czech | `cs` | `values-cs/` |
| Danish | `da` | `values-da/` |
| German | `de` | `values-de/` |
| Greek | `el` | `values-el/` |
| Spanish | `es` | `values-es/` |
| French | `fr` | `values-fr/` |
| Hebrew | `he` | `values-he/` |
| Hindi | `hi` | `values-hi/` |
| Indonesian | `id` | `values-id/` |
| Italian | `it` | `values-it/` |
| Japanese | `ja` | `values-ja/` |
| Korean | `ko` | `values-ko/` |
| Dutch | `nl` | `values-nl/` |
| Polish | `pl` | `values-pl/` |
| Portuguese | `pt` | `values-pt/` |
| Russian | `ru` | `values-ru/` |
| Thai | `th` | `values-th/` |
| Turkish | `tr` | `values-tr/` |
| Ukrainian | `uk` | `values-uk/` |
| Vietnamese | `vi` | `values-vi/` |
| Chinese (Simplified) | `zh-rCN` | `values-zh-rCN/` |

### Updating Existing Translations

To modify an existing translation:

1. Edit the specific language file (e.g., `values-de/strings.json`)
2. Run `lokalizeCheck` to ensure consistency
3. Re-generate code if needed: `./gradlew :multipaz-doctypes:generateMultipazStrings`

## Document Type Reference

### DocumentType.Builder API

```text
DocumentType.Builder(displayName: String)
    .addMdocDocumentType(doctype: String)
    .addMdocAttribute(
        type: DocumentAttributeType,
        identifier: String,
        displayName: String,
        description: String,
        mandatory: Boolean,
        mdocNamespace: String,
        icon: Icon,
        sampleValue: DataItem?
    )
    .addSampleRequest(
        id: String,
        displayName: String,
        mdocDataElements: Map<String, Map<String, Boolean>>,
        mdocUseZkp: Boolean = false
    )
    .build()
```

### Attribute Types

| Type | Description | Example |
|------|-------------|---------|
| `String` | Text value | Family name, document number |
| `Date` | ISO 8601 date | Date of birth, expiry date |
| `Number` | Numeric value | Height in cm, age in years |
| `Boolean` | True/False | Age over 18, organ donor |
| `Picture` | Binary image data | Portrait photo, signature |
| `ComplexType` | Nested CBOR structure | Driving privileges |
| `StringOptions` | Enum-like selection | Sex, country codes |
| `IntegerOptions` | Numeric enum | Weight ranges |

### Icons

Available icons for attributes:
- `Icon.PERSON` - User-related fields
- `Icon.TODAY` - Date-related fields
- `Icon.CALENDAR_CLOCK` - Expiry/issue dates
- `Icon.PLACE` - Location/address fields
- `Icon.NUMBERS` - Numeric identifiers
- `Icon.ACCOUNT_BALANCE` - Authority/issuing fields
- `Icon.DIRECTIONS_CAR` - Vehicle-related
- `Icon.EMERGENCY` - Medical/emergency info
- `Icon.FACE` - Biometric data
- `Icon.FINGERPRINT` - Fingerprint data
- `Icon.SIGNATURE` - Signature/Mark
- `Icon.MILITARY_TECH` - Veteran status
- `Icon.STARS` - Compliance/indicators
- `Icon.EYE_TRACKING` - Iris data
- `Icon.LANGUAGE` - Nationality/language

## Localization System

### How It Works

1. **Base JSON Keys** (`lokalize/values/strings.json`)
   - Source of truth for all string identifiers
   - New key names are defined here
   - Generated as type-safe constants in `GeneratedStringKeys.kt`

2. **JSON Resources** (`lokalize/values*/strings.json`)
   - Flat JSON format for easy editing
   - One file per language
   - Base locale in `values/strings.json`

3. **Generated Code** (`GeneratedTranslations.kt`, `GeneratedStringKeys.kt`)
   - Auto-generated from JSON at build time
   - Embedded string maps and type-safe keys for all platforms
   - No file system access required at runtime

4. **Runtime Access** (`LocalizedStrings.kt`)
   - Platform-aware locale detection
   - Fallback to base locale (English)
   - Simple API: `LocalizedStrings.getString(GeneratedStringKeys.SOME_KEY)`

### Usage in Document Definitions

```kotlin
// Get a localized string
val displayName = LocalizedStrings.getString(GeneratedStringKeys.DOCUMENT_DISPLAY_NAME_DRIVING_LICENSE)

// Use in DocumentType.Builder
DocumentType.Builder(displayName)
    .addMdocAttribute(
        type = DocumentAttributeType.String,
        identifier = "family_name",
        displayName = LocalizedStrings.getString(GeneratedStringKeys.DRIVING_LICENSE_ATTRIBUTE_FAMILY_NAME),
        description = LocalizedStrings.getString(GeneratedStringKeys.DRIVING_LICENSE_DESCRIPTION_FAMILY_NAME),
        // ...
    )
```

### Platform-Specific Locale Detection

The `NativeLocale` expect/actual pattern provides platform-specific locale detection:

- **Android**: Uses `Locale.getDefault()`
- **iOS**: Uses `NSLocale.currentLocale`
- **JVM**: Uses `Locale.getDefault()`
- **JS/Web**: Uses `navigator.language`

## Build Configuration

The module uses the Lokalize convention plugin with JSON output format:

```kotlin
// In build.gradle.kts
plugins {
    id("org.multipaz.lokalize.convention")
}

lokalize {
    outputFormat.set(OutputFormat.JSON)
    resourcesDir.set("src/commonMain/lokalize")
}
```

## Common Tasks

```bash
# Check translation completeness
./gradlew :multipaz-doctypes:lokalizeCheck

# Generate missing translations (requires API key)
export LOKALIZE_API_KEY="your-key"
./gradlew :multipaz-doctypes:lokalizeFix

# Generate Kotlin code from JSON resources
./gradlew :multipaz-doctypes:generateMultipazStrings

# Build the module
./gradlew :multipaz-doctypes:build

# Run tests
./gradlew :multipaz-doctypes:test
```
