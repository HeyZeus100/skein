package app.skein.feature.chat

import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.feature.chat.entries.pipelineOver
import app.skein.testing.FakeImportService
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.scriptedEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImportSpaceTest {
    @Test
    fun `imports use the owning chat Space and preserve the default Space`() =
        runTest {
            for (owner in listOf("work-space", null)) {
                val repository = InMemoryVaultRepository()
                val chat = repository.createDocument(NewDocument(DocumentKind.CHAT, "Chat", null, personaId = owner))
                val importer = FakeImportService()
                val viewModel =
                    ChatViewModel(
                        chatDocId = chat.id,
                        vaultRepository = repository,
                        sendPipeline = pipelineOver(repository, scriptedEngine()),
                        onOpenSource = {},
                        scope = backgroundScope,
                        importService = importer,
                    )
                assertEquals(
                    "[[evidence.txt]]",
                    viewModel.attach("evidence.txt", "text/plain", "fact".byteInputStream()),
                )
                assertEquals(owner, importer.textImports.single().personaId)
            }
        }

    @Test
    fun `a deleted chat cannot silently import into the default Space`() =
        runTest {
            val repository = InMemoryVaultRepository()
            val chat =
                repository.createDocument(
                    NewDocument(DocumentKind.CHAT, "Chat", null, personaId = "work-space"),
                )
            val importer = FakeImportService()
            val viewModel =
                ChatViewModel(
                    chatDocId = chat.id,
                    vaultRepository = repository,
                    sendPipeline = pipelineOver(repository, scriptedEngine()),
                    onOpenSource = {},
                    scope = backgroundScope,
                    importService = importer,
                )
            repository.deleteDocument(chat.id)
            assertTrue(
                runCatching { viewModel.attach("evidence.txt", "text/plain", "fact".byteInputStream()) }.isFailure,
            )
            assertTrue(importer.textImports.isEmpty())
        }
}
