// skein-xtov.24.4 (AL-05, throwaway): tells an entry which scene it is being
// rendered in. Nav3 moves an entry between scenes with movableContentOf; the
// move lands a frame after the window change (NavDisplay animates to the new
// scene in an effect), and it detaches the focused node. An entry that keys a
// one-shot `requestFocus()` on this identity restores focus after the move.
package app.skein.prototype.nav3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneDecoratorStrategy
import androidx.navigation3.scene.SceneDecoratorStrategyScope

val LocalSceneIdentity = compositionLocalOf<Any?> { null }

class SceneIdentityDecorator<T : Any> : SceneDecoratorStrategy<T> {
    override fun SceneDecoratorStrategyScope<T>.decorateScene(scene: Scene<T>): Scene<T> = IdentifiedScene(scene)
}

private class IdentifiedScene<T : Any>(
    private val scene: Scene<T>,
) : Scene<T> {
    // Class + key, so two library scenes that share a key stay distinct scenes.
    private val identity: Any = scene::class to scene.key
    override val key: Any get() = identity
    override val entries: List<NavEntry<T>> get() = scene.entries
    override val previousEntries: List<NavEntry<T>> get() = scene.previousEntries
    override val metadata: Map<String, Any> get() = scene.metadata
    override val content: @Composable () -> Unit = {
        CompositionLocalProvider(LocalSceneIdentity provides identity) { scene.content() }
    }

    override fun equals(other: Any?): Boolean = other is IdentifiedScene<*> && other.scene == scene

    override fun hashCode(): Int = scene.hashCode()
}
