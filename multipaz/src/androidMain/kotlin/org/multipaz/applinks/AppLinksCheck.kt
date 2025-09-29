package org.multipaz.applinks

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpStatusCode
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.util.Logger
import java.util.Locale

/**
 * Utility to verify that app links work correctly in the hosting app environment.
 */
object AppLinksCheck {
    const val TAG = "AppLinksCheck"

    /**
     * Checks that the server setup for the Android app links is valid for the app instance
     * that uses multipaz library.
     *
     * Important: this function does not determine if app links should be trusted or not (that is
     * done independently in Android OS). Instead, it attempts to evaluate if Android is going to
     * trust the way the server set up and if the answer is no, helps to diagnose and fix the
     * problem.
     *
     * Server setup might no work for various reasons:
     *  - app was configured to use a server that simply lacks any setup
     *  - `assetlinks.json` is not formatted correctly
     *  - app was signed by a key that is not known to the server
     *
     *  This function attempts to validate the setup. If it is valid it returns `true`. If it is
     *  invalid, it prints to a log a sample `assetlinks.json` that would be acceptable and returns
     *  `false`.
     *
     *  If the server is unreachable, setup is not validated and assumed valid, `true` is returned.
     *  This is to avoid flagging errors when offline. Also no validation happens below Android "P"
     *  release.
     *
     *  Note that correct intent filter for the app links must also be specified in the
     *  application's `AndroidManifest.xml`. It is not possible to verify this part at runtime.
     *
     *  @param context application or Activity context
     *  @param appLinkServer server URL, e.g, "https://example.com" __without__ trailing slash.
     *  @param httpClient HTTP client to use for fetching `assetlinks.json`
     */
    suspend fun checkAppLinksServerSetup(
        context: Context,
        appLinkServer: String,
        httpClient: HttpClient
    ): Boolean {
        if (appLinkServer.endsWith("/")) {
            throw IllegalArgumentException("Trailing slash in server name: '$appLinkServer'")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // No validation on old Android for now
            return true
        }
        // Extract our app info
        val packageName = context.applicationInfo.packageName
        val signingInfo = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        ).signingInfo!!
        val requireAllSigners = signingInfo.hasMultipleSigners()
        val signatures = if (requireAllSigners) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        val digests = signatures.map {
            ByteString(Crypto.digest(Algorithm.SHA256, it.toByteArray()))
        }.toSet()

        // Fetch assetlinks.json file
        val assetLinksUrl = "$appLinkServer/.well-known/assetlinks.json"
        val jsonText = try {
            val response = httpClient.get(assetLinksUrl)
            if (response.status != HttpStatusCode.OK) {
                Logger.e(TAG, "Error fetching '$assetLinksUrl': HTTP status ${response.status.value}")
                "[]"
            } else {
                response.readBytes().decodeToString()
            }
        } catch (err: Exception) {
            Logger.e(TAG, "Error connecting to $appLinkServer", err)
            // Assume we are offline, don't complain.
            return true
        }
        // Parse and validate assetlinks.json file
        for (permission in Json.parseToJsonElement(jsonText).jsonArray) {
            try {
                val relation = permission.jsonObject["relation"]!!.jsonArray
                if (relation[0].jsonPrimitive.content != "delegate_permission/common.handle_all_urls") {
                    continue
                }
                val target = permission.jsonObject["target"]!!.jsonObject
                if (target["namespace"]?.jsonPrimitive?.content != "android_app") {
                    continue
                }
                if (target["package_name"]?.jsonPrimitive?.content != packageName) {
                    continue
                }
                val serverDigests = target["sha256_cert_fingerprints"]!!.jsonArray.map {
                    ByteString(it.jsonPrimitive.content.split(':').map { byteCode ->
                        byteCode.toInt(16).toByte()
                    }.toByteArray())
                }.toSet()
                val digestsValid =  if (requireAllSigners) {
                    serverDigests.containsAll(digests)
                } else {
                    serverDigests == digests
                }
                if (digestsValid) {
                    Logger.i(TAG, "Server app link setup is validated")
                    return true
                }
            } catch (err: Exception) {
                Logger.e(TAG, "Parsing error", err)
                continue
            }
        }
        Logger.e(TAG, "Server app link setup could not be verified. This happens when you")
        Logger.e(TAG, "build the apk yourself instead of installing it from apps.multipaz.org")
        if (appLinkServer == "https://apps.multipaz.org") {
            Logger.e(TAG, "There are two ways to fix this issue. If you are a member of Multipaz")
            Logger.e(TAG, "team, you should create a PR adding your app signing key digest:")
            Logger.e(TAG, digestToString(digests.first()))
            Logger.e(TAG, "in the list of app_signature_certificate_digests in this file:")
            Logger.e(
                TAG,
                "multipaz-backend-server/src/main/resources/resources/default_configuration.txt"
            )
            Logger.e(TAG, "The other way is to use your own HTTPS server. Replace apps.multipaz.org")
            Logger.e(TAG, "where it is referenced in the code with your server URL (in particular")
            Logger.e(TAG, "in AndroidManifest.xml), and set up assetlinks.json as instructed.")
        }
        printSampleAssetLinksFile(packageName,
            if (requireAllSigners) digests else setOf(digests.first()))
        return false
    }

    private fun printSampleAssetLinksFile(
        packageName: String,
        digests: Set<ByteString>
    ) {
        val jsonData = buildJsonArray {
            addJsonObject {
                putJsonArray("relation") {
                    add("delegate_permission/common.handle_all_urls")
                }
                putJsonObject("target") {
                    put("namespace", "android_app")
                    put("package_name", packageName)
                    putJsonArray("sha256_cert_fingerprints") {
                        for (digest in digests) {
                            add(digestToString((digest)))
                        }
                    }
                }
            }
        }
        val json = Json { prettyPrint = true }
        val jsonText = json.encodeToString(JsonArray.serializer(), jsonData)
        Logger.e(TAG, "Sample file to upload to (or merge into) '.well-known/assetlinks.json':")
        Logger.e(TAG, jsonText)
    }

    private fun digestToString(digest: ByteString): String =
        digest.toByteArray().joinToString(":") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }.uppercase(Locale.ROOT)
}