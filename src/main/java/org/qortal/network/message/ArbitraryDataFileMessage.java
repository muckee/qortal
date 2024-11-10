package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.repository.DataException;
import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

public class ArbitraryDataFileMessage extends Message {

	private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileMessage.class);

	private byte[] signature;
	private ArbitraryDataFile arbitraryDataFile;

	public ArbitraryDataFileMessage(byte[] signature, ArbitraryDataFile arbitraryDataFile) {
		super(MessageType.ARBITRARY_DATA_FILE);

		byte[] data = arbitraryDataFile.getBytes();

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

	private ArbitraryDataFileMessage(int id, byte[] signature, ArbitraryDataFile arbitraryDataFile) {
		super(id, MessageType.ARBITRARY_DATA_FILE);

		this.signature = signature;
		this.arbitraryDataFile = arbitraryDataFile;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public ArbitraryDataFile getArbitraryDataFile() {
		return this.arbitraryDataFile;
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
			ArbitraryDataFile arbitraryDataFile = new ArbitraryDataFile(data, signature, false);
			return new ArbitraryDataFileMessage(id, signature, arbitraryDataFile);
		} catch (DataException e) {
			LOGGER.info("Unable to process received file: {}", e.getMessage());
			throw new MessageException("Unable to process received file: " + e.getMessage(), e);
		}
	}

}
