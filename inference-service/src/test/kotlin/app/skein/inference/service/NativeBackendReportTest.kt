// bd skein-gg11.2 (OL-05): `NativeBackendReport.parse` is the Kotlin half of
// `LlamaNative.backendReport`'s wire format — see that function's KDoc
// (`skein_jni.cpp`) for the format itself. Pure parsing logic, no native
// library required.

package app.skein.inference.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

private const val COMPILE_TIME_ONLY =
    "cpu_features=NEON,DOTPROD;devices=;gpu_layers_offloaded=0;n_outputs_max=-1;n_batch=-1;n_ubatch=-1"

class NativeBackendReportTest {
    @Test
    fun `parses a compile-time-only report`() {
        val report = NativeBackendReport.parse(COMPILE_TIME_ONLY)

        assertThat(report.devices).isEmpty()
        assertThat(report.cpuFeatures).containsExactly("NEON", "DOTPROD").inOrder()
        assertThat(report.gpuLayersOffloaded).isEqualTo(0)
        assertThat(listOf(report.nOutputsMax, report.nBatch, report.nUbatch))
            .isEqualTo(listOf(null, null, null))
    }

    @Test
    fun `parses a single CPU device`() {
        val raw = "cpu_features=NEON;devices=0:CPU;gpu_layers_offloaded=0;n_outputs_max=1;n_batch=512;n_ubatch=512"

        val report = NativeBackendReport.parse(raw)

        assertThat(report.devices).containsExactly(NativeBackendDevice(type = 0, name = "CPU"))
    }

    @Test
    fun `parses multiple devices in order`() {
        val raw =
            "cpu_features=NEON;devices=0:CPU,1:Vulkan;gpu_layers_offloaded=99;" +
                "n_outputs_max=1;n_batch=512;n_ubatch=512"

        val report = NativeBackendReport.parse(raw)

        assertThat(report.devices)
            .containsExactly(
                NativeBackendDevice(type = 0, name = "CPU"),
                NativeBackendDevice(type = 1, name = "Vulkan"),
            ).inOrder()
    }

    @Test
    fun `parses an unallowlisted device name as other`() {
        val raw = "cpu_features=;devices=1:other;gpu_layers_offloaded=0;n_outputs_max=-1;n_batch=-1;n_ubatch=-1"

        val report = NativeBackendReport.parse(raw)

        assertThat(report.devices.single().name).isEqualTo("other")
    }

    @Test
    fun `context fields present when a context exists`() {
        val raw = "cpu_features=NEON;devices=0:CPU;gpu_layers_offloaded=0;n_outputs_max=1;n_batch=512;n_ubatch=512"

        val report = NativeBackendReport.parse(raw)

        assertThat(listOf(report.nOutputsMax, report.nBatch, report.nUbatch))
            .isEqualTo(listOf(1, 512, 512))
    }

    @Test
    fun `negative-one sentinels become null`() {
        val report = NativeBackendReport.parse(COMPILE_TIME_ONLY)

        assertThat(listOf(report.nOutputsMax, report.nBatch, report.nUbatch))
            .isEqualTo(listOf(null, null, null))
    }

    @Test
    fun `empty cpu_features parses to an empty list`() {
        val report = NativeBackendReport.parse(COMPILE_TIME_ONLY.replace("NEON,DOTPROD", ""))

        assertThat(report.cpuFeatures).isEmpty()
    }
}
