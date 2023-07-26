/*
 * Copyright 2014-2023 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.curl.internal.websocket

import io.ktor.client.engine.curl.internal.*
import io.ktor.utils.io.pool.*
import io.ktor.websocket.*
import kotlinx.atomicfu.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import libcurl.*
import platform.posix.*
import kotlin.coroutines.*

internal class CurlWebSocketSession(
    private val handle: EasyHandle,
    callContext: CoroutineContext
) : WebSocketSession {
    private val closed = atomic(false)
    private val socketJob = Job(callContext[Job])

    private val _incoming = Channel<Frame>(Channel.UNLIMITED)
    private val _outgoing = Channel<Frame>(Channel.UNLIMITED)

    override val coroutineContext: CoroutineContext = callContext + socketJob
    override var masking: Boolean
        get() = true
        set(value) {}
    override var maxFrameSize: Long
        get() = Long.MAX_VALUE
        set(value) {}
    override val incoming: ReceiveChannel<Frame>
        get() = _incoming
    override val outgoing: SendChannel<Frame>
        get() = _outgoing
    override val extensions: List<WebSocketExtension<*>>
        get() = emptyList()

    init {
        // Start receiving frames
        launch {
            ByteArrayPool.useInstance { readBuffer ->
                while (!closed.value) {
                    readBuffer.usePinned { dst ->
                        receiveNextFrame(dst)
                    }
                }
            }
        }

        // Start sending frames
        launch {
            while (!closed.value) {
                sendNextFrame()
            }
        }
    }

    override suspend fun flush() = Unit

    @Deprecated("Use cancel() instead.", replaceWith = ReplaceWith("cancel()", "kotlinx.coroutines.cancel"))
    override fun terminate() = cancel()

    private fun receiveNextFrame(buffer: Pinned<ByteArray>) = memScoped {
        val recv = alloc<size_tVar>()
        val meta = allocPointerTo<curl_ws_frame>()
        curl_ws_recv(handle, buffer.addressOf(0), buffer.get().size.convert(), recv.ptr, meta.ptr).verify()

        onFrame(buffer.get(), meta.pointed!!)
    }

    private fun onFrame(readBuffer: ByteArray, meta: curl_ws_frame) {
        val size = meta.bytesleft
        val data = readBuffer.copyOf(size.toInt())
        val frame = when {
            meta.isOfType(CURLWS_BINARY) -> Frame.Binary(false, data)

            meta.isOfType(CURLWS_TEXT) -> Frame.Text(false, data)

            meta.isOfType(CURLWS_CLOSE) -> Frame.Close(data)

            else -> error("Received unsupported frame with flags: ${meta.flags}")
        }
        _incoming.trySend(frame)
    }

    private suspend fun sendNextFrame() {
        val frame = _outgoing.receive()
        val type = frame.curlType

        frame.data.usePinned {
            sendFrame(type, it)
        }
    }

    private fun sendFrame(type: UInt, payload: Pinned<ByteArray>) = memScoped {
        val sent = alloc<size_tVar>()

        curl_ws_send(handle, payload.addressOf(0), payload.get().size.convert(), sent.ptr, 0, type).verify()
    }

}

@Suppress("NO_ELSE_IN_WHEN")
private val Frame.curlType: UInt
    get() = when (this) {
        is Frame.Binary -> CURLWS_BINARY.convert()
        is Frame.Close -> CURLWS_CLOSE.convert()
        is Frame.Ping -> CURLWS_PING.convert()
        is Frame.Pong -> CURLWS_PONG.convert()
        is Frame.Text -> CURLWS_TEXT.convert()
    }

private fun curl_ws_frame.isOfType(type: Int) =
    (flags and type).toByte().toBoolean()
