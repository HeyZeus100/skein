package app.skein.ingest

import app.skein.core.inference.thermal.ThermalState
import app.skein.core.rag.ingest.IngestPace
import org.junit.Assert.assertEquals
import org.junit.Test

/** E5.I10 (skein-7v3): thermal/battery → `IngestPace` mapping (bd: `Reduced` → 16, `Paused` → retry). */
class ThermalIngestPacerTest {
    private fun pacer(
        state: ThermalState,
        batteryOk: Boolean = true,
    ) = ThermalIngestPacer(thermalState = { state }, batteryOk = { batteryOk })

    @Test
    fun `Nominal on a healthy battery is FULL`() {
        assertEquals(IngestPace.FULL, pacer(ThermalState.Nominal).pace())
    }

    @Test
    fun `Nominal but unplugged and low is REDUCED, not a refusal to run`() {
        assertEquals(IngestPace.REDUCED, pacer(ThermalState.Nominal, batteryOk = false).pace())
    }

    @Test
    fun `Reduced is REDUCED`() {
        assertEquals(IngestPace.REDUCED, pacer(ThermalState.Reduced(threads = 2)).pace())
    }

    @Test
    fun `Paused is PAUSED regardless of battery`() {
        assertEquals(IngestPace.PAUSED, pacer(ThermalState.Paused(untilMs = 1L)).pace())
    }

    @Test
    fun `begin and end bracket the governor's activate and deactivate`() {
        val calls = mutableListOf<String>()
        val pacer =
            ThermalIngestPacer(
                thermalState = { ThermalState.Nominal },
                batteryOk = { true },
                activate = { calls += "activate" },
                deactivate = { calls += "deactivate" },
            )

        pacer.begin()
        pacer.end()

        assertEquals(listOf("activate", "deactivate"), calls)
    }
}
