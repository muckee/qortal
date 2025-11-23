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
	private PeerCapabilities capabilities = new PeerCapabilities();
	private int peerType = 0;

    private static final Logger LOGGER = LogManager.getLogger(HelloMessage.class);

	// Added in V5.1 to include capabilities exchange
	public HelloMessage(long timestamp, String versionString, String senderPeerAddress, Map<String, Object> caps, int peerType) {
		super(MessageType.HELLO);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			bytes.write(Longs.toByteArray(timestamp));
			Serialization.serializeSizedString(bytes, versionString);
			Serialization.serializeSizedString(bytes, senderPeerAddress);
			bytes.write(ByteBuffer.allocate(4).putInt(peerType).array());

            // New in version 5.5.0, only send if the remote was properly identified
            LOGGER.info("Is caps null?: {}", caps == null);
            //if (caps != null) {
                Serialization.serializeMap(bytes, caps);
            //}

		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}
		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private HelloMessage(int id, long timestamp, String versionString, String senderPeerAddress, PeerCapabilities caps, int peerType) {
		super(id, MessageType.HELLO);

		this.timestamp = timestamp;
		this.versionString = versionString;
		this.senderPeerAddress = senderPeerAddress;
		this.capabilities = caps;
		this.peerType = peerType;
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

	public PeerCapabilities getCapabilities() { return this.capabilities; }

	public int getPeerType() { return peerType; }

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
		long timestamp = byteBuffer.getLong();

		String versionString;
		String senderPeerAddress = null;
		int peerType = Peer.NETWORK;
		Map<String, Object> capabilities = new HashMap<>();
		try {
            versionString = Serialization.deserializeSizedString(byteBuffer, 255);

            // Sender peer address added in v3.0, so is an optional field. Older versions won't send it.
            if (byteBuffer.hasRemaining()) {
                senderPeerAddress = Serialization.deserializeSizedString(byteBuffer, 255);
            }

            // Below only exists in v5.5.0 and great
            if(isAtleastVersion("5.5.0", versionString)) {
                LOGGER.info("Peer reported at v5.5.0");
            }
                peerType = byteBuffer.getInt();
                if (byteBuffer.hasRemaining()) {
                    capabilities = Serialization.deserializeMap(byteBuffer);
                }
                //else {
                //    capabilities = null;  // Set to null to represent an older version client connection
                //}
            //} else {
            //    capabilities = null;
            //}
		} catch (TransformationException e) {
			throw new MessageException(e.getMessage(), e);
		} catch (IOException e) {
            throw new RuntimeException(e);
        }

        return new HelloMessage(id, timestamp, versionString, senderPeerAddress, new PeerCapabilities(capabilities), peerType);
	}

    public static final Pattern VERSION_PATTERN = Pattern.compile(Controller.VERSION_PREFIX
            + "(\\d{1,3})\\.(\\d{1,5})\\.(\\d{1,5})");

    private static boolean isAtleastVersion(String minVer, String ver) {
        // Add the version prefix
        String minVersionString = Controller.VERSION_PREFIX + minVer;
        String versionString = Controller.VERSION_PREFIX + ver;

        Matcher matcher1 = VERSION_PATTERN.matcher(minVersionString);
        Matcher matcher2 = VERSION_PATTERN.matcher(versionString);

        if (!matcher1.lookingAt() || !matcher2.lookingAt()) {
            return false;
        }

        // We're expecting 3 positive shorts, so we can convert 1.2.3 into 0x0100020003
        long minVersion = 0;
        long version = 0;
        for (int g = 1; g <= 3; ++g) {
            long value1 = Long.parseLong(matcher1.group(g));
            long value2 = Long.parseLong(matcher2.group(g));

            if (value1 < 0 || value1 > Short.MAX_VALUE || value2 < 0 || value2 > Short.MAX_VALUE) {
                return false;
            }

            minVersion <<= 16;
            version <<= 16;
            minVersion |= value1;
            version |= value2;
        }

        return version >= minVersion;
    }

}
