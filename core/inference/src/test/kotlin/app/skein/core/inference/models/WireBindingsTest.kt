// skein-28wm: the store-side -> wire `ManifestBinding` converter, exercised
// end to end — real store-side `bind()`, real files on a real temp
// filesystem, real `ParcelFileDescriptor`s, and a real `Parcel` round trip
// (Robolectric supplies working `android.os` implementations; see
// `:core:ipc`'s `ParcelRoundTripTest`, whose `roundTrip` helper this file
// reuses verbatim).
//
// AAA per test, one behavior per test, per `feedback_tdd_workflow`.

package app.skein.core.inference.models

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WireBindingsTest {
    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private var storeCounter = 0

    // --------------------------------------------------------------- files

    @Test
    fun `toWire maps main and every companion role in manifest order`() {
        val files = defaultFixtureFiles()
        val (_, bound) = importAndBind(files)

        val wire = WireBindings.toWire(bound)

        assertThat(wire.files.map { it.role }).containsExactly("main", "tokenizer", "license").inOrder()
    }

    @Test
    fun `toWire carries the manifest id and version from the binding`() {
        val (_, bound) = importAndBind(defaultFixtureFiles())

        val wire = WireBindings.toWire(bound)

        assertThat(wire.manifestId).isEqualTo(bound.manifestId)
        assertThat(wire.manifestVersion).isEqualTo(bound.manifestVersion)
    }

    @Test
    fun `toWire carries expected sha256 verbatim for every file`() {
        val (_, bound) = importAndBind(defaultFixtureFiles())

        val wire = WireBindings.toWire(bound)

        assertThat(wire.files.map { it.expectedSha256 }).isEqualTo(bound.files.map { it.expectedSha256 })
    }

    @Test
    fun `toWire carries expected size bytes verbatim for every file`() {
        val (_, bound) = importAndBind(defaultFixtureFiles())

        val wire = WireBindings.toWire(bound)

        assertThat(wire.files.map { it.expectedSizeBytes }).isEqualTo(bound.files.map { it.expectedSizeBytes })
    }

    @Test
    fun `toWire opens a readable descriptor over the real main file bytes`() {
        val files = defaultFixtureFiles()
        val (_, bound) = importAndBind(files)

        val wire = WireBindings.toWire(bound)

        val mainBytes = readAll(wire.files.first { it.role == "main" }.fd)
        assertThat(mainBytes).isEqualTo(files.first { it.role == ModelFileRole.MAIN }.content)
    }

    @Test
    fun `toWire opens a readable descriptor over a companion's real bytes`() {
        val files = defaultFixtureFiles()
        val (_, bound) = importAndBind(files)

        val wire = WireBindings.toWire(bound)

        val tokenizerBytes = readAll(wire.files.first { it.role == "tokenizer" }.fd)
        assertThat(tokenizerBytes).isEqualTo(files.first { it.role == ModelFileRole.TOKENIZER }.content)
    }

    // ---------------------------------------------------------- attestation

    @Test
    fun `toWire returns a null attestation when the manifest declares none`() {
        val (_, bound) = importAndBind(defaultFixtureFiles())

        val wire = WireBindings.toWire(bound)

        assertThat(wire.attestation).isNull()
    }

    @Test
    fun `toWire returns a null attestation when no bundle file was resolved for it`() {
        val files = defaultFixtureFiles()
        val (manifest, bound) = importAndBind(files, manifestJsonWithAttestation(files))

        val wire = WireBindings.toWire(bound, attestation = manifest.attestation)

        assertThat(wire.attestation).isNull()
    }

    @Test
    fun `toWire builds an AttestationRefParcel when a bundle file is supplied`() {
        val files = defaultFixtureFiles()
        val (manifest, bound) = importAndBind(files, manifestJsonWithAttestation(files))
        val bundle = temp.newFile("model.sigstore.json")
        bundle.writeText(SIGSTORE_BUNDLE_CONTENTS)

        val wire = WireBindings.toWire(bound, attestation = manifest.attestation, attestationBundle = bundle)

        assertThat(wire.attestation).isNotNull()
    }

    @Test
    fun `toWire's attestation covers default to main when the manifest omits covers`() {
        val files = defaultFixtureFiles()
        val (manifest, bound) = importAndBind(files, manifestJsonWithAttestation(files))
        val bundle = temp.newFile("model.sigstore.json")
        bundle.writeText(SIGSTORE_BUNDLE_CONTENTS)

        val wire = WireBindings.toWire(bound, attestation = manifest.attestation, attestationBundle = bundle)

        assertThat(wire.attestation?.covers).containsExactly("main")
    }

    @Test
    fun `toWire's attestation descriptor is readable over the supplied bundle bytes`() {
        val files = defaultFixtureFiles()
        val (manifest, bound) = importAndBind(files, manifestJsonWithAttestation(files))
        val bundle = temp.newFile("model.sigstore.json")
        bundle.writeText(SIGSTORE_BUNDLE_CONTENTS)

        val wire = WireBindings.toWire(bound, attestation = manifest.attestation, attestationBundle = bundle)

        assertThat(readAll(wire.attestation!!.bundleFd).decodeToString()).isEqualTo(SIGSTORE_BUNDLE_CONTENTS)
    }

    // ------------------------------------------------------------ fd owners

    @Test
    fun `a failed conversion closes every descriptor already opened`() {
        val files = defaultFixtureFiles()
        val (_, bound) = importAndBind(files)
        denyReadAccess(bound.byRole(ModelFileRole.TOKENIZER)!!.path)
        val opened = mutableListOf<ParcelFileDescriptor>()

        assertThrows(IOException::class.java) {
            WireBindings.buildWire(bound, opened = opened)
        }

        assertThat(opened).isNotEmpty()
        assertThat(opened.all { !it.fileDescriptor.valid() }).isTrue()
    }

    @Test
    fun `the wire binding survives a real Parcel round trip`() {
        val files = defaultFixtureFiles()
        val (_, bound) = importAndBind(files)
        val original = WireBindings.toWire(bound)

        val restored = roundTrip(original)

        val restoredMainBytes = readAll(restored.files.first { it.role == "main" }.fd)
        assertThat(restoredMainBytes).isEqualTo(files.first { it.role == ModelFileRole.MAIN }.content)
    }

    @Test
    fun `the client's descriptor is already closed once the wire binding is marshalled`() {
        // POST_REVIEW_RESOLUTIONS.md §3.2 rule 3 restated for this handoff
        // (see WireBindings.kt's header): the client never closes these fds
        // itself, because AIDL's generated stubs marshal every `in`
        // ParcelFileDescriptor with PARCELABLE_WRITE_RETURN_VALUE, which
        // closes the sender's local copy as a side effect of the transaction.
        // Robolectric models that exactly, so this is provable directly.
        val files = defaultFixtureFiles()
        val (_, bound) = importAndBind(files)
        val original = WireBindings.toWire(bound)
        val clientSideFd = original.files.first { it.role == "main" }.fd

        roundTrip(original)

        assertThat(clientSideFd.fileDescriptor.valid()).isFalse()
    }

    // --------------------------------------------------------------- helpers

    private fun importAndBind(
        files: List<FixtureFile>,
        manifestJsonText: String = manifestJson(files),
    ): Pair<ModelManifest, ManifestBinding> {
        val manifest = (ModelManifest.parse(manifestJsonText) as ManifestParse.Parsed).manifest
        val store = ImmutableModelStore(temp.newFolder("models-${storeCounter++}"))
        val imported = (store.import(manifest, sourceOf(files)) as ImportResult.Imported).model
        val bound = (ManifestBinding.bind(manifest, imported) as BindResult.Bound).binding
        return manifest to bound
    }

    /** Splices a valid `attestation` object into the fixture manifest JSON, covers omitted. */
    private fun manifestJsonWithAttestation(files: List<FixtureFile>): String =
        manifestJson(files).trimEnd().removeSuffix("}") +
            ",\n" +
            """
            |  "attestation": {
            |    "bundle_file": "model.sigstore.json",
            |    "certificate_identity": "https://github.com/skein/models",
            |    "certificate_oidc_issuer": "https://token.actions.githubusercontent.com"
            |  }
            |}
            """.trimMargin()

    /** Revokes every permission bit so `ParcelFileDescriptor.open` fails without needing directory write access. */
    private fun denyReadAccess(target: File) {
        val view = Files.getFileAttributeView(target.toPath(), PosixFileAttributeView::class.java)
        requireNotNull(view) { "test filesystem has no POSIX attribute view" }.setPermissions(emptySet())
    }

    private fun readAll(fd: ParcelFileDescriptor): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }

    /** Reused verbatim from `:core:ipc`'s `ParcelRoundTripTest`; see that file's header for why. */
    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> roundTrip(value: T): T {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(value, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
            parcel.setDataPosition(0)
            return requireNotNull(parcel.readParcelable<T>(T::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        const val SIGSTORE_BUNDLE_CONTENTS = "{\"mediaType\":\"sigstore.bundle\"}"
    }
}
