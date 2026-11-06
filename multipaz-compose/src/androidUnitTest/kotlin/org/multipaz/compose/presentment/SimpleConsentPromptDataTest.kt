package org.multipaz.compose.presentment

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.multipaz.consent.SimpleConsentClaim
import org.multipaz.context.initializeApplication
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimpleConsentPromptDataTest {

    @Before
    fun setUp() {
        Security.addProvider(BouncyCastleProvider())
        Locale.setDefault(Locale.US)
        val app = ApplicationProvider.getApplicationContext<Application>()
        initializeApplication(app)
        ConsentGoldenEcosystem.registerFakeAndroidApps(app)
    }

    @Test
    fun testResolveSimpleConsentPromptData() = runTest {
        val ecosystem = ConsentGoldenEcosystem.create()
        val mDLRequests = DrivingLicense.getDocumentType().cannedRequests.associateBy { it.id }

        val age21PortraitData = ecosystem.executeCannedDcql(
            mDLRequests.getValue("age_over_21_and_portrait")
        )
        val age21OnlyData = ecosystem.executeCannedDcql(
            mDLRequests.getValue("age_over_21")
        )

        // 1. Trusted proximity RP requesting age_over_21 + portrait
        val (vineyardsReq, vineyardsTrust) = ecosystem.trustedProximityRequester(
            ConsentGoldenEcosystem.RP_VINEYARDS
        )
        val trustedProxResult = assertNotNull(
            resolveSimpleConsentPromptData(
                requester = vineyardsReq,
                trustedRequesterIdentity = vineyardsTrust,
                presentmentData = age21PortraitData,
            )
        )
        assertEquals(SimpleConsentClaim.AgeOverAndPortrait(21), trustedProxResult.simpleConsent.claim)
        assertNotNull(trustedProxResult.requesterIcon)
        assertEquals(
            "Utopia Vineyards wants to know if you're older than 21",
            trustedProxResult.text,
        )

        // 2. Untrusted web RP requesting age_over_21 (referred to by URL, no RP icon)
        val (unverifiedWebReq, unverifiedWebTrust) = ecosystem.unverifiedWebRequester(
            origin = "https://age-check.utopia-gaming.example.com"
        )
        val webResult = assertNotNull(
            resolveSimpleConsentPromptData(
                requester = unverifiedWebReq,
                trustedRequesterIdentity = unverifiedWebTrust,
                presentmentData = age21OnlyData,
            )
        )
        assertNull(webResult.requesterIcon)
        assertEquals(
            "https://age-check.utopia-gaming.example.com wants to know if you're older than 21",
            webResult.text,
        )

        // 3. Web RP requesting age_over_21 + portrait -> disqualified over the Internet
        assertNull(
            resolveSimpleConsentPromptData(
                requester = unverifiedWebReq,
                trustedRequesterIdentity = unverifiedWebTrust,
                presentmentData = age21PortraitData,
            )
        )

        // 4. Unknown proximity RP (unverified cert chain) requesting age_over_21 + portrait
        val (unverifiedProxReq, unverifiedProxTrust) = ecosystem.unverifiedProximityRequester()
        val unknownProxResult = assertNotNull(
            resolveSimpleConsentPromptData(
                requester = unverifiedProxReq,
                trustedRequesterIdentity = unverifiedProxTrust,
                presentmentData = age21PortraitData,
            )
        )
        assertNull(unknownProxResult.requesterIcon)
        assertEquals(
            "An unknown requester wants to know if you're older than 21",
            unknownProxResult.text,
        )

        // 5. Anonymous proximity RP (no cert chain) requesting age_over_21 + portrait
        val (anonProxReq, anonProxTrust) = ecosystem.anonymousProximityRequester()
        val anonProxResult = assertNotNull(
            resolveSimpleConsentPromptData(
                requester = anonProxReq,
                trustedRequesterIdentity = anonProxTrust,
                presentmentData = age21PortraitData,
            )
        )
        assertNull(anonProxResult.requesterIcon)
        assertEquals(
            "An anonymous requester wants to know if you're older than 21",
            anonProxResult.text,
        )

        // 6. Local Android app requesting age_over_21 without portrait
        val (appReq, appTrust) = ecosystem.unverifiedAppRequester(
            ConsentGoldenEcosystem.APP_NIGHTCLUB_SCANNER
        )
        val appResult = assertNotNull(
            resolveSimpleConsentPromptData(
                requester = appReq,
                trustedRequesterIdentity = appTrust,
                presentmentData = age21OnlyData,
            )
        )
        assertNotNull(appResult.requesterIcon)
        assertEquals(
            "Utopia Nightclub Scanner wants to know if you're older than 21",
            appResult.text,
        )

        // 7. age_in_years and age_birth_year text formatting
        val ageInYearsData = ecosystem.executeDcql(
            """
            {
              "credentials": [
                {
                  "id": "mdl",
                  "format": "mso_mdoc",
                  "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                  "claims": [
                    { "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_in_years"] }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals(
            "Utopia Vineyards wants to know your age",
            assertNotNull(
                resolveSimpleConsentPromptData(vineyardsReq, vineyardsTrust, ageInYearsData)
            ).text,
        )
        assertEquals(
            "An unknown requester wants to know your age",
            assertNotNull(
                resolveSimpleConsentPromptData(unverifiedProxReq, unverifiedProxTrust, ageInYearsData)
            ).text,
        )
        assertEquals(
            "An anonymous requester wants to know your age",
            assertNotNull(
                resolveSimpleConsentPromptData(anonProxReq, anonProxTrust, ageInYearsData)
            ).text,
        )

        val ageBirthYearData = ecosystem.executeDcql(
            """
            {
              "credentials": [
                {
                  "id": "mdl",
                  "format": "mso_mdoc",
                  "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                  "claims": [
                    { "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_birth_year"] }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals(
            "Utopia Vineyards wants to know your birth year",
            assertNotNull(
                resolveSimpleConsentPromptData(vineyardsReq, vineyardsTrust, ageBirthYearData)
            ).text,
        )
        assertEquals(
            "An unknown requester wants to know your birth year",
            assertNotNull(
                resolveSimpleConsentPromptData(unverifiedProxReq, unverifiedProxTrust, ageBirthYearData)
            ).text,
        )
        assertEquals(
            "An anonymous requester wants to know your birth year",
            assertNotNull(
                resolveSimpleConsentPromptData(anonProxReq, anonProxTrust, ageBirthYearData)
            ).text,
        )

        // 8. age_over_65 (false in sample mDL) -> NotAgeOver(65)
        val ageOver65Data = ecosystem.executeDcql(
            """
            {
              "credentials": [
                {
                  "id": "mdl",
                  "format": "mso_mdoc",
                  "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                  "claims": [
                    { "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_65"] }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        val ageOver65Result = assertNotNull(
            resolveSimpleConsentPromptData(vineyardsReq, vineyardsTrust, ageOver65Data)
        )
        assertEquals(SimpleConsentClaim.NotAgeOver(65), ageOver65Result.simpleConsent.claim)
        assertEquals(
            "Utopia Vineyards wants to know if you're older than 65",
            ageOver65Result.text,
        )
    }
}
