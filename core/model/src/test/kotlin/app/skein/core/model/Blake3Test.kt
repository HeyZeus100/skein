package app.skein.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [Blake3] to the published BLAKE3 test vectors (skein-uo5n /
 * `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.4 `Blake3RevisionHashTest`).
 *
 * The official vector inputs are `bytes(i % 251)` repeated — [pattern]
 * below. The lengths chosen here are the ones that exercise every branch
 * of the implementation: sub-block, exact block, block+1, the 1 KiB chunk
 * boundary either side, and several multi-chunk tree shapes (2, 3, 4, 8,
 * 100 chunks) so the left-complete subtree split and the chunk counter are
 * both covered. A hand-rolled hash that is only ever tested against itself
 * is worthless; these are the numbers that make it worth something.
 */
class Blake3Test {
    private val pattern: ByteArray = ByteArray(102_400) { (it % 251).toByte() }

    @Test
    fun `hashes the empty input to the published vector`() {
        assertEquals(
            "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262",
            Blake3.hexUtf8(""),
        )
    }

    @Test
    fun `hashes abc to the published vector`() {
        assertEquals(
            "6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85",
            Blake3.hexUtf8("abc"),
        )
    }

    @Test
    fun `matches the published vectors across block and chunk boundaries`() {
        val expected =
            mapOf(
                1 to "2d3adedff11b61f14c886e35afa036736dcd87a74d27b5c1510225d0f592e213",
                2 to "7b7015bb92cf0b318037702a6cdd81dee41224f734684c2c122cd6359cb1ee63",
                63 to "e9bc37a594daad83be9470df7f7b3798297c3d834ce80ba85d6e207627b7db7b",
                64 to "4eed7141ea4a5cd4b788606bd23f46e212af9cacebacdc7d1f4c6dc7f2511b98",
                65 to "de1e5fa0be70df6d2be8fffd0e99ceaa8eb6e8c93a63f2d8d1c30ecb6b263dee",
                127 to "d81293fda863f008c09e92fc382a81f5a0b4a1251cba1634016a0f86a6bd640d",
                128 to "f17e570564b26578c33bb7f44643f539624b05df1a76c81f30acd548c44b45ef",
                129 to "683aaae9f3c5ba37eaaf072aed0f9e30bac0865137bae68b1fde4ca2aebdcb12",
            )
        for ((length, hex) in expected) {
            assertEquals("length=$length", hex, Blake3.hex(pattern.copyOf(length)))
        }
    }

    @Test
    fun `matches the published vectors for multi-chunk tree inputs`() {
        val expected =
            mapOf(
                1023 to "10108970eeda3eb932baac1428c7a2163b0e924c9a9e25b35bba72b28f70bd11",
                1024 to "42214739f095a406f3fc83deb889744ac00df831c10daa55189b5d121c855af7",
                1025 to "d00278ae47eb27b34faecf67b4fe263f82d5412916c1ffd97c8cb7fb814b8444",
                2048 to "e776b6028c7cd22a4d0ba182a8bf62205d2ef576467e838ed6f2529b85fba24a",
                2049 to "5f4d72f40d7a5f82b15ca2b2e44b1de3c2ef86c426c95c1af0b6879522563030",
                3072 to "b98cb0ff3623be03326b373de6b9095218513e64f1ee2edd2525c7ad1e5cffd2",
                3073 to "7124b49501012f81cc7f11ca069ec9226cecb8a2c850cfe644e327d22d3e1cd3",
                4096 to "015094013f57a5277b59d8475c0501042c0b642e531b0a1c8f58d2163229e969",
                4097 to "9b4052b38f1c5fc8b1f9ff7ac7b27cd242487b3d890d15c96a1c25b8aa0fb995",
                5120 to "9cadc15fed8b5d854562b26a9536d9707cadeda9b143978f319ab34230535833",
                6144 to "3e2e5b74e048f3add6d21faab3f83aa44d3b2278afb83b80b3c35164ebeca205",
                7168 to "61da957ec2499a95d6b8023e2b0e604ec7f6b50e80a9678b89d2628e99ada77a",
                8192 to "aae792484c8efe4f19e2ca7d371d8c467ffb10748d8a5a1ae579948f718a2a63",
                8193 to "bab6c09cb8ce8cf459261398d2e7aef35700bf488116ceb94a36d0f5f1b7bc3b",
                16_384 to "f875d6646de28985646f34ee13be9a576fd515f76b5b0a26bb324735041ddde4",
                31_744 to "62b6960e1a44bcc1eb1a611a8d6235b6b4b78f32e7abc4fb4c6cdcce94895c47",
                102_400 to "bc3e3d41a1146b069abffad3c0d44860cf664390afce4d9661f7902e7943e085",
            )
        for ((length, hex) in expected) {
            assertEquals("length=$length", hex, Blake3.hex(pattern.copyOf(length)))
        }
    }

    @Test
    fun `streaming in arbitrary pieces matches the one-shot digest`() {
        // The whole point of the incremental API: the result must depend only
        // on the concatenated bytes, never on how the caller split them —
        // including splits that land inside a block, on a block boundary, and
        // across the 1 KiB chunk boundary.
        for (length in listOf(0, 1, 63, 64, 65, 1023, 1024, 1025, 2048, 4097, 10_000)) {
            val input = pattern.copyOf(length)
            val oneShot = Blake3.hex(input)
            for (pieceSize in listOf(1, 7, 64, 100, 512, 1024, 1500)) {
                val hasher = Blake3.Hasher()
                var at = 0
                while (at < length) {
                    val take = minOf(pieceSize, length - at)
                    hasher.update(input, at, take)
                    at += take
                }
                assertEquals("length=$length pieceSize=$pieceSize", oneShot, hasher.hex())
            }
        }
    }

    @Test
    fun `digest is 32 bytes and hex is 64 lowercase characters`() {
        assertEquals(32, Blake3.hash(byteArrayOf(1, 2, 3)).size)
        val hex = Blake3.hexUtf8("skein")
        assertEquals(64, hex.length)
        assertEquals(hex, hex.lowercase())
    }
}
