package me.rerere.rikkahub.service

internal class ContextCompactionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
