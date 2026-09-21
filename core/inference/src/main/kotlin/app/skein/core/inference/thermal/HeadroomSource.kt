package app.skein.core.inference.thermal

/**
 * Android-free seam over `PowerManager.getThermalHeadroom(int)`
 * (API 30+; see https://developer.android.com/reference/android/os/PowerManager#getThermalHeadroom(int)).
 * Lets [ThermalGovernorCore] be unit-tested on the host JVM.
 */
public fun interface HeadroomSource {
    /**
     * Returns the current forecasted thermal headroom on the platform's
     * convention (0.0 = cool, 1.0 = the device's own throttling threshold,
     * >1.0 = already throttling), or [Float.NaN] when unsupported — either
     * because the platform itself returned NaN, or because the caller has
     * no other way to answer.
     */
    public fun headroomNow(): Float
}
