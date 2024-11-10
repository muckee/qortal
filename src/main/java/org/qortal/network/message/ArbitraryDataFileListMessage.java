package org.qortal.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.data.network.PeerData;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ArbitraryDataFileListMessage extends Message {

	private byte[] signature;
	private List<byte[]> hashes;
	private Long requestTime;
	private Integer requestHops;
	private String peerAddress;
	private Boolean isRelayPossible;

	public ArbitraryDataFileListMessage(byte[] signature, List<byte[]> hashes, Long requestTime,
										Integer requestHops, String peerAddress, Boolean isRelayPossible) {
		super(MessageType.ARBITRARY_DATA_FILE_LIST);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			bytes.write(signature);

			bytes.write(Ints.toByteArray(hashes.size()));

			for (byte[] hash : hashes) {
				bytes.write(hash);
			}

			if (requestTime != null) {
				// The remaining fields are optional

				bytes.write(Longs.toByteArray(requestTime));

				bytes.write(Ints.toByteArray(requestHops));

				Serialization.serializeSizedStringV2(bytes, peerAddress);

				bytes.write(Ints.toByteArray(Boolean.TRUE.equals(isRelayPossible) ? 1 : 0));
			}
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	/** Legacy version */
	public ArbitraryDataFileListMessage(byte[] signature, List<byte[]> hashes) {
		this(signature, hashes, null, null, null, null);
	}

	private ArbitraryDataFileListMessage(int id, byte[] signature, List<byte[]> hashes, Long requestTime,
										Integer requestHops, String peerAddress, boolean isRelayPossible) {
		super(id, MessageType.ARBITRARY_DATA_FILE_LIST);

		this.signature = signature;
		this.hashes = hashes;
		this.requestTime = requestTime;
		this.requestHops = requestHops;
		this.peerAddress = peerAddress;
		this.isRelayPossible = isRelayPossible;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public List<byte[]> getHashes() {
		return this.hashes;
	}

	public Long getRequestTime() {
		return this.requestTime;
	}

	public Integer getRequestHops() {
		return this.requestHops;
	}

	public String getPeerAddress() {
		return this.peerAddress;
	}

	public Boolean isRelayPossible() {
		return this.isRelayPossible;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
		byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
		bytes.get(signature);

		int count = bytes.getInt();

		List<byte[]> hashes = new ArrayList<>();
		for (int i = 0; i < count; ++i) {
			byte[] hash = new byte[Transformer.SHA256_LENGTH];
			bytes.get(hash);
			hashes.add(hash);
		}

		Long requestTime = null;
		Integer requestHops = null;
		String peerAddress = null;
		boolean isRelayPossible = true; // Legacy versions only send this message when relaying is possible

		// The remaining fields are optional
		if (bytes.hasRemaining()) {
			try {
				requestTime = bytes.getLong();

				requestHops = bytes.getInt();

				peerAddress = Serialization.deserializeSizedStringV2(bytes, PeerData.MAX_PEER_ADDRESS_SIZE);

				isRelayPossible = bytes.getInt() > 0;
			} catch (TransformationException e) {
				throw new MessageException(e.getMessage(), e);
			}
		}

		return new ArbitraryDataFileListMessage(id, signature, hashes, requestTime, requestHops, peerAddress, isRelayPossible);
	}

}
