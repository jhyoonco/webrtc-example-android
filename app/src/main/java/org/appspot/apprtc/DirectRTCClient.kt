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
import org.appspot.apprtc.AppRTCClient.*
import org.appspot.apprtc.TCPChannelClient.TCPChannelEvents
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * Implementation of AppRTCClient that uses direct TCP connection as the signaling channel.
 * This eliminates the need for an external server. This class does not support loopback
 * connections.
 */
class DirectRTCClient(private val events: SignalingEvents) : AppRTCClient, TCPChannelEvents {
    private val executor: ExecutorService
    private var tcpClient: TCPChannelClient? = null
    private var connectionParameters: RoomConnectionParameters? = null

    private enum class ConnectionState {
        NEW, CONNECTED, CLOSED, ERROR
    }

    // All alterations of the room state should be done from inside the looper thread.
    private var roomState: ConnectionState

    /**
     * Connects to the room, roomId in connectionsParameters is required. roomId must be a valid
     * IP address matching IP_PATTERN.
     */
    override fun connectToRoom(connectionParameters: RoomConnectionParameters?) {
        this.connectionParameters = connectionParameters
        if (connectionParameters?.loopback == true) {
            reportError("Loopback connections aren't supported by DirectRTCClient.")
        }
        executor.execute { connectToRoomInternal() }
    }

    override fun disconnectFromRoom() {
        executor.execute { disconnectFromRoomInternal() }
    }

    /**
     * Connects to the room.
     *
     * Runs on the looper thread.
     */
    private fun connectToRoomInternal() {
        roomState = ConnectionState.NEW
        val endpoint = connectionParameters!!.roomId
        val matcher = IP_PATTERN.matcher(endpoint)
        if (!matcher.matches()) {
            reportError("roomId must match IP_PATTERN for DirectRTCClient.")
            return
        }
        val ip = matcher.group(1)
        val portStr = matcher.group(matcher.groupCount())
        val port: Int
        port = if (portStr != null) {
            try {
                portStr.toInt()
            } catch (e: NumberFormatException) {
                reportError("Invalid port number: $portStr")
                return
            }
        } else {
            DEFAULT_PORT
        }
        tcpClient = TCPChannelClient(executor, this, ip, port)
    }

    /**
     * Disconnects from the room.
     *
     * Runs on the looper thread.
     */
    private fun disconnectFromRoomInternal() {
        roomState = ConnectionState.CLOSED
        tcpClient?.disconnect()
        tcpClient = null
        executor.shutdown()
    }

    override fun sendOfferSdp(sdp: SessionDescription?) {
        executor.execute(Runnable {
            if (roomState != ConnectionState.CONNECTED) {
                reportError("Sending offer SDP in non connected state.")
                return@Runnable
            }
            val json = JSONObject()
            jsonPut(json, "sdp", sdp?.description ?: "")
            jsonPut(json, "type", "offer")
            sendMessage(json.toString())
        })
    }

    override fun sendAnswerSdp(sdp: SessionDescription?) {
        executor.execute {
            val json = JSONObject()
            jsonPut(json, "sdp", sdp?.description ?: "")
            jsonPut(json, "type", "answer")
            sendMessage(json.toString())
        }
    }

    override fun sendLocalIceCandidate(candidate: IceCandidate) {
        executor.execute(Runnable {
            val json = JSONObject()
            jsonPut(json, "type", "candidate")
            jsonPut(json, "label", candidate.sdpMLineIndex)
            jsonPut(json, "id", candidate.sdpMid)
            jsonPut(json, "candidate", candidate.sdp)
            if (roomState != ConnectionState.CONNECTED) {
                reportError("Sending ICE candidate in non connected state.")
                return@Runnable
            }
            sendMessage(json.toString())
        })
    }

    /** Send removed Ice candidates to the other participant.  */
    override fun sendLocalIceCandidateRemovals(candidates: Array<IceCandidate>) {
        executor.execute(Runnable {
            val json = JSONObject()
            jsonPut(json, "type", "remove-candidates")
            val jsonArray = JSONArray()
            for (candidate in candidates) {
                candidate.let { jsonArray.put(toJsonCandidate(it)) }
            }
            jsonPut(json, "candidates", jsonArray)
            if (roomState != ConnectionState.CONNECTED) {
                reportError("Sending ICE candidate removals in non connected state.")
                return@Runnable
            }
            sendMessage(json.toString())
        })
    }
    // -------------------------------------------------------------------
    // TCPChannelClient event handlers
    /**
     * If the client is the server side, this will trigger onConnectedToRoom.
     */
    override fun onTCPConnected(isServer: Boolean) {
        if (isServer) {
            roomState = ConnectionState.CONNECTED
            val parameters = SignalingParameters( // Ice servers are not needed for direct connections.
                    ArrayList(),
                    isServer,  // Server side acts as the initiator on direct connections.
                    null,  // clientId
                    null,  // wssUrl
                    null,  // wwsPostUrl
                    null,  // offerSdp
                    null // iceCandidates
            )
            events.onConnectedToRoom(parameters)
        }
    }

    override fun onTCPMessage(msg: String?) {
        try {
            val json = JSONObject(msg)
            when (val type = json.optString("type")) {
                "candidate" -> {
                    events.onRemoteIceCandidate(toJavaCandidate(json))
                }
                "remove-candidates" -> {
                    val candidateArray = json.getJSONArray("candidates")
//                val candidates = arrayOfNulls<IceCandidate>(candidateArray.length())
                    val candidates = arrayOf<IceCandidate>()
                    for (i in 0 until candidateArray.length()) {
                        candidates[i] = toJavaCandidate(candidateArray.getJSONObject(i))
                    }
                    events.onRemoteIceCandidatesRemoved(candidates)
                }
                "answer" -> {
                    val sdp = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp"))
                    events.onRemoteDescription(sdp)
                }
                "offer" -> {
                    val sdp = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp"))
                    val parameters = SignalingParameters( // Ice servers are not needed for direct connections.
                            ArrayList(),
                            false,  // This code will only be run on the client side. So, we are not the initiator.
                            null,  // clientId
                            null,  // wssUrl
                            null,  // wssPostUrl
                            sdp,  // offerSdp
                            null // iceCandidates
                    )
                    roomState = ConnectionState.CONNECTED
                    events.onConnectedToRoom(parameters)
                }
                else -> {
                    reportError("Unexpected TCP message: $msg")
                }
            }
        } catch (e: JSONException) {
            reportError("TCP message JSON parsing error: $e")
        }
    }

    override fun onTCPError(description: String?) {
        reportError("TCP connection error: $description")
    }

    override fun onTCPClose() {
        events.onChannelClose()
    }

    // --------------------------------------------------------------------
    // Helper functions.
    private fun reportError(errorMessage: String) {
        Log.e(TAG, errorMessage)
        executor.execute {
            if (roomState != ConnectionState.ERROR) {
                roomState = ConnectionState.ERROR
                events.onChannelError(errorMessage)
            }
        }
    }

    private fun sendMessage(message: String) {
        executor.execute { tcpClient!!.send(message) }
    }

    companion object {
        private const val TAG = "DirectRTCClient"
        private const val DEFAULT_PORT = 8888

        // Regex pattern used for checking if room id looks like an IP.
        @JvmField
        val IP_PATTERN = Pattern.compile("(" // IPv4
                + "((\\d+\\.){3}\\d+)|" // IPv6
                + "\\[((([0-9a-fA-F]{1,4}:)*[0-9a-fA-F]{1,4})?::"
                + "(([0-9a-fA-F]{1,4}:)*[0-9a-fA-F]{1,4})?)\\]|"
                + "\\[(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4})\\]|" // IPv6 without []
                + "((([0-9a-fA-F]{1,4}:)*[0-9a-fA-F]{1,4})?::(([0-9a-fA-F]{1,4}:)*[0-9a-fA-F]{1,4})?)|"
                + "(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4})|" // Literals
                + "localhost"
                + ")" // Optional port number
                + "(:(\\d+))?")

        // Put a |key|->|value| mapping in |json|.
        private fun jsonPut(json: JSONObject, key: String, value: Any) {
            try {
                json.put(key, value)
            } catch (e: JSONException) {
                throw RuntimeException(e)
            }
        }

        // Converts a Java candidate to a JSONObject.
        private fun toJsonCandidate(candidate: IceCandidate): JSONObject {
            val json = JSONObject()
            jsonPut(json, "label", candidate.sdpMLineIndex)
            jsonPut(json, "id", candidate.sdpMid)
            jsonPut(json, "candidate", candidate.sdp)
            return json
        }

        // Converts a JSON candidate to a Java object.
        @Throws(JSONException::class)
        private fun toJavaCandidate(json: JSONObject): IceCandidate {
            return IceCandidate(
                    json.getString("id"), json.getInt("label"), json.getString("candidate"))
        }
    }

    init {
        executor = Executors.newSingleThreadExecutor()
        roomState = ConnectionState.NEW
    }
}