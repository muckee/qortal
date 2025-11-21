package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.qortal.transform.Transformer;
import org.qortal.transform.block.BlockTransformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class GetBlocksMessage extends Message {

    private static final int BLOCK_SIGNATURE_LENGTH = BlockTransformer.BLOCK_SIGNATURE_LENGTH;

    private byte[] parentSignature;
    private int numberRequested;

    public GetBlocksMessage(byte[] parentSignature, int numberRequested) {
        this(-1, parentSignature, numberRequested);
    }

    private GetBlocksMessage(int id, byte[] parentSignature, int numberRequested) {
        super(id, MessageType.GET_BLOCKS);

        this.parentSignature = parentSignature;
        this.numberRequested = numberRequested;
    }

    public byte[] getParentSignature() {
        return this.parentSignature;
    }

    public int getNumberRequested() {
        return this.numberRequested;
    }

    public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
        if (bytes.remaining() != BLOCK_SIGNATURE_LENGTH + Transformer.INT_LENGTH)
            return null;

        byte[] parentSignature = new byte[BLOCK_SIGNATURE_LENGTH];
        bytes.get(parentSignature);

        int numberRequested = bytes.getInt();

        return new GetBlocksMessage(id, parentSignature, numberRequested);
    }

    @Override
    protected byte[] toData() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            bytes.write(this.parentSignature);

            bytes.write(Ints.toByteArray(this.numberRequested));

            return bytes.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

}
