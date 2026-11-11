package com.mist.connect

import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket

/** Only MCP can fall back. The voice bootstrap at 5001 is deliberately untouched. */
internal object McpEndpoint {
    fun bind(factory: (Int) -> ServerSocket = { port ->
        ServerSocket(port, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    }): ServerSocket {
        val socket = try { factory(5000) } catch (_: BindException) { factory(0) }
        try {
            require(socket.isBound && !socket.isClosed && socket.localPort in 1..65535 && socket.localPort != 5001)
            require(socket.inetAddress.isLoopbackAddress)
            return socket
        } catch (failure: Throwable) {
            try { socket.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
    fun url(socket: ServerSocket): String {
        check(socket.isBound && !socket.isClosed && socket.localPort in 1..65535 && socket.localPort != 5001)
        return "http://127.0.0.1:${socket.localPort}/mcp"
    }
}
