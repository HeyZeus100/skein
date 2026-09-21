package app.skein.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ManifestGuardTaskTest {

    private lateinit var manifestsDir: File

    @Before
    fun setUp() {
        manifestsDir = ProjectBuilder.builder().build().layout.buildDirectory.get().asFile
        manifestsDir.mkdirs()
    }

    private fun manifestFile(name: String, xml: String): File {
        val file = File(manifestsDir, name)
        file.writeText(xml.trimIndent())
        return file
    }

    private fun taskFor(manifest: File): ManifestGuardTask {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("checkManifestGuardsTest", ManifestGuardTask::class.java).get()
        task.mergedManifest.set(manifest)
        task.variantName.set("fossDebug")
        return task
    }

    private fun cleanManifest(extra: String = "") = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.skein">
            <application>
                <service android:name="app.skein.inference.service.InferenceService"
                    android:process=":inference"
                    android:isolatedProcess="true"
                    android:exported="false" />
                <service android:name="app.skein.embedder.service.EmbedderService"
                    android:process=":embedder"
                    android:isolatedProcess="true"
                    android:exported="false" />
                $extra
            </application>
        </manifest>
    """.trimIndent()

    @Test
    fun `passes on a clean manifest`() {
        val task = taskFor(manifestFile("clean.xml", cleanManifest()))
        task.checkManifest()
    }

    @Test
    fun `passes on the exported MANAGE_DOCUMENTS-guarded vault DocumentsProvider`() {
        // E2.I6 (skein-75x): a DocumentsProvider MUST be exported and guarded
        // by android:permission="android.permission.MANAGE_DOCUMENTS" — the
        // platform refuses anything else. That is a component permission,
        // not a <uses-permission>, so the denylist (INTERNET,
        // ACCESS_NETWORK_STATE) is untouched and no allowlist is needed;
        // this test pins that the guard keeps accepting the declaration.
        // Joined with the template's own inner indent so cleanManifest()'s
        // trimIndent() still strips the common prefix (a zero-indent line
        // here would leave the <?xml ...?> declaration indented, which the
        // XML parser rejects).
        val provider = listOf(
            """<provider android:name="app.skein.core.vault.provider.VaultDocumentsProvider"""",
            """    android:authorities="us.aherrera.skein.documents"""",
            """    android:exported="true"""",
            """    android:grantUriPermissions="false"""",
            """    android:permission="android.permission.MANAGE_DOCUMENTS">""",
            """    <intent-filter>""",
            """        <action android:name="android.content.action.DOCUMENTS_PROVIDER" />""",
            """    </intent-filter>""",
            """    <grant-uri-permission android:pathPrefix="/document/note:" />""",
            """    <grant-uri-permission android:pathPrefix="/document/att:" />""",
            """</provider>""",
        ).joinToString(separator = "\n" + " ".repeat(16))
        val xml = cleanManifest(extra = provider)
        val task = taskFor(manifestFile("documents-provider.xml", xml))

        task.checkManifest()
    }

    @Test
    fun `fails when INTERNET permission is present`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.skein">
                <uses-permission android:name="android.permission.INTERNET" />
                <application>
                </application>
            </manifest>
        """.trimIndent()
        val task = taskFor(manifestFile("internet.xml", xml))

        val error = runCatching { task.checkManifest() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for INTERNET permission")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
        assertTrue(error.message!!.contains("android.permission.INTERNET"))
    }

    @Test
    fun `fails when ACCESS_NETWORK_STATE permission is present`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.skein">
                <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
                <application />
            </manifest>
        """.trimIndent()
        val task = taskFor(manifestFile("network-state.xml", xml))

        val error = runCatching { task.checkManifest() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for ACCESS_NETWORK_STATE permission")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
    }

    @Test
    fun `fails when a GMS component is present`() {
        val xml = cleanManifest(
            extra = """<meta-data android:name="com.google.android.gms.version" android:value="1" />""",
        )
        val task = taskFor(manifestFile("gms.xml", xml))

        val error = runCatching { task.checkManifest() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for a GMS component")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
        assertTrue(error.message!!.contains("com.google.android.gms"))
    }

    @Test
    fun `fails when InferenceService is missing isolatedProcess`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.skein">
                <application>
                    <service android:name="app.skein.inference.service.InferenceService"
                        android:process=":inference"
                        android:exported="false" />
                </application>
            </manifest>
        """.trimIndent()
        val task = taskFor(manifestFile("not-isolated.xml", xml))

        val error = runCatching { task.checkManifest() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for a non-isolated InferenceService")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
        assertTrue(error.message!!.contains("isolatedProcess"))
    }

    @Test
    fun `fails when EmbedderService is exported`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.skein">
                <application>
                    <service android:name="app.skein.embedder.service.EmbedderService"
                        android:process=":embedder"
                        android:isolatedProcess="true"
                        android:exported="true" />
                </application>
            </manifest>
        """.trimIndent()
        val task = taskFor(manifestFile("exported.xml", xml))

        val error = runCatching { task.checkManifest() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for an exported EmbedderService")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
        assertTrue(error.message!!.contains("exported"))
    }
}
