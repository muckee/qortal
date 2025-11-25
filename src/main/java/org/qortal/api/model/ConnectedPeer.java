package org.qortal.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortal.controller.Controller;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.network.PeerData;
import org.qortal.network.Handshake;
import org.qortal.network.Peer;
import org.qortal.network.helper.PeerCapabilities;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@XmlAccessorType(XmlAccessType.FIELD)
public class ConnectedPeer {

    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    public Direction direction;
    public Handshake handshakeStatus;
    public Long lastPing;
    public Long connectedWhen;
    public Long peersConnectedWhen;

    public String address;
    public String version;

    public String nodeId;

    public Integer lastHeight;
    @Schema(example = "base58")
    public byte[] lastBlockSignature;
    public Long lastBlockTimestamp;
    public UUID connectionId;

    @Schema(description = "Capabilities as an array of single-entry key:value maps")
    @XmlJavaTypeAdapter(MapAdapter.class)
    public Map capabilities;

    //public PeerCapabilities capabilities;
    //public Map<String, Object>[] capabilities;
    //public List<Map<String, Object>> capabilities;

    public String age;
    public Boolean isTooDivergent;

    // Needed for DeSerialization
    public ConnectedPeer() {
    }

    public ConnectedPeer(Peer peer) {
        this.direction = peer.isOutbound() ? Direction.OUTBOUND : Direction.INBOUND;
        this.handshakeStatus = peer.getHandshakeStatus();
        this.lastPing = peer.getLastPing();

        PeerData peerData = peer.getPeerData();
        this.connectedWhen = peer.getConnectionTimestamp();
        this.peersConnectedWhen = peer.getPeersConnectionTimestamp();

        this.address = peerData.getAddress().toString();

        this.version = peer.getPeersVersionString();
        this.nodeId = peer.getPeersNodeId();
        this.connectionId = peer.getPeerConnectionId();

        PeerCapabilities peerCapabilities = peer.getPeersCapabilities();
        if (peerCapabilities.size() > 0) {
            // Assuming peer.getPeersCapabilities() returns a PeerCapabilities object
            // and PeerCapabilities.getPeerCapabilities() returns the internal Map<String, Object>
            //this.capabilities = peerCapabilities.getPeerCapabilities();
            //capabilities = peer.getPeersCapabilities();
            this.capabilities = peerCapabilities.getPeerCapabilities();
        }

        if (peer.getConnectionEstablishedTime() > 0) {
            long age = (System.currentTimeMillis() - peer.getConnectionEstablishedTime());
            long minutes = TimeUnit.MILLISECONDS.toMinutes(age);
            long seconds = TimeUnit.MILLISECONDS.toSeconds(age) - TimeUnit.MINUTES.toSeconds(minutes);
            this.age = String.format("%dm %ds", minutes, seconds);
        } else {
            this.age = "connecting...";
        }

        if (peer.getPeerType() == Peer.NETWORK) {
            BlockSummaryData peerChainTipData = peer.getChainTipData();
            if (peerChainTipData != null) {
                this.lastHeight = peerChainTipData.getHeight();
                this.lastBlockSignature = peerChainTipData.getSignature();
                this.lastBlockTimestamp = peerChainTipData.getTimestamp();
            }
        }

        // Only include isTooDivergent decision if we've had the opportunity to request block summaries this peer
        if (peer.getLastTooDivergentTime() != null) {
            this.isTooDivergent = Controller.wasRecentlyTooDivergent.test(peer);
        }
    }

}
