package suive.kotlinls.model

data class InitializeParams(
    val processId: Int?,
    val rootUri: String?,
//    val capabilities: ClientCapabilities,
): Params