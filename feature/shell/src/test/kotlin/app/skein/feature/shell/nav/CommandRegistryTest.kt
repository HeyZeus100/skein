package app.skein.feature.shell.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan `E6.I4` slice A (bd `skein-ps0`): `CommandRegistry.register(scope, commands)` and palette filtering/matching. */
class CommandRegistryTest {
    private fun noOpCommand(
        keyword: String,
        hint: String = "",
    ) = Command(keyword = keyword, hint = hint) {}

    @Test
    fun `an empty registry has no global commands`() {
        val registry = CommandRegistry()

        assertTrue(registry.commands().isEmpty())
    }

    @Test
    fun `register makes commands available under their scope`() {
        val registry = CommandRegistry()
        val newNote = noOpCommand("new note")

        registry.register(CommandScope.GLOBAL, listOf(newNote))

        assertEquals(listOf(newNote), registry.commands())
    }

    @Test
    fun `commands defaults to GLOBAL only, excluding EDITOR-scoped commands`() {
        val registry = CommandRegistry()
        registry.register(CommandScope.GLOBAL, listOf(noOpCommand("new note")))
        registry.register(CommandScope.EDITOR, listOf(noOpCommand("ai continue")))

        val globalOnly = registry.commands()

        assertEquals(listOf("new note"), globalOnly.map { it.keyword })
    }

    @Test
    fun `commands can be asked for multiple scopes at once`() {
        val registry = CommandRegistry()
        registry.register(CommandScope.GLOBAL, listOf(noOpCommand("new note")))
        registry.register(CommandScope.EDITOR, listOf(noOpCommand("ai continue")))

        val both = registry.commands(setOf(CommandScope.GLOBAL, CommandScope.EDITOR))

        assertEquals(setOf("new note", "ai continue"), both.map { it.keyword }.toSet())
    }

    @Test
    fun `re-registering a scope replaces its commands rather than appending`() {
        val registry = CommandRegistry()
        registry.register(CommandScope.GLOBAL, listOf(noOpCommand("new note")))

        registry.register(CommandScope.GLOBAL, listOf(noOpCommand("new chat")))

        assertEquals(listOf("new chat"), registry.commands().map { it.keyword })
    }

    @Test
    fun `unregister drops every command for that scope`() {
        val registry = CommandRegistry()
        registry.register(CommandScope.GLOBAL, listOf(noOpCommand("new note")))

        registry.unregister(CommandScope.GLOBAL)

        assertTrue(registry.commands().isEmpty())
    }

    @Test
    fun `filter with a blank query returns every registered command`() {
        val registry = CommandRegistry()
        registry.register(CommandScope.GLOBAL, listOf(noOpCommand("new note"), noOpCommand("new chat")))

        assertEquals(2, registry.filter("").size)
    }

    @Test
    fun `filter narrows to commands whose keyword starts with what was typed`() {
        val registry = CommandRegistry()
        registry.register(CommandScope.GLOBAL, listOf(noOpCommand("new note"), noOpCommand("persona")))

        val filtered = registry.filter("new")

        assertEquals(listOf("new note"), filtered.map { it.keyword })
    }

    @Test
    fun `filter is case-insensitive`() {
        val registry = CommandRegistry()
        registry.register(CommandScope.GLOBAL, listOf(noOpCommand("new note")))

        assertEquals(1, registry.filter("NEW").size)
    }

    @Test
    fun `match finds a command typed with no argument`() {
        val registry = CommandRegistry()
        val newNote = noOpCommand("new note")
        registry.register(CommandScope.GLOBAL, listOf(newNote))

        val result = registry.match("new note")

        assertEquals(newNote to "", result)
    }

    @Test
    fun `match splits the keyword from its trailing argument`() {
        val registry = CommandRegistry()
        val newNote = noOpCommand("new note")
        registry.register(CommandScope.GLOBAL, listOf(newNote))

        val result = registry.match("new note Smoke test")

        assertEquals(newNote to "Smoke test", result)
    }

    @Test
    fun `match returns null for an incomplete keyword`() {
        val registry = CommandRegistry()
        registry.register(CommandScope.GLOBAL, listOf(noOpCommand("new note")))

        assertNull(registry.match("new"))
    }

    @Test
    fun `match returns null for an unknown command`() {
        val registry = CommandRegistry()
        registry.register(CommandScope.GLOBAL, listOf(noOpCommand("new note")))

        assertNull(registry.match("delete everything"))
    }

    @Test
    fun `match requires a word boundary after the keyword, not just a shared prefix`() {
        val registry = CommandRegistry()
        registry.register(CommandScope.GLOBAL, listOf(noOpCommand("new note"), noOpCommand("new")))

        // "newx" shares a prefix with "new" but is neither "new" alone nor
        // "new " followed by an argument, so nothing should match.
        val result = registry.match("newx")

        assertNull(result)
    }
}
