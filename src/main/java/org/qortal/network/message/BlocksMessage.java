package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.block.Block;
import org.qortal.data.at.ATStateData;
import org.qortal.data.block.BlockData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.transform.TransformationException;
import org.qortal.transform.block.BlockTransformation;
import org.qortal.transform.block.BlockTransformer;
import org.qortal.utils.Triple;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class BlocksMessage extends Message {

    private static final Logger LOGGER = LogManager.getLogger(BlocksMessage.class);

    private List<Block> blocks;

    public BlocksMessage(List<Block> blocks) {
        this(-1, blocks);
    }

    private BlocksMessage(int id, List<Block> blocks) {
        super(id, MessageType.BLOCKS);

        this.blocks = blocks;
    }

    public List<Block> getBlocks() {
        return this.blocks;
    }

    public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {

        int count = bytes.getInt();
        List<Block> blocks = new ArrayList<>();

        for (int i = 0; i < count; ++i) {
            int height = bytes.getInt();

            try {
                boolean finalBlockInBuffer = (i == count-1);

                Triple<BlockData, List<TransactionData>, List<ATStateData>> blockInfo = null;
                blockInfo = BlockTransformer.fromByteBuffer(bytes, finalBlockInBuffer);
                BlockData blockData = blockInfo.getA();
                blockData.setHeight(height);

                // We are unable to obtain a valid Repository instance here, so set it to null and we will attach it later
                Block block = new Block(null, blockData, blockInfo.getB(), blockInfo.getC());
                blocks.add(block);

            } catch (TransformationException e) {
                return null;
            }

        }

        return new BlocksMessage(id, blocks);
    }

//    // From ChatGPT
//public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
//    try {
//        int blockCount = byteBuffer.getInt();
//
//        List<BlockData> blocks = new ArrayList<>(blockCount);
//        List<List<TransactionData>> transactions = new ArrayList<>(blockCount);
//        List<byte[]> atStatesHashes = new ArrayList<>(blockCount);
//
//        for (int i = 0; i < blockCount; ++i) {
//            int height = byteBuffer.getInt();
//
//            BlockTransformation blockTransformation = BlockTransformer.fromByteBufferV2(byteBuffer);
//
//            BlockData blockData = blockTransformation.getBlockData();
//            blockData.setHeight(height);
//
//            blocks.add(blockData);
//            transactions.add(blockTransformation.getTransactions());
//            atStatesHashes.add(blockTransformation.getAtStatesHash());
//        }
//
//        return new BlocksMessage(id, blocks, transactions, atStatesHashes);
//    } catch (TransformationException e) {
//        LOGGER.info(String.format("Received garbled BLOCKS message: %s", e.getMessage()));
//        throw new MessageException(e.getMessage(), e);
//    }
//}


    @Override
    protected byte[] toData() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            bytes.write(Ints.toByteArray(this.blocks.size()));

            for (Block block : this.blocks) {
                bytes.write(Ints.toByteArray(block.getBlockData().getHeight()));
                bytes.write(BlockTransformer.toBytes(block));
            }
            LOGGER.trace(String.format("Total length of %d blocks is %d bytes", this.blocks.size(), bytes.size()));

            return bytes.toByteArray();
        } catch (IOException e) {
            return null;
        } catch (TransformationException e) {
            return null;
        }
    }

}
