package io.github.alexarchambault.jsonrpc

class JsonRpcError(val code: Long, val message: String) extends Throwable(message)
