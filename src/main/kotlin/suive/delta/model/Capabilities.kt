package suive.delta.model

object ClientCapabilities
data class ServerCapabilities(
    val textDocumentSync: Int = 2,
    val completionProvider: CompletionOptions = CompletionOptions()
)
