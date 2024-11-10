package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.repository.DataException;
import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

public class ArbitraryMetadataMessage extends Message {

	private byte[] signature;
	private ArbitraryDataFile arbitraryMetadataFile;

	public ArbitraryMetadataMessage(byte[] signature, ArbitraryDataFile arbitraryMetadataFile) {
		super(MessageType.ARBITRARY_METADATA);

		byte[] data = arbitraryMetadataFile.getBytes();

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			bytes.write(signature);

			bytes.write(Ints.toByteArray(data.length));

			bytes.write(data);
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private ArbitraryMetadataMessage(int id, byte[] signature, ArbitraryDataFile arbitraryMetadataFile) {
		super(id, MessageType.ARBITRARY_METADATA);

		this.signature = signature;
		this.arbitraryMetadataFile = arbitraryMetadataFile;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public ArbitraryDataFile getArbitraryMetadataFile() {
		return this.arbitraryMetadataFile;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
		byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		int dataLength = byteBuffer.getInt();

		if (byteBuffer.remaining() < dataLength)
			throw new BufferUnderflowException();

		byte[] data = new byte[dataLength];
		byteBuffer.get(data);

		try {
			ArbitraryDataFile arbitraryMetadataFile = new ArbitraryDataFile(data, signature, false);
			return new ArbitraryMetadataMessage(id, signature, arbitraryMetadataFile);
		} catch (DataException e) {
			throw new MessageException("Unable to process arbitrary metadata message: " + e.getMessage(), e);
		}
	}

}
