package org.qortal.network.message;

import org.qortal.network.PeerAddress;
import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class PeerRelayDataMessage extends Message {

	private final byte[] hash;
	private final PeerAddress peerAddress;

	public PeerRelayDataMessage(String relayHost, byte[] hash) {
		super(MessageType.PEER_RELAY_DATA);

		String fullHost = relayHost + ":12394";  // @ToDo: Fix this
		byte[] relayPeerHost = fullHost.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(hash.length + relayPeerHost.length);

		try {
			bytes.write(hash);
			bytes.write(relayPeerHost);

		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}
		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	
		this.hash = hash;
		this.peerAddress = PeerAddress.fromString(fullHost);

	}

	public PeerAddress getPeerAddress() {
		return this.peerAddress;
	}

	public byte[] getHash() {
		return this.hash;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) {
		byte[] hash = new byte[Transformer.SHA256_LENGTH];
		bytes.get(hash);

		byte[] host = new byte[bytes.remaining()];
		bytes.get(host);

		return new PeerRelayDataMessage(Arrays.toString(host), hash);
	}

}
