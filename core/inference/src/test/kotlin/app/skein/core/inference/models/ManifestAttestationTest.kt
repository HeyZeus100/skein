// skein-st1r: §2.4's `AttestationDegradationTest`, in the form the JVM can
// prove today — that origin trust and content trust are structurally separate.
//
// The load gate takes a `ManifestBinding` and a `ModelHandle` and nothing
// else; there is no parameter through which an `AttestationStatus` could
// reach it. So a degraded attestation cannot weaken the digest gate and a
// verified one cannot excuse a digest mismatch. When `E3.I6` lands the
// sigstore implementation, the behavioural half of this test (expired trust
// root still loads, tampered digest still refuses) attaches here.

package app.skein.core.inference.models

import app.skein.core.verify.ModelVerifier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ManifestAttestationTest {
    @Test
    fun `the default binding reports everything as unattested`() {
        val manifest = parsedManifest()

        assertThat(ManifestAttestation.Unavailable.verify(manifest, null).status)
            .isEqualTo(AttestationStatus.UNAVAILABLE)
    }

    @Test
    fun `an unattested verdict claims no coverage`() {
        assertThat(Attestation.Unattested.coverage).isEmpty()
    }

    @Test
    fun `a manifest binding carries no attestation field`() {
        // The structural assertion: `BoundFile` is digests and sizes only, so
        // nothing in the load gate can branch on origin trust.
        val fields = BoundFile::class.java.declaredFields.map { it.name }

        assertThat(fields).containsNoneOf("attestation", "attestationStatus", "originTrust")
    }

    @Test
    fun `a verified attestation cannot be supplied to the load gate`() {
        val parameters =
            ModelVerifier::class.java.methods
                .first { it.name == "verifyForLoad" }
                .parameterTypes

        assertThat(parameters.map { it.simpleName }).doesNotContain("Attestation")
    }
}
