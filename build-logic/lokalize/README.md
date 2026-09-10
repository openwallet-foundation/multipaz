# Lokalize Gradle Plugin

Gradle plugin for Android/Kotlin Multiplatform internationalization that enforces translation completeness and provides AI-assisted translation.

## Features

- **Translation Validation**: Ensures all target locales have complete translations
- **AI-Powered Translation**: Automatically generates missing translations using LLMs (OpenAI, Google Gemini, Anthropic)
- **Smart Resource Handling**: Supports strings, plurals, and string-arrays in both XML and JSON formats
- **Build Integration**: Fails builds on missing translations (configurable)
- **Plural Support**: Properly translates all plural quantity variants (one, other, few, many)
- **Worker Isolation**: Uses Gradle Worker API for safe classpath isolation
- **Code Generation**: Generates Kotlin constants from JSON resources for compile-time safe access, checked into the source tree so downstreams on non-Gradle build systems can compile as-is
- **Drift Protection**: `lokalizeCheckGenerated` (wired into `check`) fails the build if the committed generated sources fall out of sync with the JSON resources
- **Convention Plugin**: Pre-configured settings for consistent usage across modules

## Installation

### Step 1: Add the plugin to your build

In your module's `build.gradle.kts`:

```kotlin
plugins {
    id("org.multipaz.lokalize")
}
```

Or use the convention plugin for pre-configured defaults:

```kotlin
plugins {
    id("org.multipaz.lokalize.convention")
}
```

### Step 2: Configure the plugin

```kotlin
import org.multipaz.lokalize.util.LLMProvider
import org.multipaz.lokalize.util.LLmModel
import org.multipaz.lokalize.util.OutputFormat

lokalize {
    defaultLocale = "en"
    targetLocales = listOf("es", "fr", "de")
    failOnMissing = true
    
    // Resource format: XML (Android strings.xml) or JSON
    outputFormat.set(OutputFormat.JSON)
    
    // Optional: Configure AI translation.
    // Note there is no API key setting here - see "Set up your API key" below.
    llmProvider.set(LLMProvider.GOOGLE)  // GOOGLE, OPENAI, or ANTHROPIC
    llModel.set(LLmModel.GEMINI2_5_FLASH)
    
    // Optional: Custom resources directory.
    // For Kotlin Multiplatform JSON projects, prefer a path that is NOT a
    // recognized KMP source-set directory (e.g. "src/commonMain/lokalize"
    // rather than "src/commonMain/resources"), so the per-locale JSONs are
    // not auto-bundled into the JVM JAR as Java resources at the root.
    resourcesDir.set("src/commonMain/lokalize")

    // Optional: Override the packages used for the generated code so that
    // multiple modules (e.g. multipaz-doctypes and multipaz-utopia) can each
    // produce their own GeneratedTranslations / GeneratedStringKeys without
    // colliding. Defaults target multipaz-doctypes.
    generatedTranslationsPackageName.set("org.multipaz.utopia.generated")
    stringKeysPackageName.set("org.multipaz.utopia.localization")
}
```

### Step 3: Set up your API key (for AI translation)

Only `lokalizeFix` needs a key; `lokalizeCheck` and the generator tasks do not.

**There is no `llmApiKey` setting in the `lokalize { }` block, by design.** The key is a
secret, and a build script that can assign one is a build script that will eventually have
one committed to it. The plugin resolves it itself, using the first of these that is set:

1. `LOKALIZE_API_KEY` environment variable (preferred)
2. `KOOG_API_KEY`, `OPENAI_API_KEY`, `GOOGLE_API_KEY`, `ANTHROPIC_API_KEY` environment
   variables, in that order
3. the `lokalizeApiKey` Gradle property

Option 1 - environment variable (recommended):
```bash
export LOKALIZE_API_KEY="your-api-key"
./gradlew :multipaz-compose:lokalizeFix
```

Option 2 - Gradle property in `~/.gradle/gradle.properties`, which lives outside the
repository:
```properties
lokalizeApiKey=your-api-key
```

> **Do not** put the key in this repository's `gradle.properties` - that file is checked in.
> `local.properties` is git-ignored but is *not* read by this plugin.

If no key is found, `lokalizeFix` does not fail. It warns and copies the base locale text
verbatim into every target locale, so a run without a key produces resources that look
complete but are untranslated. Check the log for `No API key found` before committing.

#### The key is written to disk while the task runs

`lokalizeFix` forks a worker process and passes it parameters through a file, so your key
is written **in cleartext** to
`<module>/build/lokalize/translations/translation_input_<locale>.json`, one file per locale,
and stays there after the build. `build/` is git-ignored so it cannot be committed, but if
you have used a real key, remove them afterwards:

```bash
find . -name 'translation_input_*.json' -delete
```

## Tasks

### `lokalizeCheck`

Validates that all target locales have complete translations.

```bash
./gradlew :module:lokalizeCheck
```

- Compares target locale files against the base (default) locale
- Reports missing strings, plurals, and arrays
- Fails the build if `failOnMissing = true` and translations are incomplete
- Supports both XML and JSON resource formats

### `lokalizeFix`

Generates missing translations using AI.

```bash
./gradlew :module:lokalizeFix
```

- Detects missing entries
- Sends them to the configured LLM for translation
- Updates the target locale files with translated content
- Fails if the API returns errors (does not silently use fallback text)
- Supports both XML and JSON resource formats

### `generateMultipazStrings` (JSON format only)

Renders Kotlin code from JSON string resources for compile-time safe access.

```bash
./gradlew :module:generateMultipazStrings
```

- Only does anything when `outputFormat.set(OutputFormat.JSON)` (skips for XML modules)
- Scans all `values*/strings.json` files
- Writes Kotlin files with embedded string maps into the **checked-in** source
  directory (`generatedSourceDir`, default `src/commonMain/generated/`)
- Creates a central access object with `getString()`, `getMapForLocale()`, and `containsKey()` methods
- Useful for platforms where file access is unreliable (e.g., iOS)

The generated Kotlin is committed to version control, not produced into `build/`
on every compile. Compiling reads the committed sources directly and never
regenerates. **After editing any `strings.json`, run this task and commit the
result alongside the JSON change** — otherwise `lokalizeCheckGenerated` will fail
(see below). This keeps the source tree compilable as-is for downstreams that
build without Gradle (issue #1811).

### `lokalizeCheckGenerated` (JSON format only)

Guards against the committed generated sources drifting from the JSON resources.

```bash
./gradlew :module:lokalizeCheckGenerated
```

- Renders the Kotlin in memory and compares it against the committed files under
  `generatedSourceDir`; fails the build (printing the exact
  `generateMultipazStrings` command to run) if they differ
- Wired into `check`, so `./gradlew check` — and `./gradlew build`, which CI runs
  — enforce it on every PR. It is the only thing standing between a `strings.json`
  edit and stale committed sources, since generation is off the compile path.

**Generated API:**
```kotlin
// Get string for a specific language
val text = GeneratedTranslations.getString("my_key", "es")

// Get entire map for a locale
val map = GeneratedTranslations.getMapForLocale("de")

// Check if key exists
val exists = GeneratedTranslations.containsKey("my_key", "fr")

// List all available languages
val languages = GeneratedTranslations.allLanguages
```

## Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `defaultLocale` | `String` | `"en"` | Base/source locale |
| `targetLocales` | `List<String>` | `[]` | Locales to validate/translate |
| `failOnMissing` | `Boolean` | `true` | Fail build on missing translations |
| `outputFormat` | `OutputFormat` | `XML` | Resource format (XML or JSON) |
| `llmProvider` | `LLMProvider` | `GOOGLE` | LLM provider to use |
| `llModel` | `LLmModel` | `GEMINI2_5_FLASH_LITE` | Specific model |
| `resourcesDir` | `Property<String>` | `"src/commonMain/composeResources"` | Resources base path |
| `generatedTranslationsPackageName` | `Property<String>` | `"org.multipaz.doctypes.generated"` | Package for the generated `GeneratedTranslations` and per-language `Strings_*` files |
| `stringKeysPackageName` | `Property<String>` | `"org.multipaz.doctypes.localization"` | Package for the generated `GeneratedStringKeys` object |
| `generatedSourceDir` | `Property<String>` | `"src/commonMain/generated"` | Checked-in directory the JSON code generator writes into (empty for XML modules) |

### Output Formats

#### XML Format (Default)

Standard Android `strings.xml` format:

```xml
<!-- values/strings.xml -->
<resources>
    <string name="app_name">My App</string>
    <string name="welcome_message">Welcome, %1$s!</string>
    
    <plurals name="items_count">
        <item quantity="one">%d item</item>
        <item quantity="other">%d items</item>
    </plurals>
    
    <string-array name="months">
        <item>January</item>
        <item>February</item>
    </string-array>
</resources>
```

#### JSON Format

Flat JSON with key-value pairs:

```json
{
  "app_name": "My App",
  "welcome_message": "Welcome, %1$s!",
  "items_count_one": "%d item",
  "items_count_other": "%d items"
}
```

JSON format is recommended when:
- You need compile-time access to strings via code generation
- Working with Kotlin Multiplatform projects
- Using the `generateMultipazStrings` task

## Supported LLM Providers

### Google (Gemini)

Available models:
- `GEMINI2_5_FLASH` - Balance of speed and capability (recommended)
- `GEMINI2_5_FLASH_LITE` - Most efficient for low-latency; the plugin default
- `GEMINI2_5_PRO` - Advanced capabilities
- `GEMINI3_PRO_PREVIEW` - Advanced reasoning, preview
- `GEMINI3_FLASH_PREVIEW` - Pro-level intelligence at Flash speed, preview

### OpenAI

Available models:
- `GPT4O` - Versatile flagship model
- `GPT4O_MINI` - Cost-effective version
- `GPT5` - Latest flagship
- `GPT5_MINI` / `GPT5_NANO` - Faster, cost-efficient

### Anthropic (Claude)

Available models:
- `CLAUDE_SONNET_4` - High-performance reasoning
- `CLAUDE_OPUS_4` - Most powerful for complex tasks
- `CLAUDE_HAIKU_4_5` - Fastest, most compact

## Supported Resource Types

### Simple Strings

**XML:**
```xml
<string name="app_name">My App</string>
<string name="welcome_message">Welcome, %1$s!</string>
```

**JSON:**
```json
{
  "app_name": "My App",
  "welcome_message": "Welcome, %1$s!"
}
```

### Plurals

**XML:**
```xml
<plurals name="items_count">
    <item quantity="one">%d item</item>
    <item quantity="other">%d items</item>
</plurals>
```

**JSON:**
```json
{
  "items_count_one": "%d item",
  "items_count_other": "%d items"
}
```

The plugin will translate each quantity variant (one, other, few, many) separately based on the target locale's plural rules.

### String Arrays

**XML:**
```xml
<string-array name="months">
    <item>January</item>
    <item>February</item>
</string-array>
```

**JSON:**
```json
{
  "months_0": "January",
  "months_1": "February"
}
```

## How It Works

### Translation Flow

1. **Scan**: Reads base locale resources (XML or JSON) and extracts all entries
2. **Compare**: Checks each target locale for missing or incomplete entries
3. **Batch**: Groups missing entries for efficient API usage
4. **Translate**: Sends to LLM with context-aware prompts
5. **Parse**: Extracts translations from API response
6. **Write**: Updates target locale files preserving existing content

### Plural Handling

Different locales have different plural rules:
- **English, Spanish**: `one`, `other`
- **French**: `one`, `other` (treats 0 as "one")
- **Russian, Polish**: `one`, `few`, `many`, `other`
- **Arabic**: `zero`, `one`, `two`, `few`, `many`, `other`
- **Japanese, Korean**: `only` (no plurals)

The plugin uses CLDR plural rules to determine required quantities for each locale.

## Troubleshooting

### API Rate Limits

If you see `429 Too Many Requests`:

**Google Gemini Free Tier**:
- Very limited daily quota
- Wait 24 hours for reset, or
- Upgrade to paid API key

**OpenAI**:
- Check your plan's rate limits
- Consider using `GPT4O_MINI` for cost savings

**Anthropic**:
- Claude has different rate limits per tier
- Check your API key's tier status

### Authentication Errors

`Translation failed for locale '<locale>'` followed by `API key not valid`, `401` or `403`
means the key never reached the provider, or was rejected. Check, in order:

- Is `LOKALIZE_API_KEY` exported in the shell that runs Gradle? Confirm with
  `echo ${LOKALIZE_API_KEY:+set}`.
- Does something assign the key in a build script? Nothing should - `lokalize { }` exposes no
  key setting, and a `set()` call on the underlying property outranks the environment lookup
  and is sent to the provider verbatim. This is what caused issue #2003, where a placeholder
  `"API_KEY"` was hardcoded in the convention plugin and every run failed with
  `400 API_KEY_INVALID`.
- Is the key valid for the provider you selected? A Gemini key will not work with
  `llmProvider.set(LLMProvider.OPENAI)`. Verify a Google key independently with:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' \
    "https://generativelanguage.googleapis.com/v1beta/models?key=$LOKALIZE_API_KEY"
  ```

The failure message includes the provider's own error and the exception chain, and the
worker prints the full stack trace, so read those before guessing.

### Missing Translations Not Detected

Ensure your resource directory structure follows standard conventions:

**XML format:**
```
src/commonMain/composeResources/
  values/strings.xml          (base/default)
  values-es/strings.xml       (Spanish)
  values-fr/strings.xml       (French)
```

**JSON format:**
```
src/commonMain/lokalize/
  values/strings.json         (base/default)
  values-es/strings.json      (Spanish)
  values-fr/strings.json      (French)
```

### Build Fails with Classpath Issues

The plugin uses Gradle Worker API with classpath isolation. If you see Koog/Kotlin version conflicts:
```bash
./gradlew clean
./gradlew --stop  # Stop Gradle daemon
./gradlew :module:lokalizeFix
```

### Code Generation Not Working

If `generateMultipazStrings` is skipped:
- Verify `outputFormat.set(OutputFormat.JSON)` is configured
- Check that JSON files exist in `resourcesDir`
- Ensure directory names start with "values"

## Best Practices

1. **Commit base locale first**: Always ensure base strings are complete before running `lokalizeFix`

2. **Review AI translations**: While AI is accurate, review translations for:
   - Brand-specific terminology
   - Cultural context
   - UI space constraints

3. **Keep API keys out of the repository**: pass them by environment variable, never by
   editing a build script. The `lokalize { }` block has no key setting for this reason.
   ```bash
   export LOKALIZE_API_KEY="your-key"
   ```
   After a run with a real key, clear the worker input files it leaves behind:
   ```bash
   find . -name 'translation_input_*.json' -delete
   ```

4. **Run check before commit**: Add to pre-commit hooks:
   ```bash
   ./gradlew :module:lokalizeCheck
   ```

5. **Batch translations**: For cost efficiency, accumulate several missing strings before running `lokalizeFix`

6. **Choose format wisely**:
   - Use **XML** for Android-only projects with standard resource handling
   - Use **JSON** for Kotlin Multiplatform projects needing code generation

## Development Workflow

### Using the Convention Plugin (Recommended)

The `org.multipaz.lokalize.convention` plugin provides pre-configured settings:

```kotlin
plugins {
    id("org.multipaz.lokalize.convention")
}

// Only override if needed
lokalize {
    outputFormat.set(OutputFormat.JSON)
    resourcesDir.set("src/commonMain/lokalize")
}
```

### Manual Configuration

When using the base plugin directly:

```kotlin
plugins {
    id("org.multipaz.lokalize")
}

lokalize {
    defaultLocale = "en"
    targetLocales = listOf("es", "fr", "de", "ja")
    outputFormat.set(OutputFormat.JSON)
    resourcesDir.set("src/commonMain/lokalize")
}
```

### Example: multipaz-compose (XML format)

```bash
# Check translations
./gradlew :multipaz-compose:lokalizeCheck

# Generate missing translations (requires API key)
export LOKALIZE_API_KEY="your-key"
./gradlew :multipaz-compose:lokalizeFix
```

### Example: multipaz-doctypes (JSON format)

```bash
# Check translations
./gradlew :multipaz-doctypes:lokalizeCheck

# Generate missing translations
export LOKALIZE_API_KEY="your-key"
./gradlew :multipaz-doctypes:lokalizeFix

# Regenerate the checked-in Kotlin from the JSON resources, then commit the result
# (both the strings.json changes and src/commonMain/generated/ together)
./gradlew :multipaz-doctypes:generateMultipazStrings

# Verify the committed sources are in sync (also runs as part of `check`)
./gradlew :multipaz-doctypes:lokalizeCheckGenerated
```

### Example: multipaz-utopia (JSON format, custom packages)

`multipaz-utopia` carries its own copy of the lokalize pipeline so that
applications consuming only `multipaz-doctypes` don't ship Utopia-specific
strings. It overrides the generated package names so its
`GeneratedTranslations` / `GeneratedStringKeys` live alongside the Utopia
code instead of in the doctypes namespace:

```kotlin
// multipaz-utopia/build.gradle.kts
lokalize {
    outputFormat.set(OutputFormat.JSON)
    resourcesDir.set("src/commonMain/lokalize")
    generatedTranslationsPackageName.set("org.multipaz.utopia.generated")
    stringKeysPackageName.set("org.multipaz.utopia.localization")
}
```

```bash
./gradlew :multipaz-utopia:lokalizeCheck
./gradlew :multipaz-utopia:lokalizeFix               # requires LOKALIZE_API_KEY
./gradlew :multipaz-utopia:generateMultipazStrings   # then commit src/commonMain/generated/
./gradlew :multipaz-utopia:lokalizeCheckGenerated    # verify (also part of `check`)
```

## CI/CD Integration

### GitHub Actions

```yaml
- name: Check Translations
  run: ./gradlew :module:lokalizeCheck
  env:
    LOKALIZE_API_KEY: ${{ secrets.LOKALIZE_API_KEY }}

# Do NOT regenerate in CI. The generated sources are committed; CI only verifies
# they are in sync. lokalizeCheckGenerated is wired into `check`, so a plain
# `./gradlew build` already enforces this — run it explicitly only if you skip check.
- name: Verify Generated Sources (JSON format)
  run: ./gradlew :module:lokalizeCheckGenerated
```

### GitLab CI

```yaml
translation-check:
  script:
    - ./gradlew :module:lokalizeCheck
  variables:
    LOKALIZE_API_KEY: $LOKALIZE_API_KEY
```

## Architecture

The plugin uses a layered architecture:

- **ResourceScanner**: Parses XML and JSON resources using pluggable strategies
- **ResourceScannerStrategy**: Interface for format-specific scanning (XML/JSON)
- **TranslationComparator**: Finds missing/incomplete translations
- **BatchTranslator**: Groups translations for efficient API usage
- **TranslationWorkAction**: Runs in isolated Gradle Worker process
- **ResourceWriter**: Merges translations preserving existing content
- **ResourceWriterStrategy**: Interface for format-specific writing (XML/JSON)
- **GenerateStringsTask**: Renders Kotlin code from JSON resources into the checked-in source tree
- **LokalizeVerifyGeneratedTask**: Backs `lokalizeCheckGenerated`; renders in memory and fails on drift from the committed sources

Worker isolation ensures classpath isolation between the plugin and project dependencies.

## License

Copyright (c) 2024 Multipaz Contributors

Licensed under the Apache License, Version 2.0
