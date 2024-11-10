package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.Block;
import org.qortal.controller.BlockMinter;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.utils.NTP;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class PenaltyFixTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings("test-settings-v2-penalty-fix.json");
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}

	@Test
	public void testSingleSponsor() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// Alice self share online, and will be used to mint the blocks
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			List<PrivateKeyAccount> onlineAccounts = new ArrayList<>();
			onlineAccounts.add(aliceSelfShare);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");

			// Test account from real penalty data (pen-revert.json)
			Account penaltyAccount = new Account(repository, "QLcAQpko5egwNjifueCAeAsT8CAj2Sr5qJ");

			// Bob sends a payment to the penalty account, so that it gets a row in the Accounts table
			TransactionData paymentData = new PaymentTransactionData(TestTransaction.generateBase(bobAccount), penaltyAccount.getAddress(), 1);
			TransactionUtils.signAndImportValid(repository, paymentData, bobAccount); // updates paymentData's signature

			// Mint blocks up to height 4
			Block block = null;
			for (int i = 2; i <= 4; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			assertEquals(4, (int)block.getBlockData().getHeight());

			// Check blocks minted penalty of penalty account
			assertEquals(0, (int) penaltyAccount.getBlocksMintedPenalty());

			// Penalty revert code runs at block 5
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(5, (int)block.getBlockData().getHeight());

			// +5000000 blocks minted penalty should be applied
			assertEquals(5000000, (int) penaltyAccount.getBlocksMintedPenalty());

			// Orphan the last block, to simulate a re-org
			BlockUtils.orphanLastBlock(repository);

			assertEquals(0, (int) penaltyAccount.getBlocksMintedPenalty());

			// Penalty revert code runs again
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(5, (int)block.getBlockData().getHeight());

			// Penalty should still be 5000000, rather than doubled up to 10000000
			assertEquals(5000000, (int) penaltyAccount.getBlocksMintedPenalty());
		}
	}
}