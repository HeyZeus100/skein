package us.aherrera.skein.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.4 `Blake3RevisionHashTest`:
 * canonicalization (LF-only line endings, frontmatter key sort) produces
 * identical hashes for cosmetic-only edits, and different hashes for real
 * ones.
 */
class RevisionHashingTest {
    private val empty = JsonObject(emptyMap())

    @Test
    fun `hash is 64 lowercase hex characters`() {
        val hash = RevisionHashing.compute("body", empty)
        assertTrue(hash, Regex("^[a-f0-9]{64}$").matches(hash))
    }

    @Test
    fun `CRLF and LF line endings hash identically`() {
        assertEquals(
            RevisionHashing.compute("one\ntwo\nthree", empty),
            RevisionHashing.compute("one\r\ntwo\r\nthree", empty),
        )
    }

    @Test
    fun `bare CR line endings hash identically to LF`() {
        assertEquals(
            RevisionHashing.compute("one\ntwo", empty),
            RevisionHashing.compute("one\rtwo", empty),
        )
    }

    @Test
    fun `frontmatter key order does not change the hash`() {
        val a =
            buildJsonObject {
                put("tags", JsonPrimitive("x"))
                put("alpha", JsonPrimitive(1))
            }
        val b =
            buildJsonObject {
                put("alpha", JsonPrimitive(1))
                put("tags", JsonPrimitive("x"))
            }
        assertEquals(RevisionHashing.compute("body", a), RevisionHashing.compute("body", b))
    }

    @Test
    fun `nested frontmatter key order does not change the hash`() {
        val a =
            buildJsonObject {
                put(
                    "meta",
                    buildJsonObject {
                        put("z", JsonPrimitive(1))
                        put("a", JsonPrimitive(2))
                    },
                )
            }
        val b =
            buildJsonObject {
                put(
                    "meta",
                    buildJsonObject {
                        put("a", JsonPrimitive(2))
                        put("z", JsonPrimitive(1))
                    },
                )
            }
        assertEquals(RevisionHashing.compute("body", a), RevisionHashing.compute("body", b))
    }

    @Test
    fun `array order does change the hash`() {
        val a =
            buildJsonObject {
                put(
                    "tags",
                    buildJsonArray {
                        add(JsonPrimitive("x"))
                        add(JsonPrimitive("y"))
                    },
                )
            }
        val b =
            buildJsonObject {
                put(
                    "tags",
                    buildJsonArray {
                        add(JsonPrimitive("y"))
                        add(JsonPrimitive("x"))
                    },
                )
            }
        assertNotEquals(RevisionHashing.compute("body", a), RevisionHashing.compute("body", b))
    }

    @Test
    fun `the frontmatter id key is excluded so identical content is identically addressed`() {
        val one =
            buildJsonObject {
                put("id", JsonPrimitive("doc-1"))
                put("tags", JsonPrimitive("t"))
            }
        val two =
            buildJsonObject {
                put("id", JsonPrimitive("doc-2"))
                put("tags", JsonPrimitive("t"))
            }
        assertEquals(RevisionHashing.compute("same body", one), RevisionHashing.compute("same body", two))
    }

    @Test
    fun `a one-character body edit changes the hash`() {
        assertNotEquals(
            RevisionHashing.compute("hello", empty),
            RevisionHashing.compute("hellp", empty),
        )
    }

    @Test
    fun `a frontmatter value edit changes the hash`() {
        assertNotEquals(
            RevisionHashing.compute("body", buildJsonObject { put("tags", JsonPrimitive("a")) }),
            RevisionHashing.compute("body", buildJsonObject { put("tags", JsonPrimitive("b")) }),
        )
    }

    @Test
    fun `a null body hashes the same as an empty body`() {
        assertEquals(RevisionHashing.compute(null, empty), RevisionHashing.compute("", empty))
    }

    @Test
    fun `length prefixing keeps the body-frontmatter split unambiguous`() {
        // Without the length prefixes these two would concatenate to the
        // same bytes; with them they must not collide.
        val a = RevisionHashing.compute("ab", buildJsonObject { put("k", JsonPrimitive("")) })
        val b = RevisionHashing.compute("a", buildJsonObject { put("bk", JsonPrimitive("")) })
        assertNotEquals(a, b)
    }

    @Test
    fun `a body long enough to be streamed in slices hashes consistently`() {
        // `compute` feeds the body to the hasher in 64 Ki-char slices; a body
        // spanning several of them, with multi-byte and surrogate-pair
        // characters sitting near the boundaries, must hash the same as the
        // logically identical content assembled differently.
        val unit = "abcé中😀"
        val long = unit.repeat(100_000)
        assertEquals(RevisionHashing.compute(long, empty), RevisionHashing.compute(long, empty))
        assertNotEquals(RevisionHashing.compute(long, empty), RevisionHashing.compute(long + "x", empty))
    }

    @Test
    fun `the hash is exactly BLAKE3 over the documented framing, surrogate pairs included`() {
        // A slice boundary must never split a surrogate pair — doing so emits
        // U+FFFD and silently changes the address of the document. This body
        // puts one exactly on the 64 Ki-char slice boundary, and rebuilds the
        // framed bytes independently of `compute`'s streaming path.
        val body = "x".repeat(64 * 1024 - 1) + "😀" + "tail"
        val canonicalFrontmatter = "{}".toByteArray(Charsets.UTF_8)
        val framed =
            "skein/revision/v1\u0000".toByteArray(Charsets.UTF_8) +
                byteArrayOf(canonicalFrontmatter.size.toByte(), 0, 0, 0, 0, 0, 0, 0) +
                canonicalFrontmatter +
                body.toByteArray(Charsets.UTF_8)

        assertEquals(Blake3.hex(framed), RevisionHashing.compute(body, empty))
    }

    @Test
    fun `canonical body normalizes every line ending form`() {
        assertEquals("a\nb\nc\n", RevisionHashing.canonicalBody("a\r\nb\rc\n"))
    }

    @Test
    fun `canonical frontmatter is compact sorted json without the id key`() {
        val fm =
            buildJsonObject {
                put("id", JsonPrimitive("doc-1"))
                put("zed", JsonPrimitive(2))
                put("alpha", JsonPrimitive("a b"))
            }
        assertEquals("""{"alpha":"a b","zed":2}""", RevisionHashing.canonicalFrontmatter(fm))
    }

    @Test
    fun `excerpt hash is domain-separated from the revision hash`() {
        assertNotEquals(
            RevisionHashing.excerptHash("text"),
            RevisionHashing.compute("text", empty),
        )
    }
}
