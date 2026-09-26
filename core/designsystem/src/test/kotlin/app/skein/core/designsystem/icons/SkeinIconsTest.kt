// skein-xtov.23.6 (DS6): keeps `SkeinIcons` and the bundled
// `res/drawable/ic_skein_*.xml` set in lockstep so a future icon add/remove
// can't silently drift the two apart — every accessor must resolve to a
// real drawable, and every `ic_skein_*` drawable must have an accessor (no
// orphans left behind by a renamed or removed concept).
package app.skein.core.designsystem.icons

import android.content.res.Resources
import app.skein.core.designsystem.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkeinIconsTest {
    @Test
    fun everySkeinIconResolvesAndNoDrawableIsOrphaned() {
        val resources: Resources = RuntimeEnvironment.getApplication().resources

        // Reflection, not `SkeinIcons::class.memberProperties` (kotlin-reflect
        // isn't a dependency anywhere in this project — see build.gradle.kts).
        // A Kotlin `object`'s non-const `val Int` properties compile to
        // private static final backing fields with public getters, so plain
        // java.lang.reflect sees every one of them without it. `isPrivate`
        // is what excludes the Compose compiler's injected `public static
        // final int $stable` marker field (also `Int`-typed, legitimately
        // 0) from being mistaken for a drawable id.
        val iconFields =
            SkeinIcons::class.java.declaredFields
                .filter { it.type == Int::class.javaPrimitiveType && Modifier.isPrivate(it.modifiers) }
                .onEach { it.isAccessible = true }
        assertTrue("SkeinIcons declares no icons", iconFields.isNotEmpty())

        val referencedNames =
            iconFields
                .map { field ->
                    val id = field.get(SkeinIcons) as Int
                    // Resources.NotFoundException here IS the "every entry
                    // resolves to an existing drawable" assertion.
                    val name = resources.getResourceEntryName(id)
                    assertTrue(
                        "SkeinIcons.${field.name} resolves to '$name', not an ic_skein_* drawable",
                        name.startsWith("ic_skein_"),
                    )
                    name
                }.toSet()

        val bundledNames =
            R.drawable::class.java.declaredFields
                .map { it.name }
                .filter { it.startsWith("ic_skein_") }
                .toSet()

        val orphans = bundledNames - referencedNames
        assertTrue("Drawables with no SkeinIcons accessor: $orphans", orphans.isEmpty())

        val dangling = referencedNames - bundledNames
        assertTrue("SkeinIcons accessors pointing at a missing drawable: $dangling", dangling.isEmpty())
    }
}
