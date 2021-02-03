/*
 *  Copyright 2016 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package org.appspot.apprtc

import android.util.Log
import org.webrtc.ThreadUtils.ThreadChecker
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.concurrent.ExecutorService

/**
 * Replacement for WebSocketChannelClient for direct communication between two IP addresses. Handles
 * the signaling between the two clients using a TCP connection.
 *
 *
 * All public methods should be called from a looper executor thread
 * passed in a constructor, otherwise exception will be thrown.
 * All events are dispatched on the same thread.
 */
class TCPChannelClient(
        private val executor: ExecutorService, eventListener: TCPChannelEvents, ip: String?, port: Int) {
    private val executorThreadCheck: ThreadChecker
    private val eventListener: TCPChannelEvents
    private var socket: TCPSocket? = null

    /**
     * Callback interface for messages delivered on TCP Connection. All callbacks are invoked from the
     * looper executor thread.
     */
    interface TCPChannelEvents {
        fun onTCPConnected(server: Boolean)
        fun onTCPMessage(message: String?)
        fun onTCPError(description: String?)
        fun onTCPClose()
    }

    /**
     * Disconnects the client if not already disconnected. This will fire the onTCPClose event.
     */
    fun disconnect() {
        executorThreadCheck.checkIsOnValidThread()
        socket!!.disconnect()
    }

    /**
     * Sends a message on the socket.
     *
     * @param message Message to be sent.
     */
    fun send(message: String) {
        executorThreadCheck.checkIsOnValidThread()
        socket!!.send(message)
    }

    /**
     * Helper method for firing onTCPError events. Calls onTCPError on the executor thread.
     */
    private fun reportError(message: String) {
        Log.e(TAG, "TCP Error: $message")
        executor.execute { eventListener.onTCPError(message) }
    }

    /**
     * Base class for server and client sockets. Contains a listening thread that will call
     * eventListener.onTCPMessage on new messages.
     */
    private abstract inner class TCPSocket internal constructor() : Thread() {
        // Lock for editing out and rawSocket
        protected val rawSocketLock: Any
        private var out: PrintWriter? = null
        private var rawSocket: Socket? = null

        /**
         * Connect to the peer, potentially a slow operation.
         *
         * @return Socket connection, null if connection failed.
         */
        abstract fun connect(): Socket?

        /** Returns true if sockets is a server rawSocket.  */
        abstract val isServer: Boolean

        /**
         * The listening thread.
         */
        override fun run() {
            Log.d(TAG, "Listening thread started...")

            // Receive connection to temporary variable first, so we don't block.
            val tempSocket = connect()
            var `in`: BufferedReader
            Log.d(TAG, "TCP connection established.")
            synchronized(rawSocketLock) {
                if (rawSocket != null) {
                    Log.e(TAG, "Socket already existed and will be replaced.")
                }
                rawSocket = tempSocket

                // Connecting failed, error has already been reported, just exit.
                if (rawSocket == null) {
                    return
                }
                try {
                    out = PrintWriter(
                            OutputStreamWriter(rawSocket!!.getOutputStream(), Charset.forName("UTF-8")), true)
                    `in` = BufferedReader(
                            InputStreamReader(rawSocket!!.getInputStream(), Charset.forName("UTF-8")))
                } catch (e: IOException) {
                    reportError("Failed to open IO on rawSocket: " + e.message)
                    return
                }
            }
            Log.v(TAG, "Execute onTCPConnected")
            executor.execute {
                Log.v(TAG, "Run onTCPConnected")
                eventListener.onTCPConnected(isServer)
            }
            while (true) {
                val message: String?
                message = try {
                    `in`.readLine()
                } catch (e: IOException) {
                    synchronized(rawSocketLock) {
                        // If socket was closed, this is expected.
                        if (rawSocket == null) {
                            return
                        }
                    }
                    reportError("Failed to read from rawSocket: " + e.message)
                    break
                }

                // No data received, rawSocket probably closed.
                if (message == null) {
                    break
                }
                executor.execute {
                    Log.v(TAG, "Receive: $message")
                    eventListener.onTCPMessage(message)
                }
            }
            Log.d(TAG, "Receiving thread exiting...")

            // Close the rawSocket if it is still open.
            disconnect()
        }

        /** Closes the rawSocket if it is still open. Also fires the onTCPClose event.  */
        open fun disconnect() {
            try {
                synchronized(rawSocketLock) {
                    if (rawSocket != null) {
                        rawSocket!!.close()
                        rawSocket = null
                        out = null
                        executor.execute { eventListener.onTCPClose() }
                    }
                }
            } catch (e: IOException) {
                reportError("Failed to close rawSocket: " + e.message)
            }
        }

        /**
         * Sends a message on the socket. Should only be called on the executor thread.
         */
        fun send(message: String) {
            Log.v(TAG, "Send: $message")
            synchronized(rawSocketLock) {
                if (out == null) {
                    reportError("Sending data on closed socket.")
                    return
                }
                out!!.write("""
    $message
    
    """.trimIndent())
                out!!.flush()
            }
        }

        init {
            rawSocketLock = Any()
        }
    }

    private inner class TCPSocketServer(private val address: InetAddress, private val port: Int) : TCPSocket() {
        // Server socket is also guarded by rawSocketLock.
        private var serverSocket: ServerSocket? = null

        /** Opens a listening socket and waits for a connection.  */
        override fun connect(): Socket? {
            Log.d(TAG, "Listening on [" + address.hostAddress + "]:" + Integer.toString(port))
            val tempSocket: ServerSocket
            tempSocket = try {
                ServerSocket(port, 0, address)
            } catch (e: IOException) {
                reportError("Failed to create server socket: " + e.message)
                return null
            }
            synchronized(rawSocketLock) {
                if (serverSocket != null) {
                    Log.e(TAG, "Server rawSocket was already listening and new will be opened.")
                }
                serverSocket = tempSocket
            }
            return try {
                tempSocket.accept()
            } catch (e: IOException) {
                reportError("Failed to receive connection: " + e.message)
                null
            }
        }

        /** Closes the listening socket and calls super.  */
        override fun disconnect() {
            try {
                synchronized(rawSocketLock) {
                    if (serverSocket != null) {
                        serverSocket!!.close()
                        serverSocket = null
                    }
                }
            } catch (e: IOException) {
                reportError("Failed to close server socket: " + e.message)
            }
            super.disconnect()
        }

        override val isServer: Boolean
            get() = true
    }

    private inner class TCPSocketClient(private val address: InetAddress, private val port: Int) : TCPSocket() {
        /** Connects to the peer.  */
        override fun connect(): Socket? {
            Log.d(TAG, "Connecting to [" + address.hostAddress + "]:" + Integer.toString(port))
            return try {
                Socket(address, port)
            } catch (e: IOException) {
                reportError("Failed to connect: " + e.message)
                null
            }
        }

        override val isServer: Boolean
            get() = false
    }

    companion object {
        private const val TAG = "TCPChannelClient"
    }

    /**
     * Initializes the TCPChannelClient. If IP is a local IP address, starts a listening server on
     * that IP. If not, instead connects to the IP.
     *
     * @param eventListener Listener that will receive events from the client.
     * @param ip            IP address to listen on or connect to.
     * @param port          Port to listen on or connect to.
     */
    init {
        executorThreadCheck = ThreadChecker()
        executorThreadCheck.detachThread()
        this.eventListener = eventListener
        val address: InetAddress
        try {
            address = InetAddress.getByName(ip)

            socket = if (address.isAnyLocalAddress) {
                TCPSocketServer(address, port)
            } else {
                TCPSocketClient(address, port)
            }
            socket?.start()
        } catch (e: UnknownHostException) {
            reportError("Invalid IP address.")
//            return
        }
    }
}