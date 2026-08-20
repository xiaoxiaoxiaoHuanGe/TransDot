package com.transdot.transferassistant.lan

import android.content.Context
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription

class AndroidLanPeerFactory(context: Context) : LanPeerFactory {
    private val factory = WebRtcFactoryHolder.get(context.applicationContext)
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    override fun create(iceServers: List<String>, observer: LanPeerObserver): LanPeerConnection {
        require(iceServers.isEmpty()) { "LAN_ICE_SERVERS_NOT_ALLOWED" }
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val native = requireNotNull(factory.createPeerConnection(configuration, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    callbackScope.launch { observer.onFailed("LAN_PEER_OFFLINE") }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                callbackScope.launch {
                    observer.onIceCandidate(LanIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) {
                callbackScope.launch { observer.onDataChannel(WebRtcDataChannel(channel, callbackScope)) }
            }
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit
        }))
        return WebRtcPeerConnection(native) { block -> callbackScope.launch { block() } }
    }
}

private object WebRtcFactoryHolder {
    @Volatile private var instance: PeerConnectionFactory? = null

    fun get(context: Context): PeerConnectionFactory = instance ?: synchronized(this) {
        instance ?: run {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
            )
            PeerConnectionFactory.builder().createPeerConnectionFactory().also { instance = it }
        }
    }
}

private class WebRtcPeerConnection(
    private val native: PeerConnection,
    private val dispatch: (() -> Unit) -> Unit,
) : LanPeerConnection {
    private val closed = AtomicBoolean(false)

    override fun setRemoteOffer(sdp: String, callback: LanSdpCallback) {
        native.setRemoteDescription(SetSdpObserver(callback, sdp, dispatch), SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    override fun createAnswer(callback: LanSdpCallback) {
        native.createAnswer(CreateSdpObserver(callback, dispatch), MediaConstraints())
    }

    override fun setLocalAnswer(sdp: String, callback: LanSdpCallback) {
        native.setLocalDescription(SetSdpObserver(callback, sdp, dispatch), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override fun addIceCandidate(candidate: LanIceCandidate): Boolean = native.addIceCandidate(
        IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate),
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        native.close()
        native.dispose()
    }
}

private class CreateSdpObserver(
    private val callback: LanSdpCallback,
    private val dispatch: (() -> Unit) -> Unit,
) : org.webrtc.SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = dispatch { callback.onSuccess(description.description) }
    override fun onCreateFailure(error: String) = dispatch { callback.onFailure("LAN_NEGOTIATION_FAILED") }
    override fun onSetSuccess() = Unit
    override fun onSetFailure(error: String) = dispatch { callback.onFailure("LAN_NEGOTIATION_FAILED") }
}

private class SetSdpObserver(
    private val callback: LanSdpCallback,
    private val sdp: String,
    private val dispatch: (() -> Unit) -> Unit,
) : org.webrtc.SdpObserver {
    override fun onSetSuccess() = dispatch { callback.onSuccess(sdp) }
    override fun onSetFailure(error: String) = dispatch { callback.onFailure("LAN_NEGOTIATION_FAILED") }
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onCreateFailure(error: String) = dispatch { callback.onFailure("LAN_NEGOTIATION_FAILED") }
}

private class WebRtcDataChannel(
    private val native: DataChannel,
    private val callbackScope: CoroutineScope,
) : LanDataChannel {
    private val closed = AtomicBoolean(false)
    override val isOpen: Boolean get() = native.state() == DataChannel.State.OPEN
    override val isOrdered: Boolean = true
    override val bufferedAmount: Long get() = native.bufferedAmount()

    override fun setObserver(observer: LanDataChannelObserver) {
        native.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {
                callbackScope.launch { observer.onBufferedAmountChange(previousAmount) }
            }
            override fun onStateChange() {
                callbackScope.launch {
                    when (native.state()) {
                        DataChannel.State.OPEN -> observer.onOpen()
                        DataChannel.State.CLOSING, DataChannel.State.CLOSED -> observer.onClosed()
                        else -> Unit
                    }
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = buffer.data.duplicate()
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                callbackScope.launch {
                    if (buffer.binary) observer.onBinary(bytes)
                    else observer.onText(String(bytes, StandardCharsets.UTF_8))
                }
            }
        })
    }

    override fun sendText(text: String): Boolean =
        native.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8)), false))

    override fun sendBinary(bytes: ByteArray): Boolean =
        native.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        native.unregisterObserver()
        native.close()
        native.dispose()
    }
}
