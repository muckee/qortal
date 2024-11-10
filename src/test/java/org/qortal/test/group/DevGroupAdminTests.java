package org.qortal.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.GroupUtils;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ValidationResult;

import static org.junit.Assert.*;

/**
 * Dev group admin tests
 *
 * The dev group (ID 1) is owned by the null account with public key 11111111111111111111111111111111
 * To regain access to otherwise blocked owner-based rules, it has different validation logic
 * which applies to groups with this same null owner.
 *
 * The main difference is that approval is required for certain transaction types relating to
 * null-owned groups. This allows existing admins to approve updates to the group (using group's
 * approval threshold) instead of these actions being performed by the owner.
 *
 * Since these apply to all null-owned groups, this allows anyone to update their group to
 * the null owner if they want to take advantage of this decentralized approval system.
 *
 * Currently, the affected transaction types are:
 * - AddGroupAdminTransaction
 * - RemoveGroupAdminTransaction
 *
 * This same approach could ultimately be applied to other group transactions too.
 */
public class DevGroupAdminTests extends Common {

	private static final int DEV_GROUP_ID = 1;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testGroupKickMember() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Dev group
			int groupId = DEV_GROUP_ID;

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Attempt to kick Bob
			ValidationResult result = groupKick(repository, alice, groupId, bob.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Alice to invite Bob, as it's a closed group
			groupInvite(repository, alice, groupId, bob.getAddress(), 3600);

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Attempt to kick Bob
			result = groupKick(repository, alice, groupId, bob.getAddress());
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));
		}
	}

	@Test
	public void testGroupKickAdmin() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Dev group
			int groupId = DEV_GROUP_ID;

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Alice to invite Bob, as it's a closed group
			groupInvite(repository, alice, groupId, bob.getAddress(), 3600);

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Promote Bob to admin
			TransactionData addGroupAdminTransactionData = addGroupAdmin(repository, alice, groupId, bob.getAddress());

			// Confirm transaction needs approval, and hasn't been approved
			Transaction.ApprovalStatus approvalStatus = GroupUtils.getApprovalStatus(repository, addGroupAdminTransactionData.getSignature());
			assertEquals("incorrect transaction approval status", Transaction.ApprovalStatus.PENDING, approvalStatus);

			// Have Alice approve Bob's approval-needed transaction
			GroupUtils.approveTransaction(repository, "alice", addGroupAdminTransactionData.getSignature(), true);

			// Mint a block so that the transaction becomes approved
			BlockUtils.mintBlock(repository);

			// Confirm transaction is approved
			approvalStatus = GroupUtils.getApprovalStatus(repository, addGroupAdminTransactionData.getSignature());
			assertEquals("incorrect transaction approval status", Transaction.ApprovalStatus.APPROVED, approvalStatus);

			// Confirm Bob is now admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Attempt to kick Bob
			ValidationResult result = groupKick(repository, alice, groupId, bob.getAddress());
			// Shouldn't be allowed
			assertEquals(ValidationResult.INVALID_GROUP_OWNER, result);

			// Confirm Bob is still a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Confirm Bob still an admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob no longer an admin (ADD_GROUP_ADMIN no longer approved)
			assertFalse(isAdmin(repository, bob.getAddress(), groupId));

			// Have Alice try to kick herself!
			result = groupKick(repository, alice, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Have Bob try to kick Alice
			result = groupKick(repository, bob, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);
		}
	}

	@Test
	public void testGroupBanMember() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Dev group
			int groupId = DEV_GROUP_ID;

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Attempt to cancel non-existent Bob ban
			ValidationResult result = cancelGroupBan(repository, alice, groupId, bob.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Attempt to ban Bob
			result = groupBan(repository, alice, groupId, bob.getAddress());
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Orphan last block (Bob ban)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed group-ban transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Alice to invite Bob, as it's a closed group
			groupInvite(repository, alice, groupId, bob.getAddress(), 3600);

			// Bob to join
			result = joinGroup(repository, bob, groupId);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Attempt to ban Bob
			result = groupBan(repository, alice, groupId, bob.getAddress());
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Cancel Bob's ban
			result = cancelGroupBan(repository, alice, groupId, bob.getAddress());
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Orphan last block (Bob join)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed join-group transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Orphan last block (Cancel Bob ban)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed cancel-ban transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Orphan last block (Bob ban)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed group-ban transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));
		}
	}

	@Test
	public void testGroupBanAdmin() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Dev group
			int groupId = DEV_GROUP_ID;

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Alice to invite Bob, as it's a closed group
			groupInvite(repository, alice, groupId, bob.getAddress(), 3600);

			// Bob to join
			ValidationResult result = joinGroup(repository, bob, groupId);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Promote Bob to admin
			TransactionData addGroupAdminTransactionData = addGroupAdmin(repository, alice, groupId, bob.getAddress());

			// Confirm transaction needs approval, and hasn't been approved
			Transaction.ApprovalStatus approvalStatus = GroupUtils.getApprovalStatus(repository, addGroupAdminTransactionData.getSignature());
			assertEquals("incorrect transaction approval status", Transaction.ApprovalStatus.PENDING, approvalStatus);

			// Have Alice approve Bob's approval-needed transaction
			GroupUtils.approveTransaction(repository, "alice", addGroupAdminTransactionData.getSignature(), true);

			// Mint a block so that the transaction becomes approved
			BlockUtils.mintBlock(repository);

			// Confirm transaction is approved
			approvalStatus = GroupUtils.getApprovalStatus(repository, addGroupAdminTransactionData.getSignature());
			assertEquals("incorrect transaction approval status", Transaction.ApprovalStatus.APPROVED, approvalStatus);

			// Confirm Bob is now admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Attempt to ban Bob
			result = groupBan(repository, alice, groupId, bob.getAddress());
			// .. but we can't, because Bob is an admin and the group has no owner
			assertEquals(ValidationResult.INVALID_GROUP_OWNER, result);

			// Confirm Bob still a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// ... and still an admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Have Alice try to ban herself!
			result = groupBan(repository, alice, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Have Bob try to ban Alice
			result = groupBan(repository, bob, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);
		}
	}


	private ValidationResult joinGroup(Repository repository, PrivateKeyAccount joiner, int groupId) throws DataException {
		JoinGroupTransactionData transactionData = new JoinGroupTransactionData(TestTransaction.generateBase(joiner), groupId);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, joiner);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private void groupInvite(Repository repository, PrivateKeyAccount admin, int groupId, String invitee, int timeToLive) throws DataException {
		GroupInviteTransactionData transactionData = new GroupInviteTransactionData(TestTransaction.generateBase(admin), groupId, invitee, timeToLive);
		TransactionUtils.signAndMint(repository, transactionData, admin);
	}

	private ValidationResult groupKick(Repository repository, PrivateKeyAccount admin, int groupId, String member) throws DataException {
		GroupKickTransactionData transactionData = new GroupKickTransactionData(TestTransaction.generateBase(admin), groupId, member, "testing");
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private ValidationResult groupBan(Repository repository, PrivateKeyAccount admin, int groupId, String member) throws DataException {
		GroupBanTransactionData transactionData = new GroupBanTransactionData(TestTransaction.generateBase(admin), groupId, member, "testing", 0);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private ValidationResult cancelGroupBan(Repository repository, PrivateKeyAccount admin, int groupId, String member) throws DataException {
		CancelGroupBanTransactionData transactionData = new CancelGroupBanTransactionData(TestTransaction.generateBase(admin), groupId, member);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private TransactionData addGroupAdmin(Repository repository, PrivateKeyAccount owner, int groupId, String member) throws DataException {
		AddGroupAdminTransactionData transactionData = new AddGroupAdminTransactionData(TestTransaction.generateBase(owner), groupId, member);
		transactionData.setTxGroupId(groupId);
		TransactionUtils.signAndMint(repository, transactionData, owner);
		return transactionData;
	}

	private boolean isMember(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().memberExists(groupId, address);
	}

	private boolean isAdmin(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().adminExists(groupId, address);
	}

}
