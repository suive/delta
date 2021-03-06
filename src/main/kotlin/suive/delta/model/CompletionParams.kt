package suive.delta.model

data class CompletionParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    val partialResultToken: String? = null
)
