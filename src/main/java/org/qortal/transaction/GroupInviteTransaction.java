package org.qortal.transaction;

import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.crypto.Crypto;
import org.qortal.data.transaction.GroupInviteTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import java.util.Collections;
import java.util.List;

public class GroupInviteTransaction extends Transaction {

	// Properties

	private GroupInviteTransactionData groupInviteTransactionData;
	private Account inviteeAccount = null;

	// Constructors

	public GroupInviteTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.groupInviteTransactionData = (GroupInviteTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.groupInviteTransactionData.getInvitee());
	}

	// Navigation

	public Account getAdmin() {
		return this.getCreator();
	}

	public Account getInvitee() {
		if (this.inviteeAccount == null)
			this.inviteeAccount = new Account(this.repository, this.groupInviteTransactionData.getInvitee());

		return this.inviteeAccount;
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		int groupId = this.groupInviteTransactionData.getGroupId();

		// Check time to live zero (infinite) or positive
		if (this.groupInviteTransactionData.getTimeToLive() < 0)
			return ValidationResult.INVALID_LIFETIME;

		// Check member address is valid
		if (!Crypto.isValidAddress(this.groupInviteTransactionData.getInvitee()))
			return ValidationResult.INVALID_ADDRESS;

		// Check group exists
		if (!this.repository.getGroupRepository().groupExists(groupId))
			return ValidationResult.GROUP_DOES_NOT_EXIST;

		Account admin = getAdmin();

		// Can't invite if not an admin
		if (!this.repository.getGroupRepository().adminExists(groupId, admin.getAddress()))
			return ValidationResult.NOT_GROUP_ADMIN;

		Account invitee = getInvitee();

		// Check invitee not already in group
		if (this.repository.getGroupRepository().memberExists(groupId, invitee.getAddress()))
			return ValidationResult.ALREADY_GROUP_MEMBER;

		// Check invitee is not banned
		if (this.repository.getGroupRepository().banExists(groupId, invitee.getAddress(), this.groupInviteTransactionData.getTimestamp()))
			return ValidationResult.BANNED_FROM_GROUP;

		// Check creator has enough funds
		if (admin.getConfirmedBalance(Asset.QORT) < this.groupInviteTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public void preProcess() throws DataException {
		// Nothing to do
	}

	@Override
	public void process() throws DataException {
		// Update Group Membership
		Group group = new Group(this.repository, this.groupInviteTransactionData.getGroupId());
		group.invite(this.groupInviteTransactionData);

		// Save this transaction with updated member/admin references to transactions that can help restore state
		this.repository.getTransactionRepository().save(this.groupInviteTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert group membership
		Group group = new Group(this.repository, this.groupInviteTransactionData.getGroupId());
		group.uninvite(this.groupInviteTransactionData);

		// Save this transaction with removed member/admin references
		this.repository.getTransactionRepository().save(this.groupInviteTransactionData);
	}

}
