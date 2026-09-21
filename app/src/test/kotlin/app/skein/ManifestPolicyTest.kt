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
 * `E2.I6` (`VaultDocumentsProvider`, permission-guarded) has landed and is
 * asserted below as the one exported provider. When `E6.I17`
 * (`SkeinVoiceInteractionService`) lands, extend the exported-service
 * assertion the same way — do not delete this test.
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
    fun `the only exported service is WorkManager's job service, guarded by BIND_JOB_SERVICE`() {
        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SERVICES)

        val exportedServices = packageInfo.services.orEmpty().filter { it.exported }

        assertEquals(setOf(WORKMANAGER_JOB_SERVICE), exportedServices.map { it.name }.toSet())
        exportedServices.forEach { service ->
            assertEquals("${service.name} must be bindable by the system only", BIND_JOB_SERVICE, service.permission)
        }
    }

    @Test
    fun `no receiver is exported`() {
        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_RECEIVERS)

        val exportedReceivers = packageInfo.receivers.orEmpty().filter { it.exported }

        assertTrue(
            "expected no exported receivers, found: ${exportedReceivers.map { it.name }}",
            exportedReceivers.isEmpty(),
        )
    }

    // --- DocumentsProvider (E2.I6, POST_REVIEW_RESOLUTIONS.md §4.3) --------

    @Test
    fun `the only exported provider is the vault DocumentsProvider`() {
        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PROVIDERS)
        val exported =
            packageInfo.providers
                .orEmpty()
                .filter { it.exported }
                .map { it.name }
                .toSet()

        assertEquals(setOf(DOCUMENTS_PROVIDER), exported)
    }

    @Test
    fun `no FileProvider is declared anywhere in the merged manifest`() {
        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PROVIDERS)
        val fileProviders =
            packageInfo.providers
                .orEmpty()
                .map { it.name }
                .filter { it.endsWith("FileProvider") }

        assertTrue(
            "POST_REVIEW_RESOLUTIONS.md §4 forbids a FileProvider in v1, found: $fileProviders",
            fileProviders.isEmpty(),
        )
    }

    @Test
    fun `the DocumentsProvider is guarded by MANAGE_DOCUMENTS for both reads and writes`() {
        val provider = documentsProviderInfo()

        assertEquals(MANAGE_DOCUMENTS, provider.readPermission)
        assertEquals(MANAGE_DOCUMENTS, provider.writePermission)
    }

    @Test
    fun `the DocumentsProvider declares the authority the class expects`() {
        assertEquals(DOCUMENTS_AUTHORITY, documentsProviderInfo().authority)
    }

    @Test
    fun `the DocumentsProvider only allows grants for note and attachment document paths`() {
        // grantUriPermissions="false" in the source manifest plus the two
        // <grant-uri-permission> subsets resolves to an effective
        // ProviderInfo with grantUriPermissions == true (the platform flips
        // it whenever a subset is declared — and DocumentsProvider.attachInfo
        // would throw otherwise) restricted to exactly these path prefixes.
        // A tree/root/directory URI matches neither, so no client can hold
        // a grant over the whole vault.
        val provider = documentsProviderInfo()
        val prefixes =
            provider.uriPermissionPatterns
                .orEmpty()
                .filter { it.type == android.os.PatternMatcher.PATTERN_PREFIX }
                .map { it.path }
                .toSet()

        assertTrue("DocumentsProvider.attachInfo requires effective grantUriPermissions", provider.grantUriPermissions)
        assertEquals(setOf("/document/note:", "/document/att:"), prefixes)
        assertEquals(prefixes.size, provider.uriPermissionPatterns.orEmpty().size)
    }

    @Test
    fun `the DocumentsProvider source declaration matches POST_REVIEW_RESOLUTIONS 4_3 verbatim`() {
        val element = documentsProviderElement()

        assertEquals("true", element.getAttributeNS(ANDROID_NS, "exported"))
        assertEquals("false", element.getAttributeNS(ANDROID_NS, "grantUriPermissions"))
        assertEquals(MANAGE_DOCUMENTS, element.getAttributeNS(ANDROID_NS, "permission"))
    }

    @Test
    fun `the DocumentsProvider advertises the DOCUMENTS_PROVIDER intent filter`() {
        val actions =
            documentsProviderElement()
                .childElements("intent-filter")
                .flatMap { it.childElements("action") }
                .map { it.getAttributeNS(ANDROID_NS, "name") }

        assertEquals(listOf("android.content.action.DOCUMENTS_PROVIDER"), actions)
    }

    private fun documentsProviderInfo(): android.content.pm.ProviderInfo {
        val packageInfo =
            packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_PROVIDERS or PackageManager.GET_URI_PERMISSION_PATTERNS,
            )
        return requireNotNull(packageInfo.providers.orEmpty().firstOrNull { it.name == DOCUMENTS_PROVIDER }) {
            "$DOCUMENTS_PROVIDER not declared in the manifest"
        }
    }

    private fun documentsProviderElement(): Element =
        requireNotNull(
            applicationElement()
                .childElements("provider")
                .firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == DOCUMENTS_PROVIDER },
        ) { "$DOCUMENTS_PROVIDER not declared in the source manifest" }

    private fun Element.childElements(tag: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
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

        /** Activities this issue's manifest declares as exported=true. */
        val EXPECTED_EXPORTED_COMPONENTS =
            setOf(
                "app.skein.MainActivity",
            )

        /**
         * E2.I6 (`skein-75x`): the single exported provider — the vault
         * `DocumentsProvider` behind the system file picker, permission-
         * guarded per POST_REVIEW_RESOLUTIONS.md §4.3. Its authority is
         * `VaultDocumentsProvider.AUTHORITY`, duplicated here as a literal
         * so this test does not depend on `:core:vault`.
         */
        const val DOCUMENTS_PROVIDER = "app.skein.core.vault.provider.VaultDocumentsProvider"
        const val DOCUMENTS_AUTHORITY = "us.aherrera.skein.documents"
        const val MANAGE_DOCUMENTS = "android.permission.MANAGE_DOCUMENTS"

        /**
         * E5.I10 (`skein-7v3`): the single exported service — WorkManager's
         * JobScheduler bridge (`androidx.work:work-runtime`). JobScheduler
         * can only bind to an exported job service, and the platform
         * protects it with `BIND_JOB_SERVICE`, a signature-level permission
         * only the system holds — the same "exported, but only to the OS"
         * posture as the `MANAGE_DOCUMENTS`-guarded provider above.
         * WorkManager's other merged components stay unexported; its
         * DUMP-guarded `DiagnosticsReceiver` is removed in the manifest.
         */
        const val WORKMANAGER_JOB_SERVICE = "androidx.work.impl.background.systemjob.SystemJobService"
        const val BIND_JOB_SERVICE = "android.permission.BIND_JOB_SERVICE"

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
         * Permissions merged in by androidx transitives, accepted as
         * safe-by-construction additions to the baseline:
         *
         *  - `app.skein.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` —
         *    self-defined, signature-protected permission androidx.core
         *    (1.9+) auto-declares and self-requests whenever a transitive
         *    dependency uses `ContextCompat.registerReceiver(...,
         *    RECEIVER_NOT_EXPORTED)` internally, to keep dynamically-
         *    registered receivers unexported on pre-API-33 devices. Not
         *    requestable by any other app and strengthens rather than
         *    weakens the exported-component posture.
         *
         *  - `android.permission.USE_FINGERPRINT` — merged in by
         *    `androidx.biometric:1.1.0` (`skein-3el`, VaultKeyProvider) as
         *    `<uses-permission android:maxSdkVersion="28" .../>`. Skein's
         *    `minSdk = 30`, so this permission is a no-op at runtime — the
         *    OS never grants it on any device we ship to. Kept on the
         *    allowlist because merged-manifest parsers surface it anyway
         *    and stripping it out via `tools:node="remove"` would break
         *    older devices if the minSdk were ever lowered.
         */
        val ANDROIDX_INJECTED_PERMISSIONS =
            setOf(
                "app.skein.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                "android.permission.USE_FINGERPRINT",
            )

        val ALLOWED_PERMISSIONS =
            setOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.USE_BIOMETRIC",
            ) + ANDROIDX_INJECTED_PERMISSIONS
    }
}
