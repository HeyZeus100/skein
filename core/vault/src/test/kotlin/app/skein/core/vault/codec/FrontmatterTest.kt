// JVM unit tests for `Frontmatter` (`E2.I3` acceptance criteria 2-4): the
// hand-rolled Obsidian-compatible YAML-subset codec used for every
// document's frontmatter block.

package app.skein.core.vault.codec

import app.skein.core.model.FrontmatterExamples
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test

public class FrontmatterTest {
    // ---- acceptance criterion 2: basic parse ----

    @Test
    public fun `parse extracts scalar and flow-list keys plus the body`() {
        val (frontmatter, body) = Frontmatter.parse("---\nid: 0192abc\ntags: [a, b]\n---\nbody")

        assertThat(frontmatter["id"]).isEqualTo(JsonPrimitive("0192abc"))
        assertThat(frontmatter["tags"]).isEqualTo(
            JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
        )
        assertThat(body).isEqualTo("body")
    }

    @Test
    public fun `text without frontmatter yields an empty object and the full text unchanged`() {
        val text = "Just a plain note with no header at all."

        val (frontmatter, body) = Frontmatter.parse(text)

        assertThat(frontmatter.entries).isEmpty()
        assertThat(body).isEqualTo(text)
    }

    @Test
    public fun `text starting with a fence but never closing it is treated as having no frontmatter`() {
        val text = "---\nid: unterminated\nno closing fence here"

        val (frontmatter, body) = Frontmatter.parse(text)

        assertThat(frontmatter.entries).isEmpty()
        assertThat(body).isEqualTo(text)
    }

    @Test
    public fun `block-style dash lists parse to the same value as flow-style lists`() {
        val (flow, _) = Frontmatter.parse("---\ntags: [a, b]\n---\nbody")
        val (block, _) = Frontmatter.parse("---\ntags:\n  - a\n  - b\n---\nbody")

        assertThat(block["tags"]).isEqualTo(flow["tags"])
    }

    @Test
    public fun `quoted strings are unescaped`() {
        val (frontmatter, _) = Frontmatter.parse("---\ntitle: \"Hello, \\\"World\\\"\"\n---\nbody")

        assertThat(frontmatter["title"]).isEqualTo(JsonPrimitive("Hello, \"World\""))
    }

    @Test
    public fun `bare booleans and integers are typed, not left as strings`() {
        val (frontmatter, _) = Frontmatter.parse("---\npinned: true\ncount: 3\n---\nbody")

        assertThat(frontmatter["pinned"]).isEqualTo(JsonPrimitive(true))
        assertThat(frontmatter["count"]).isEqualTo(JsonPrimitive(3L))
    }

    @Test
    public fun `an iso-8601 timestamp is preserved as a plain string scalar`() {
        val (frontmatter, _) = Frontmatter.parse("---\ncreated: 2026-09-20T12:00:00Z\n---\nbody")

        assertThat(frontmatter["created"]).isEqualTo(JsonPrimitive("2026-09-20T12:00:00Z"))
    }

    // ---- acceptance criterion 3: round-trip fidelity ----

    @Test
    public fun `render of parse is identity for the canonical FrontmatterExamples NOTE fixture`() {
        val fixture = FrontmatterExamples.NOTE

        val (frontmatter, body) = Frontmatter.parse(fixture)
        val rendered = Frontmatter.render(frontmatter, body)

        assertThat(rendered).isEqualTo(fixture)
    }

    @Test
    public fun `render of parse is identity for a note with an unknown nested key`() {
        // `meta` is outside the supported subset (a nested mapping) and must
        // round-trip byte-for-byte as an opaque block. Keys are already in
        // canonical order (`id`, then `tags`, then the unknown `meta` key
        // sorted into "...rest alphabetically") so the identity holds.
        val fixture =
            "---\n" +
                "id: 0192abc\n" +
                "tags: [a, b]\n" +
                "meta:\n" +
                "  foo: bar\n" +
                "  nested: true\n" +
                "---\n" +
                "Body text."

        val (frontmatter, body) = Frontmatter.parse(fixture)
        val rendered = Frontmatter.render(frontmatter, body)

        assertThat(rendered).isEqualTo(fixture)
        // The opaque key is still present in the parsed object (not dropped).
        assertThat(frontmatter["meta"]).isNotNull()
    }

    @Test
    public fun `render produces no header block when the frontmatter object is empty`() {
        val body = "Just a body, no frontmatter."

        val rendered = Frontmatter.render(buildJsonObject { }, body)

        assertThat(rendered).isEqualTo(body)
    }

    // ---- acceptance criterion 4: canonical key order ----

    @Test
    public fun `keys are emitted in canonical order then remaining keys alphabetically`() {
        val frontmatter =
            buildJsonObject {
                put("zeta", "z")
                put("source", "att:1")
                put("tags", JsonArray(listOf(JsonPrimitive("x"))))
                put("persona", "default")
                put("alpha", "a")
                put("updated", "2026-09-20T12:00:00Z")
                put("created", "2026-09-20T12:00:00Z")
                put("title", "T")
                put("kind", "note")
                put("id", "abc")
            }

        val rendered = Frontmatter.render(frontmatter, "body")
        val keyOrder =
            rendered
                .lines()
                .drop(1) // opening fence
                .takeWhile { it != "---" }
                .map { it.substringBefore(':') }

        assertThat(keyOrder).isEqualTo(
            listOf("id", "kind", "title", "created", "updated", "persona", "tags", "source", "alpha", "zeta"),
        )
    }

    @Test
    public fun `flow lists are rendered with a comma-space separator`() {
        val frontmatter =
            buildJsonObject {
                putJsonArray("tags") {
                    add(JsonPrimitive("example"))
                    add(JsonPrimitive("fixture"))
                }
            }

        val rendered = Frontmatter.render(frontmatter, "body")

        assertThat(rendered).contains("tags: [example, fixture]")
    }
}
