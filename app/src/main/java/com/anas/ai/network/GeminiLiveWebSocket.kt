package com.anas.ai.network


import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.anas.ai.data.model.*
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


class GeminiLiveWebSocket(
    private val apiKey: String,
    private val model: String,
    private val voiceName: String,
    private val systemPrompt: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectionStateChanged(status: String)
        fun onAudioDataReceived(pcmData: ByteArray)
        fun onAssistantTextReceived(textChunk: String)
        fun onInterrupted()
        fun onTurnCompleted()
        fun onError(message: String)
    }


    companion object {
        private const val TAG = "GeminiLiveWebSocket"
    }


    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(GeminiConstants.KEEPALIVE_INTERVAL_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()


    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isReady = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private var reconnectAttempts = 0
    private val isManuallyStopped = AtomicBoolean(false)
    private var coroutineScope: CoroutineScope? = null


    private var sessionRenewalJob: Job? = null
    private var reconnectJob: Job? = null


    fun connect(scope: CoroutineScope) {
        coroutineScope = scope
        isManuallyStopped.set(false)
        initiateConnection()
    }


    private fun initiateConnection() {
        if (apiKey.isBlank()) {
            listener.onError("Gemini API Key is missing. Please configure it in Settings.")
            return
        }


        listener.onConnectionStateChanged("CONNECTING...")


        try {
            val url = "${GeminiConstants.WS_BASE_URL}?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .build()


            val gen = generation.incrementAndGet()
            isReady.set(false)
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                private fun stale() = gen != generation.get()

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (stale()) return
                    Log.d(TAG, "WebSocket connected to Gemini Live")
                    isConnected.set(true)
                    // Wait for setupComplete before reporting LIVE or sending audio
                    sendSetupMessage(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (stale()) return
                    handleIncomingMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (stale()) return
                    handleIncomingMessage(bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code / $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (stale()) return
                    Log.d(TAG, "WebSocket closed: $code / $reason")
                    handleClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (stale()) return
                    Log.e(TAG, "WebSocket failure: ${t.message}", t)
                    handleFailure(t, response)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating WebSocket connection: ${e.message}", e)
            listener.onError("Connection failed: ${e.message}")
            listener.onConnectionStateChanged("OFFLINE")
        }
    }


    private fun handleClosed(code: Int, reason: String) {
        isConnected.set(false)
        isReady.set(false)
        stopTimers()
        if (isManuallyStopped.get()) {
            listener.onConnectionStateChanged("OFFLINE")
            return
        }
        val detail = "Server closed connection (code $code): " +
            reason.ifBlank { "no reason given" }
        listener.onError(detail)
        if (code == 1003 || code == 1007 || code == 1008) {
            // Bad request, bad key or unsupported model: retrying will not help
            listener.onConnectionStateChanged("OFFLINE")
        } else {
            scheduleReconnect()
        }
    }

    private fun handleFailure(t: Throwable, response: Response?) {
        isConnected.set(false)
        isReady.set(false)
        stopTimers()
        if (isManuallyStopped.get()) {
            listener.onConnectionStateChanged("OFFLINE")
            return
        }
        val httpCode = response?.code
        val msg = buildString {
            append("Connection failed")
            if (httpCode != null) append(" (HTTP $httpCode)")
            append(": ${t.message ?: "unknown error"}")
        }
        if (httpCode == 400 || httpCode == 401 || httpCode == 403 || httpCode == 404) {
            listener.onError("$msg. Check your API key in Settings.")
            listener.onConnectionStateChanged("OFFLINE")
        } else {
            listener.onError(msg)
            scheduleReconnect()
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val setupPayload = SetupPayload(
            model = model,
            generationConfig = GenerationConfigPayload(
                responseModalities = listOf("AUDIO"),
                speechConfig = SpeechConfigPayload(
                    voiceConfig = VoiceConfigPayload(
                        prebuiltVoiceConfig = PrebuiltVoiceConfigPayload(voiceName = voiceName)
                    )
                )
            ),
            systemInstruction = ContentPayload(
                parts = listOf(PartTextPayload(text = systemPrompt))
            )
        )


        val bidiSetup = GeminiBidiSetup(setup = setupPayload)
        val json = gson.toJson(bidiSetup)
        Log.d(TAG, "Sending BidiSetup payload to Gemini Live")
        ws.send(json)
    }


    private fun handleIncomingMessage(jsonText: String) {
        try {
            val jsonObject = JsonParser.parseString(jsonText).asJsonObject

            if (jsonObject.has("setupComplete")) {
                Log.d(TAG, "Setup complete, session is live")
                isReady.set(true)
                reconnectAttempts = 0
                listener.onConnectionStateChanged("LIVE")
                startSessionRenewal()
                return
            }


            if (jsonObject.has("serverContent")) {
                val serverContent = jsonObject.getAsJsonObject("serverContent")


                // Interrupted (Barge-in)
                if (serverContent.has("interrupted") && serverContent.get("interrupted").asBoolean) {
                    Log.d(TAG, "Server signaled turn interrupted")
                    listener.onInterrupted()
                }


                // Model turn parts (Audio + Text)
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getAsJsonObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getAsJsonArray("parts")
                        for (i in 0 until parts.size()) {
                            val part = parts.get(i).asJsonObject


                            // Text transcript
                            if (part.has("text")) {
                                val text = part.get("text").asString
                                if (!text.isNullOrBlank()) {
                                    listener.onAssistantTextReceived(text)
                                }
                            }


                            // Inline audio (24kHz Mono PCM Base64)
                            if (part.has("inlineData")) {
                                val inlineData = part.getAsJsonObject("inlineData")
                                if (inlineData.has("data")) {
                                    val base64Data = inlineData.get("data").asString
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    listener.onAudioDataReceived(pcmBytes)
                                }
                            }
                        }
                    }
                }


                // Turn completed
                if (serverContent.has("turnComplete") && serverContent.get("turnComplete").asBoolean) {
                    Log.d(TAG, "Server signaled turnComplete")
                    listener.onTurnCompleted()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming server JSON: ${e.message}", e)
        }
    }


    fun sendAudioChunk(pcm16k: ByteArray) {
        if (!isConnected.get() || !isReady.get() || pcm16k.isEmpty()) return


        try {
            val base64Audio = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
            val realtimeInput = GeminiRealtimeInput(
                realtimeInput = RealtimeAudioInput(
                    audio = RealtimeAudioPayload(
                        mimeType = "audio/pcm;rate=16000",
                        data = base64Audio
                    )
                )
            )
            val json = gson.toJson(realtimeInput)
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk: ${e.message}")
        }
    }


    private fun startSessionRenewal() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(GeminiConstants.SESSION_RENEWAL_MS)
            if (isActive && !isManuallyStopped.get()) {
                Log.d(TAG, "9-minute session threshold reached. Seamlessly renewing session...")
                val old = webSocket
                initiateConnection()
                old?.close(1000, "Session renewal")
            }
        }
    }


    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectAttempts++
        if (reconnectAttempts > 5) {
            listener.onError("Could not reconnect after 5 attempts. Turn the power button off and on to try again.")
            listener.onConnectionStateChanged("OFFLINE")
            return
        }
        listener.onConnectionStateChanged("RECONNECTING...")
        val wait = minOf(3000L * reconnectAttempts, 10000L)
        reconnectJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(wait)
            if (!isManuallyStopped.get()) {
                Log.d(TAG, "Attempting auto-reconnect ($reconnectAttempts)...")
                initiateConnection()
            }
        }
    }


    private fun stopTimers() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = null
    }


    fun disconnect() {
        isManuallyStopped.set(true)
        generation.incrementAndGet()
        isReady.set(false)
        stopTimers()
        reconnectJob?.cancel()
        reconnectJob = null
        isConnected.set(false)


        try {
            webSocket?.close(1000, "Client stopped session")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing webSocket: ${e.message}")
        } finally {
            webSocket = null
        }
        listener.onConnectionStateChanged("OFFLINE")
    }
}
