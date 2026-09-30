package app.skein.core.vault.key

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyStore
import java.security.KeyStoreException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidKeystoreFacadeTest {
    @Test
    fun `null key lookup becomes typed absence without exposing the alias`() {
        val store = KeyStore.getInstance("JCEKS").apply { load(null) }
        val facade = AndroidKeystoreFacade(RuntimeEnvironment.getApplication()) { store }

        val failure =
            assertThrows(KeyMaterialMissingException::class.java) {
                facade.decryptCipher("private-alias-sentinel", ByteArray(12))
            }

        assertThat(failure.message).isEqualTo("device security key is unavailable")
        assertThat(facade.containsAlias("private-alias-sentinel")).isFalse()
        assertThat(store.size()).isEqualTo(0)
    }

    @Test
    fun `key lookup exception is not classified as missing`() {
        // An uninitialized KeyStore throws instead of successfully returning null.
        val store = KeyStore.getInstance("JCEKS")
        val facade = AndroidKeystoreFacade(RuntimeEnvironment.getApplication()) { store }

        assertThrows(KeyStoreException::class.java) {
            facade.decryptCipher("private-alias-sentinel", ByteArray(12))
        }
    }
}
