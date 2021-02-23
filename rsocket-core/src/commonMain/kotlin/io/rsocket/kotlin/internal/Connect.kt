/*
 * Copyright 2015-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.kotlin.internal

import io.ktor.utils.io.core.*
import io.ktor.utils.io.core.internal.*
import io.rsocket.kotlin.*
import io.rsocket.kotlin.core.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.handler.*
import kotlinx.coroutines.*
import kotlin.native.concurrent.*

@TransportApi
internal suspend fun Connection.connect(
    isServer: Boolean,
    interceptors: Interceptors,
    connectionConfig: ConnectionConfig,
    acceptor: ConnectionAcceptor
): RSocket = Connect(this, isServer, interceptors, connectionConfig, acceptor).start()

// TODO will be replaced by configurable requestScope context
//  for now needed to mute errors on K/N
@SharedImmutable
private val exceptionHandler = CoroutineExceptionHandler { _, _ -> }

@TransportApi
private class Connect(
    private val connection: Connection,
    private val isServer: Boolean,
    private val interceptors: Interceptors,
    private val connectionConfig: ConnectionConfig,
    private val acceptor: ConnectionAcceptor
) {
    private val keepAliveHandler = KeepAliveHandler(connectionConfig.keepAlive)
    private val priorityConnection = PriorityConnection()
    private val streamStorage = StreamStorage(StreamId(isServer))
    private val requestScope = CoroutineScope(SupervisorJob(connection.job) + exceptionHandler)
    private val connectionScope = CoroutineScope(connection.job + Dispatchers.Unconfined)

    init {
        connection.job.invokeOnCompletion {
            priorityConnection.close(it)
            streamStorage.cleanup(it)
        }
    }

    suspend fun start(): RSocket {
        val requester = createRequester()
        val responderDeferred = async { createResponder(requester) }
        // start keepalive ticks
        launchWhileActive {
            if (keepAliveHandler.tick(connection.job)) priorityConnection.sendKeepAlive(true, 0, ByteReadPacket.Empty)
        }

        // start sending frames to connection
        launchWhileActive {
            priorityConnection.receive().closeOnError { connection.sendFrame(it) }
        }

        //TODO!!!
        launch {
            // start frame handling for zero stream and requester side
            // after this, requester RSocket is ready to do requests and accept responses
            // that's needed to be able to use requester RSocket inside ConnectionAcceptor
            // when connection will be accepted or if we will receive request from responder, we should await
            startFrameHandling({ responderDeferred.isActive }, { responderDeferred.await() })
            val responder = responderDeferred.await()
            startFrameHandling({ true }, { responder })
        }
        responderDeferred.await()
        return requester
    }

    private fun createRequester(): RSocket {
        val requester = RSocketRequester(connection.job, requestScope, priorityConnection, streamStorage)
        return interceptors.wrapRequester(requester)
    }

    private suspend fun createResponder(requester: RSocket): RSocketResponder {
        val rSocketResponder = with(interceptors.wrapAcceptor(acceptor)) {
            ConnectionAcceptorContext(connectionConfig, requester).accept()
        }
        val requestHandler = interceptors.wrapResponder(rSocketResponder)

        // link completing of connection and requestHandler
        connection.job.invokeOnCompletion(requestHandler.job::completeWith)
        requestHandler.job.invokeOnCompletion(connection.job::completeWith)

        return RSocketResponder(priorityConnection, requestScope, requestHandler)
    }

    private suspend inline fun startFrameHandling(
        isActive: () -> Boolean,
        getResponder: () -> RSocketResponder,
    ) {
        while (connection.isActive && isActive()) connection.receiveFrame().closeOnError { frame ->
            when (frame.streamId) {
                0    -> when (frame) {
                    is MetadataPushFrame -> getResponder().handleMetadataPush(frame.metadata)
                    is ErrorFrame        -> connection.job.completeExceptionally(frame.throwable)
                    is KeepAliveFrame    -> {
                        keepAliveHandler.mark()
                        if (frame.respond) priorityConnection.sendKeepAlive(false, 0, frame.data) else Unit
                    }
                    is LeaseFrame        -> frame.release().also { error("lease isn't implemented") }
                    else                 -> frame.release()
                }
                else -> when (isServer.xor(frame.streamId % 2 != 0)) {
                    true  -> streamStorage.handleRequesterFrame(frame)
                    false -> {
                        val responder = getResponder()
                        streamStorage.handleResponderFrame(frame) { handleRequestFrame(it, responder) }
                    }
                }
            }
        }
    }

    private fun handleRequestFrame(requestFrame: RequestFrame, responder: RSocketResponder): ResponderFrameHandler {
        val id = requestFrame.streamId
        val initialRequest = requestFrame.initialRequest
        val payload = requestFrame.payload

        @OptIn(DangerousInternalIoApi::class) //because of pool
        val handler = when (requestFrame.type) {
            FrameType.RequestFnF      -> ResponderFireAndForgetFrameHandler(payload, id, streamStorage, responder)
            FrameType.RequestResponse -> ResponderRequestResponseFrameHandler(payload, id, streamStorage, responder)
            FrameType.RequestStream   -> ResponderRequestStreamFrameHandler(payload, id, streamStorage, responder, initialRequest)
            FrameType.RequestChannel  -> ResponderRequestChannelFrameHandler(payload, id, streamStorage, responder, initialRequest)
            else                      -> error("Wrong request frame type") // should never happen
        }
        return handler
    }


    //helper functions to start coroutines in specific scope and loop while connection is active

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> async(block: suspend CoroutineScope.() -> T): Deferred<T> {
        return connectionScope.async(start = CoroutineStart.UNDISPATCHED, block = block)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun launch(block: suspend CoroutineScope.() -> Unit) {
        connectionScope.launch(start = CoroutineStart.UNDISPATCHED, block = block)
    }

    private inline fun launchWhileActive(crossinline block: suspend () -> Unit) {
        launch {
            while (connection.isActive) block()
        }
    }
}

private fun CompletableJob.completeWith(error: Throwable?) {
    when (error) {
        null                     -> complete()
        is CancellationException -> cancel(error)
        else                     -> completeExceptionally(error)
    }
}
