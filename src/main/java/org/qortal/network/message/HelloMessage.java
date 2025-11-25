package org.qortal.network.message;

import com.google.common.primitives.Longs;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataBuilder;
import org.qortal.controller.Controller;
import org.qortal.network.Peer;
import org.qortal.network.helper.PeerCapabilities;
import org.qortal.transform.TransformationException;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HelloMessage extends Message {

	private long timestamp;
	private String versionString;
	private String senderPeerAddress;
	//private PeerCapabilities capabilities = new PeerCapabilities();
	private int peerType = Peer.NETWORK;

    public HelloMessage(long timestamp, String versionString, String senderPeerAddress) {
		super(MessageType.HELLO);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			bytes.write(Longs.toByteArray(timestamp));
			Serialization.serializeSizedString(bytes, versionString);
			Serialization.serializeSizedString(bytes, senderPeerAddress);
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}
		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private HelloMessage(int id, long timestamp, String versionString, String senderPeerAddress) {
        super(id, MessageType.HELLO);

		this.timestamp = timestamp;
		this.versionString = versionString;
		this.senderPeerAddress = senderPeerAddress;

	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public String getVersionString() {
		return this.versionString;
	}

	public String getSenderPeerAddress() {
		return this.senderPeerAddress;
	}

	public int getPeerType() { return peerType; }

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
		long timestamp = byteBuffer.getLong();

		String versionString;
		String senderPeerAddress = null;
		try {
            versionString = Serialization.deserializeSizedString(byteBuffer, 255);
            // Sender peer address added in v3.0, so is an optional field. Older versions won't send it.
            if (byteBuffer.hasRemaining()) {
                senderPeerAddress = Serialization.deserializeSizedString(byteBuffer, 255);
            }
		} catch (TransformationException e) {
			throw new MessageException(e.getMessage(), e);
		}

        return new HelloMessage(id, timestamp, versionString, senderPeerAddress);
	}
}
