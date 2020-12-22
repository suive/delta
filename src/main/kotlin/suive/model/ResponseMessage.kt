package suive.model

sealed class ResponseMessage(
    open val id: String // TODO this may be an integer per spec.
) : Message() {
    data class Success<R : Result>(
        override val id: String,
        val result: R?
    ) : ResponseMessage(id)

    data class Error(
        override val id: String,
        val error: ResponseError
    ) : ResponseMessage(id)
}
