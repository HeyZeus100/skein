package app.skein.inference.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.reflect.Modifier

class LlamaNativeSurfaceTest {
    @Test
    fun generatedHelpersAreNotNative() {
        // Loading without initialization avoids System.loadLibrary on the JVM.
        val surface =
            Class.forName(
                "app.skein.inference.service.LlamaNative",
                false,
                javaClass.classLoader,
            )
        val nativeHelpers =
            surface.declaredMethods
                .filter { '$' in it.name && Modifier.isNative(it.modifiers) }
                .map { it.name }

        assertThat(nativeHelpers).isEmpty()
    }
}
