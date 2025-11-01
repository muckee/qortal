package org.qortal.test;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.controller.BlockMinter;
import org.qortal.data.at.ATStateData;
import org.qortal.data.block.BlockData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.*;
import org.qortal.repository.hsqldb.HSQLDBRepository;
import org.qortal.settings.Settings;
import org.qortal.test.common.AtUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.transaction.DeployAtTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.block.BlockTransformation;
import org.qortal.utils.BlockArchiveUtils;
import org.qortal.utils.NTP;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.List;

import static org.junit.Assert.*;

public class BlockArchiveV2Tests extends Common {

	@Before
	public void beforeTest() throws DataException, IllegalAccessException {
		Common.useSettings("test-settings-v2-block-archive.json");
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
		this.deleteArchiveDirectory();

		// Set default archive version to 2, so that archive builds in these tests use V2
		FieldUtils.writeField(Settings.getInstance(), "defaultArchiveVersion", 2, true);
	}

	@After
	public void afterTest() throws DataException {
		this.deleteArchiveDirectory();
	}


	@Test
	public void testWriter() throws DataException, InterruptedException, TransformationException, IOException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// Mint some blocks so that we are able to archive them later
			for (int i = 0; i < 1000; i++) {
				BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));
			}

			// 900 blocks are trimmed (this specifies the first untrimmed height)
			repository.getBlockRepository().setOnlineAccountsSignaturesTrimHeight(901);
			repository.getATRepository().setAtTrimHeight(901);

			// Check the max archive height - this should be one less than the first untrimmed height
			final int maximumArchiveHeight = BlockArchiveWriter.getMaxArchiveHeight(repository);
			assertEquals(900, maximumArchiveHeight);

			// Write blocks 2-900 to the archive
			BlockArchiveWriter writer = new BlockArchiveWriter(0, maximumArchiveHeight, repository);
			writer.setShouldEnforceFileSizeTarget(false); // To avoid the need to pre-calculate file sizes
			BlockArchiveWriter.BlockArchiveWriteResult result = writer.write();
			assertEquals(BlockArchiveWriter.BlockArchiveWriteResult.OK, result);

			// Make sure that the archive contains the correct number of blocks
			assertEquals(900 - 1, writer.getWrittenCount());

			// Increment block archive height
			repository.getBlockArchiveRepository().setBlockArchiveHeight(writer.getWrittenCount());
			repository.saveChanges();
			assertEquals(900 - 1, repository.getBlockArchiveRepository().getBlockArchiveHeight());

			// Ensure the file exists
			File outputFile = writer.getOutputPath().toFile();
			assertTrue(outputFile.exists());
		}
	}

	@Test
	public void testWriterAndReader() throws DataException, InterruptedException, TransformationException, IOException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// Mint some blocks so that we are able to archive them later
			for (int i = 0; i < 1000; i++) {
				BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));
			}

			// 900 blocks are trimmed (this specifies the first untrimmed height)
			repository.getBlockRepository().setOnlineAccountsSignaturesTrimHeight(901);
			repository.getATRepository().setAtTrimHeight(901);

			// Check the max archive height - this should be one less than the first untrimmed height
			final int maximumArchiveHeight = BlockArchiveWriter.getMaxArchiveHeight(repository);
			assertEquals(900, maximumArchiveHeight);

			// Write blocks 2-900 to the archive
			BlockArchiveWriter writer = new BlockArchiveWriter(0, maximumArchiveHeight, repository);
			writer.setShouldEnforceFileSizeTarget(false); // To avoid the need to pre-calculate file sizes
			BlockArchiveWriter.BlockArchiveWriteResult result = writer.write();
			assertEquals(BlockArchiveWriter.BlockArchiveWriteResult.OK, result);

			// Make sure that the archive contains the correct number of blocks
			assertEquals(900 - 1, writer.getWrittenCount());

			// Increment block archive height
			repository.getBlockArchiveRepository().setBlockArchiveHeight(writer.getWrittenCount());
			repository.saveChanges();
			assertEquals(900 - 1, repository.getBlockArchiveRepository().getBlockArchiveHeight());

			// Ensure the file exists
			File outputFile = writer.getOutputPath().toFile();
			assertTrue(outputFile.exists());

			// Read block 2 from the archive
			BlockArchiveReader reader = BlockArchiveReader.getInstance();
			BlockTransformation block2Info = reader.fetchBlockAtHeight(2);
			BlockData block2ArchiveData = block2Info.getBlockData();

			// Read block 2 from the repository
			BlockData block2RepositoryData = repository.getBlockRepository().fromHeight(2);

			// Ensure the values match
			assertEquals(block2ArchiveData.getHeight(), block2RepositoryData.getHeight());
			assertArrayEquals(block2ArchiveData.getSignature(), block2RepositoryData.getSignature());

			// Test some values in the archive
			assertEquals(1, block2ArchiveData.getOnlineAccountsCount());

			// Read block 900 from the archive
			BlockTransformation block900Info = reader.fetchBlockAtHeight(900);
			BlockData block900ArchiveData = block900Info.getBlockData();

			// Read block 900 from the repository
			BlockData block900RepositoryData = repository.getBlockRepository().fromHeight(900);

			// Ensure the values match
			assertEquals(block900ArchiveData.getHeight(), block900RepositoryData.getHeight());
			assertArrayEquals(block900ArchiveData.getSignature(), block900RepositoryData.getSignature());

			// Test some values in the archive
			assertEquals(1, block900ArchiveData.getOnlineAccountsCount());

		}
	}

	@Test
	public void testArchivedAtStates() throws DataException, InterruptedException, TransformationException, IOException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// Deploy an AT so that we have AT state data
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			byte[] creationBytes = AtUtils.buildSimpleAT();
			long fundingAmount = 1_00000000L;
			DeployAtTransaction deployAtTransaction =  AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// Mint some blocks so that we are able to archive them later
			for (int i = 0; i < 1000; i++) {
				BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));
			}

			// 9 blocks are trimmed (this specifies the first untrimmed height)
			repository.getBlockRepository().setOnlineAccountsSignaturesTrimHeight(10);
			repository.getATRepository().setAtTrimHeight(10);

			// Check the max archive height
			final int maximumArchiveHeight = BlockArchiveWriter.getMaxArchiveHeight(repository);
			assertEquals(9, maximumArchiveHeight);

			// Write blocks 2-9 to the archive
			BlockArchiveWriter writer = new BlockArchiveWriter(0, maximumArchiveHeight, repository);
			writer.setShouldEnforceFileSizeTarget(false); // To avoid the need to pre-calculate file sizes
			BlockArchiveWriter.BlockArchiveWriteResult result = writer.write();
			assertEquals(BlockArchiveWriter.BlockArchiveWriteResult.OK, result);

			// Make sure that the archive contains the correct number of blocks
			assertEquals(9 - 1, writer.getWrittenCount());

			// Increment block archive height
			repository.getBlockArchiveRepository().setBlockArchiveHeight(writer.getWrittenCount());
			repository.saveChanges();
			assertEquals(9 - 1, repository.getBlockArchiveRepository().getBlockArchiveHeight());

			// Ensure the file exists
			File outputFile = writer.getOutputPath().toFile();
			assertTrue(outputFile.exists());

			// Check blocks 3-9
			for (Integer testHeight = 2; testHeight <= 9; testHeight++) {

				// Read a block from the archive
				BlockArchiveReader reader = BlockArchiveReader.getInstance();
				BlockTransformation blockInfo = reader.fetchBlockAtHeight(testHeight);
				BlockData archivedBlockData = blockInfo.getBlockData();
				byte[] archivedAtStateHash = blockInfo.getAtStatesHash();
				List<TransactionData> archivedTransactions = blockInfo.getTransactions();

				// Read the same block from the repository
				BlockData repositoryBlockData = repository.getBlockRepository().fromHeight(testHeight);
				ATStateData repositoryAtStateData = repository.getATRepository().getATStateAtHeight(atAddress, testHeight);

				// Ensure the repository has full AT state data
				assertNotNull(repositoryAtStateData.getStateHash());
				assertNotNull(repositoryAtStateData.getStateData());

				// Check the archived AT state
				if (testHeight == 2) {
					assertEquals(1, archivedTransactions.size());
					assertEquals(Transaction.TransactionType.DEPLOY_AT, archivedTransactions.get(0).getType());
				}
				else {
					// Blocks 3+ shouldn't have any transactions
					assertTrue(archivedTransactions.isEmpty());
				}

				// Ensure the archive has the AT states hash
				assertNotNull(archivedAtStateHash);

				// Also check the online accounts count and height
				assertEquals(1, archivedBlockData.getOnlineAccountsCount());
				assertEquals(testHeight, archivedBlockData.getHeight());

				// Ensure the values match
				assertEquals(archivedBlockData.getHeight(), repositoryBlockData.getHeight());
				assertArrayEquals(archivedBlockData.getSignature(), repositoryBlockData.getSignature());
				assertEquals(archivedBlockData.getOnlineAccountsCount(), repositoryBlockData.getOnlineAccountsCount());
				assertArrayEquals(archivedBlockData.getMinterSignature(), repositoryBlockData.getMinterSignature());
				assertEquals(archivedBlockData.getATCount(), repositoryBlockData.getATCount());
				assertEquals(archivedBlockData.getOnlineAccountsCount(), repositoryBlockData.getOnlineAccountsCount());
				assertArrayEquals(archivedBlockData.getReference(), repositoryBlockData.getReference());
				assertEquals(archivedBlockData.getTimestamp(), repositoryBlockData.getTimestamp());
				assertEquals(archivedBlockData.getATFees(), repositoryBlockData.getATFees());
				assertEquals(archivedBlockData.getTotalFees(), repositoryBlockData.getTotalFees());
				assertEquals(archivedBlockData.getTransactionCount(), repositoryBlockData.getTransactionCount());
				assertArrayEquals(archivedBlockData.getTransactionsSignature(), repositoryBlockData.getTransactionsSignature());

				// TODO: build atStatesHash and compare against value in archive
			}

			// Check block 10 (unarchived)
			BlockArchiveReader reader = BlockArchiveReader.getInstance();
			BlockTransformation blockInfo = reader.fetchBlockAtHeight(10);
			assertNull(blockInfo);

		}

	}

	@Test
	public void testArchiveAndPrune() throws DataException, InterruptedException, TransformationException, IOException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// Deploy an AT so that we have AT state data
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			byte[] creationBytes = AtUtils.buildSimpleAT();
			long fundingAmount = 1_00000000L;
			AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);

			// Mint some blocks so that we are able to archive them later
			for (int i = 0; i < 1000; i++) {
				BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));
			}

			// Assume 900 blocks are trimmed (this specifies the first untrimmed height)
			repository.getBlockRepository().setOnlineAccountsSignaturesTrimHeight(901);
			repository.getATRepository().setAtTrimHeight(901);

			// Check the max archive height - this should be one less than the first untrimmed height
			final int maximumArchiveHeight = BlockArchiveWriter.getMaxArchiveHeight(repository);
			assertEquals(900, maximumArchiveHeight);

			// Write blocks 2-900 to the archive
			BlockArchiveWriter writer = new BlockArchiveWriter(0, maximumArchiveHeight, repository);
			writer.setShouldEnforceFileSizeTarget(false); // To avoid the need to pre-calculate file sizes
			BlockArchiveWriter.BlockArchiveWriteResult result = writer.write();
			assertEquals(BlockArchiveWriter.BlockArchiveWriteResult.OK, result);

			// Make sure that the archive contains the correct number of blocks
			assertEquals(900 - 1, writer.getWrittenCount());

			// Increment block archive height
			repository.getBlockArchiveRepository().setBlockArchiveHeight(901);
			repository.saveChanges();
			assertEquals(901, repository.getBlockArchiveRepository().getBlockArchiveHeight());

			// Ensure the file exists
			File outputFile = writer.getOutputPath().toFile();
			assertTrue(outputFile.exists());

			// Ensure the SQL repository contains blocks 2 and 900...
			assertNotNull(repository.getBlockRepository().fromHeight(2));
			assertNotNull(repository.getBlockRepository().fromHeight(900));

			// Prune all the archived blocks
			int numBlocksPruned = repository.getBlockRepository().pruneBlocks(0, 900);
			assertEquals(900-1, numBlocksPruned);
			repository.getBlockRepository().setBlockPruneHeight(901);

			// Prune the AT states for the archived blocks
			repository.getATRepository().rebuildLatestAtStates(900);
			repository.saveChanges();
			int numATStatesPruned = repository.getATRepository().pruneAtStates(0, 900);
			assertEquals(900-2, numATStatesPruned); // Minus 1 for genesis block, and another for the latest AT state
			repository.getATRepository().setAtPruneHeight(901);

			// Now ensure the SQL repository is missing blocks 2 and 900...
			assertNull(repository.getBlockRepository().fromHeight(2));
			assertNull(repository.getBlockRepository().fromHeight(900));

			// ... but it's not missing blocks 1 and 901 (we don't prune the genesis block)
			assertNotNull(repository.getBlockRepository().fromHeight(1));
			assertNotNull(repository.getBlockRepository().fromHeight(901));

			// Validate the latest block height in the repository
			assertEquals(1002, (int) repository.getBlockRepository().getLastBlock().getHeight());

		}
	}

	@Test
	public void testTrimArchivePruneAndOrphan() throws DataException, InterruptedException, TransformationException, IOException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// Deploy an AT so that we have AT state data
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			byte[] creationBytes = AtUtils.buildSimpleAT();
			long fundingAmount = 1_00000000L;
			AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);

			// Mint some blocks so that we are able to archive them later
			for (int i = 0; i < 1000; i++) {
				BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));
			}

			// Make sure that block 500 has full AT state data and data hash
			List<ATStateData> block500AtStatesData = repository.getATRepository().getBlockATStatesAtHeight(500);
			ATStateData atStatesData = repository.getATRepository().getATStateAtHeight(block500AtStatesData.get(0).getATAddress(), 500);
			assertNotNull(atStatesData.getStateHash());
			assertNotNull(atStatesData.getStateData());

			// Trim the first 500 blocks
			repository.getBlockRepository().trimOldOnlineAccountsSignatures(0, 500);
			repository.getBlockRepository().setOnlineAccountsSignaturesTrimHeight(501);
			repository.getATRepository().rebuildLatestAtStates(500);
			repository.getATRepository().trimAtStates(0, 500, 1000);
			repository.getATRepository().setAtTrimHeight(501);

			// Now block 499 should only have the AT state data hash
			List<ATStateData> block499AtStatesData = repository.getATRepository().getBlockATStatesAtHeight(499);
			atStatesData = repository.getATRepository().getATStateAtHeight(block499AtStatesData.get(0).getATAddress(), 499);
			assertNotNull(atStatesData.getStateHash());
			assertNull(atStatesData.getStateData());

			// ... but block 500 should have the full data (due to being retained as the "latest" AT state in the trimmed range
			block500AtStatesData = repository.getATRepository().getBlockATStatesAtHeight(500);
			atStatesData = repository.getATRepository().getATStateAtHeight(block500AtStatesData.get(0).getATAddress(), 500);
			assertNotNull(atStatesData.getStateHash());
			assertNotNull(atStatesData.getStateData());

			// ... and block 501 should also have the full data
			List<ATStateData> block501AtStatesData = repository.getATRepository().getBlockATStatesAtHeight(501);
			atStatesData = repository.getATRepository().getATStateAtHeight(block501AtStatesData.get(0).getATAddress(), 501);
			assertNotNull(atStatesData.getStateHash());
			assertNotNull(atStatesData.getStateData());

			// Check the max archive height - this should be one less than the first untrimmed height
			final int maximumArchiveHeight = BlockArchiveWriter.getMaxArchiveHeight(repository);
			assertEquals(500, maximumArchiveHeight);

			BlockData block3DataPreArchive = repository.getBlockRepository().fromHeight(3);

			// Write blocks 2-500 to the archive
			BlockArchiveWriter writer = new BlockArchiveWriter(0, maximumArchiveHeight, repository);
			writer.setShouldEnforceFileSizeTarget(false); // To avoid the need to pre-calculate file sizes
			BlockArchiveWriter.BlockArchiveWriteResult result = writer.write();
			assertEquals(BlockArchiveWriter.BlockArchiveWriteResult.OK, result);

			// Make sure that the archive contains the correct number of blocks
			assertEquals(500 - 1, writer.getWrittenCount()); // -1 for the genesis block

			// Increment block archive height
			repository.getBlockArchiveRepository().setBlockArchiveHeight(writer.getWrittenCount());
			repository.saveChanges();
			assertEquals(500 - 1, repository.getBlockArchiveRepository().getBlockArchiveHeight());

			// Ensure the file exists
			File outputFile = writer.getOutputPath().toFile();
			assertTrue(outputFile.exists());

			// Ensure the SQL repository contains blocks 2 and 500...
			assertNotNull(repository.getBlockRepository().fromHeight(2));
			assertNotNull(repository.getBlockRepository().fromHeight(500));

			// Prune all the archived blocks
			int numBlocksPruned = repository.getBlockRepository().pruneBlocks(0, 500);
			assertEquals(500-1, numBlocksPruned);
			repository.getBlockRepository().setBlockPruneHeight(501);

			// Prune the AT states for the archived blocks
			repository.getATRepository().rebuildLatestAtStates(500);
			repository.saveChanges();
			int numATStatesPruned = repository.getATRepository().pruneAtStates(2, 500);
			assertEquals(498, numATStatesPruned); // Minus 1 for genesis block, and another for the latest AT state
			repository.getATRepository().setAtPruneHeight(501);

			// Now ensure the SQL repository is missing blocks 2 and 500...
			assertNull(repository.getBlockRepository().fromHeight(2));
			assertNull(repository.getBlockRepository().fromHeight(500));

			// ... but it's not missing blocks 1 and 501 (we don't prune the genesis block)
			assertNotNull(repository.getBlockRepository().fromHeight(1));
			assertNotNull(repository.getBlockRepository().fromHeight(501));

			// Validate the latest block height in the repository
			assertEquals(1002, (int) repository.getBlockRepository().getLastBlock().getHeight());

			// Now orphan some unarchived blocks.
			BlockUtils.orphanBlocks(repository, 500);
			assertEquals(502, (int) repository.getBlockRepository().getLastBlock().getHeight());

			// We're close to the lower limit of the SQL database now, so
			// we need to import some blocks from the archive
			BlockArchiveUtils.importFromArchive(401, 500, repository);

			// Ensure the SQL repository now contains block 401 but not 400...
			assertNotNull(repository.getBlockRepository().fromHeight(401));
			assertNull(repository.getBlockRepository().fromHeight(400));

			// Import the remaining 399 blocks
			BlockArchiveUtils.importFromArchive(2, 400, repository);

			// Verify that block 3 matches the original
			BlockData block3DataPostArchive = repository.getBlockRepository().fromHeight(3);
			assertArrayEquals(block3DataPreArchive.getSignature(), block3DataPostArchive.getSignature());
			assertEquals(block3DataPreArchive.getHeight(), block3DataPostArchive.getHeight());

			// Orphan 2 more block, which should be the last one that is possible to be orphaned
			// TODO: figure out why this is 1 block more than in the equivalent block archive V1 test
			BlockUtils.orphanBlocks(repository, 2);

			// Orphan another block, which should fail
			Exception exception = null;
			try {
				BlockUtils.orphanBlocks(repository, 1);
			} catch (DataException e) {
				exception = e;
			}

			// Ensure that a DataException is thrown because there is no more AT states data available
			assertNotNull(exception);
			assertEquals(DataException.class, exception.getClass());

			// FUTURE: we may be able to retain unique AT states when trimming, to avoid this exception
			// and allow orphaning back through blocks with trimmed AT states.

		}
	}


	/**
	 * Many nodes are missing an ATStatesHeightIndex due to an earlier bug
	 * In these cases we disable archiving and pruning as this index is a
	 * very essential component in these processes.
	 */
	@Test
	public void testMissingAtStatesHeightIndex() throws DataException, SQLException {
		try (final HSQLDBRepository repository = (HSQLDBRepository) RepositoryManager.getRepository()) {

			// Firstly check that we're able to prune or archive when the index exists
			assertTrue(repository.getATRepository().hasAtStatesHeightIndex());
			assertTrue(RepositoryManager.canArchiveOrPrune());

			// Delete the index
			repository.prepareStatement("DROP INDEX ATSTATESHEIGHTINDEX").execute();

			// Ensure check that we're unable to prune or archive when the index doesn't exist
			assertFalse(repository.getATRepository().hasAtStatesHeightIndex());
			assertFalse(RepositoryManager.canArchiveOrPrune());
		}
	}


	private void deleteArchiveDirectory() {
		// Delete archive directory if exists
		Path archivePath = Paths.get(Settings.getInstance().getRepositoryPath(), "archive").toAbsolutePath();
		try {
			FileUtils.deleteDirectory(archivePath.toFile());
		} catch (IOException e) {

		}
	}

}
