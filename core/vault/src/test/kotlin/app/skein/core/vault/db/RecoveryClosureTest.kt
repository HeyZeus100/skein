package app.skein.core.vault.db

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class RecoveryClosureTest {
    @Test
    fun `native close with outstanding statement is not a closed recovery receipt`() {
        val connection = SkeinSQLiteConnection(FakeSkeinSQLiteNative(), 1)
        val first = connection.prepare("SELECT 1")
        val second = connection.prepare("SELECT 2")
        connection.close()
        assertThat(connection.closedForRecovery).isFalse()
        first.close()
        first.close()
        assertThat(connection.closedForRecovery).isFalse()
        second.close()
        assertThat(connection.closedForRecovery).isTrue()
    }

    @Test
    fun `failed finalization never releases closed recovery receipt`() {
        val native =
            object : SkeinSQLiteNative by FakeSkeinSQLiteNative() {
                override fun nativeFinalize(stmtHandle: Long) = error("injected")
            }
        val connection = SkeinSQLiteConnection(native, 1)
        val statement = connection.prepare("SELECT 1")
        connection.close()
        assertThrows(IllegalStateException::class.java) { statement.close() }
        statement.close()
        assertThat(connection.closedForRecovery).isFalse()
    }

    @Test
    fun `failed native close stays uncertain after repeated close`() {
        val native =
            object : SkeinSQLiteNative by FakeSkeinSQLiteNative() {
                override fun nativeClose(dbHandle: Long) = error("injected")
            }
        val connection = SkeinSQLiteConnection(native, 1)
        assertThrows(IllegalStateException::class.java) { connection.close() }
        connection.close()
        assertThat(connection.closedForRecovery).isFalse()
    }
}
