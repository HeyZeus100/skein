package app.skein

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * E3.I1 (spec §9, plan `E3.I1`): locks the manifest security baseline with a
 * test so a future change cannot silently regress it. Component/permission
 * facts (well-modeled by the Android framework) are asserted through
 * Robolectric's shadowed [PackageManager]; newer manifest-only attributes
 * that `ApplicationInfo` does not reliably surface across Robolectric
 * versions (`dataExtractionRules`, `hasFragileUserData`, the intentional
 * absence of `fullBackupContent`/`networkSecurityConfig`) are asserted by
 * parsing the merged source manifest directly, exactly as
 * `tools/ci/manifest-audit.sh` and `ManifestGuardTask` do for defense in
 * depth against merged-manifest surprises from libraries.
 *
 * When `E2.I6` (`VaultDocumentsProvider`) or `E6.I17`
 * (`SkeinVoiceInteractionService`) land, extend [EXPECTED_EXPORTED_COMPONENTS]
 * — do not delete this test.
 *
 * Pinned to SDK 34: Robolectric's SDK 35+/36+/37 (targetSdk) `android-all`
 * jars require Java 21 to load, and the toolchain here (and CI) runs on
 * Java 17. SDK 34 (Android 14) still models every attribute this test
 * checks (`dataExtractionRules` is API 31+, `hasFragileUserData` is API
 * 29+), so pinning loses no coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestPolicyTest {
    private val packageManager: PackageManager
        get() = ApplicationProvider.getApplicationContext<android.app.Application>().packageManager

    private val packageName: String
        get() = ApplicationProvider.getApplicationContext<android.app.Application>().packageName

    // --- Permissions -------------------------------------------------------

    @Test
    fun `permission set is exactly POST_NOTIFICATIONS and USE_BIOMETRIC plus known androidx shims`() {
        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val requested = packageInfo.requestedPermissions?.toSet().orEmpty()

        // No unexpected permission crept in beyond the declared two plus the
        // documented androidx-injected shim.
        assertEquals(emptySet<String>(), requested - ALLOWED_PERMISSIONS)
        // The two we actually declared are both present.
        assertEquals(
            setOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.USE_BIOMETRIC",
            ),
            requested - ANDROIDX_INJECTED_PERMISSIONS,
        )
    }

    // --- Exported components ------------------------------------------------

    @Test
    fun `exported activity set is the launcher plus known debug-only tooling`() {
        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
        val exported =
            packageInfo.activities
                .orEmpty()
                .filter { it.exported }
                .map { it.name }
                .toSet()

        // No unexpected exported activity crept in beyond the declared
        // launcher plus documented debug-only tooling.
        assertEquals(emptySet<String>(), exported - ALLOWED_EXPORTED_ACTIVITIES)
        // The one we actually declared is present, independent of variant.
        assertEquals(EXPECTED_EXPORTED_COMPONENTS, exported - DEBUG_ONLY_EXPORTED_COMPONENTS)
    }

    @Test
    fun `no service, provider, or receiver is exported`() {
        val packageInfo =
            packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS,
            )

        val exportedServices = packageInfo.services.orEmpty().filter { it.exported }
        val exportedProviders = packageInfo.providers.orEmpty().filter { it.exported }
        val exportedReceivers = packageInfo.receivers.orEmpty().filter { it.exported }

        assertTrue(
            "expected no exported services, found: ${exportedServices.map { it.name }}",
            exportedServices.isEmpty(),
        )
        assertTrue(
            "expected no exported providers, found: ${exportedProviders.map { it.name }}",
            exportedProviders.isEmpty(),
        )
        assertTrue(
            "expected no exported receivers, found: ${exportedReceivers.map { it.name }}",
            exportedReceivers.isEmpty(),
        )
    }

    @Test
    fun `inference and embedder services are isolated and not exported`() {
        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SERVICES)
        val services = packageInfo.services.orEmpty().associateBy { it.name }

        listOf(
            "app.skein.inference.service.InferenceService",
            "app.skein.embedder.service.EmbedderService",
        ).forEach { name ->
            val service = requireNotNull(services[name]) { "service $name not declared in the manifest" }
            assertFalse("$name must not be exported", service.exported)
            assertTrue(
                "$name must declare android:isolatedProcess=\"true\"",
                (service.flags and android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS) != 0,
            )
        }
    }

    // --- Backup / extraction posture (raw manifest parse) -------------------

    @Test
    fun `allowBackup is true`() {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        assertTrue(
            "android:allowBackup must be true (Seedvault D2D is scoped via dataExtractionRules)",
            (packageInfo.applicationInfo!!.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0,
        )
    }

    @Test
    fun `dataExtractionRules points at the API 31+ rules resource`() {
        assertEquals(
            "expected android:dataExtractionRules to reference @xml/data_extraction_rules " +
                "(E3.I7, docs/design/POST_REVIEW_RESOLUTIONS.md §4.3)",
            "@xml/data_extraction_rules",
            applicationElement().getAttributeNS(ANDROID_NS, "dataExtractionRules"),
        )
    }

    @Test
    fun `hasFragileUserData is true`() {
        assertEquals(
            "true",
            applicationElement().getAttributeNS(ANDROID_NS, "hasFragileUserData"),
        )
    }

    @Test
    fun `fullBackupContent points at the API 30 legacy rules resource`() {
        // E3.I1 originally required fullBackupContent to be absent
        // ("dataExtractionRules is the only backup posture"). E3.I7
        // (docs/design/POST_REVIEW_RESOLUTIONS.md §4.3, skein-7ki2)
        // supersedes that: dataExtractionRules is API 31+ only and minSdk
        // here is 30, so fullBackupContent is required to give pre-31
        // devices (including Seedvault D2D on API 30) the same exclusion
        // posture via backup_rules_legacy.xml.
        assertEquals(
            "expected android:fullBackupContent to reference @xml/backup_rules_legacy " +
                "(E3.I7, docs/design/POST_REVIEW_RESOLUTIONS.md §4.3)",
            "@xml/backup_rules_legacy",
            applicationElement().getAttributeNS(ANDROID_NS, "fullBackupContent"),
        )
    }

    @Test
    fun `networkSecurityConfig is absent`() {
        assertFalse(
            "android:networkSecurityConfig must be absent -- there is no network permission and " +
                "nothing to configure (spec §9)",
            applicationElement().hasAttributeNS(ANDROID_NS, "networkSecurityConfig"),
        )
    }

    private fun applicationElement(): Element {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(manifestFile())
        return document.getElementsByTagName("application").item(0) as Element
    }

    private fun manifestFile(): File {
        val candidates =
            listOf(
                File("src/main/AndroidManifest.xml"),
                File("app/src/main/AndroidManifest.xml"),
            )
        return candidates.firstOrNull { it.exists() }
            ?: error("could not locate AndroidManifest.xml from working dir ${File(".").absolutePath}")
    }

    companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** Components this issue's manifest declares as exported=true. */
        val EXPECTED_EXPORTED_COMPONENTS =
            setOf(
                "app.skein.MainActivity",
            )

        /**
         * Exported only because `androidx.compose.ui:ui-tooling` is a
         * `debugImplementation`-only dependency (app/build.gradle.kts) that
         * merges an exported `PreviewActivity` into *debug* variant
         * manifests for Android Studio's interactive/deploy preview. It
         * never reaches a `release` build (foss or dev), so it never ships,
         * and Robolectric here resolves the `fossDebug` merged manifest
         * (the variant `./gradlew check` exercises).
         */
        val DEBUG_ONLY_EXPORTED_COMPONENTS =
            setOf(
                "androidx.compose.ui.tooling.PreviewActivity",
            )

        val ALLOWED_EXPORTED_ACTIVITIES = EXPECTED_EXPORTED_COMPONENTS + DEBUG_ONLY_EXPORTED_COMPONENTS

        /**
         * Self-defined, signature-protected permission androidx.core (1.9+)
         * auto-declares and self-requests whenever a transitive dependency
         * uses `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`
         * internally, to keep dynamically-registered receivers unexported on
         * pre-API-33 devices. It is not requestable by any other app and
         * strengthens rather than weakens the exported-component posture, so
         * it is an accepted addition to the permission baseline.
         */
        val ANDROIDX_INJECTED_PERMISSIONS =
            setOf(
                "app.skein.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            )

        val ALLOWED_PERMISSIONS =
            setOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.USE_BIOMETRIC",
            ) + ANDROIDX_INJECTED_PERMISSIONS
    }
}
