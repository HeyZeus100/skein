// `E2.I3`: hand-rolled RFC 9562 §5.7 UUIDv7 generator. No third-party UUID
// library — every document id (`FrontmatterKeys.ID`, `documents.id`) is
// minted here.
//
// Bit layout of the 128-bit value (big-endian, matching the canonical
// 8-4-4-4-12 hex string):
//   bits 127..80 (48 bits): unix_ts_ms      — milliseconds since the epoch
//   bits  79..76 ( 4 bits): ver             — fixed `0111` (7)
//   bits  75..64 (12 bits): counter         — sub-ms monotonic counter
//   bits  63..62 ( 2 bits): var             — fixed `10`
//   bits  61.. 0 (62 bits): rand_b          — `SecureRandom` bits
//
// Monotonicity within (and across) the same millisecond is provided by a
// `(lastMs, counter)` pair guarded by a single lock: a call in a new
// millisecond reseeds the counter to a fresh random 12-bit value (RFC 9562
// §6.2 Method 1 recommends this to avoid the counter itself becoming a
// side-channel); a call in the same (or an earlier — see below) millisecond
// increments the counter. If the counter would overflow its 12 bits inside
// one millisecond, `lastMs` is advanced by one and the counter reset to 0
// ("borrowing" a future millisecond) — this also transparently absorbs a
// backward clock jump: the emitted timestamp field never regresses, so the
// canonical hex string is always strictly greater than the previous call's,
// regardless of what the wall clock reports.

package app.skein.core.vault.id

import java.security.SecureRandom
import java.time.Clock

public object Uuid7 {
    private const val COUNTER_BITS = 12
    private const val COUNTER_MASK = (1 shl COUNTER_BITS) - 1 // 0xFFF
    private const val RAND_B_BITS = 62
    private val RAND_B_MASK = (1L shl RAND_B_BITS) - 1

    private val random = SecureRandom()
    private val lock = Any()
    private var lastMs = -1L
    private var counter = 0

    /** Canonical lowercase 36-char `8-4-4-4-12` hex form, e.g. `018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b`. */
    public fun generate(clock: Clock = Clock.systemUTC()): String {
        val (ms, ctr) = nextTimestampAndCounter(clock.millis())
        val randB = nextRandomBits62()

        val versionNibble = 0x7000L
        val mostSigBits = (ms shl 16) or versionNibble or ctr.toLong()

        val variantBits = 0b10L shl 62
        val leastSigBits = variantBits or randB

        return toCanonicalHex(mostSigBits, leastSigBits)
    }

    private fun nextTimestampAndCounter(nowMs: Long): Pair<Long, Int> {
        synchronized(lock) {
            if (nowMs > lastMs) {
                lastMs = nowMs
                counter = random.nextInt(COUNTER_MASK + 1)
            } else {
                counter += 1
                if (counter > COUNTER_MASK) {
                    counter = 0
                    lastMs += 1
                }
            }
            return lastMs to counter
        }
    }

    private fun nextRandomBits62(): Long {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        var value = 0L
        for (b in bytes) {
            value = (value shl 8) or (b.toLong() and 0xFF)
        }
        return value and RAND_B_MASK
    }

    private fun toCanonicalHex(
        mostSigBits: Long,
        leastSigBits: Long,
    ): String {
        val msbHex = mostSigBits.toULong().toString(16).padStart(16, '0')
        val lsbHex = leastSigBits.toULong().toString(16).padStart(16, '0')
        val hex = msbHex + lsbHex
        return buildString(36) {
            append(hex, 0, 8)
            append('-')
            append(hex, 8, 12)
            append('-')
            append(hex, 12, 16)
            append('-')
            append(hex, 16, 20)
            append('-')
            append(hex, 20, 32)
        }
    }
}
