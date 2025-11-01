package org.qortal.transaction;

import org.qortal.account.Account;
import org.qortal.account.PublicKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.crypto.Crypto;
import org.qortal.data.group.GroupData;
import org.qortal.data.transaction.GroupKickTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.GroupRepository;
import org.qortal.repository.Repository;

import java.util.Collections;
import java.util.List;

public class GroupKickTransaction extends Transaction {

	// Properties
	private GroupKickTransactionData groupKickTransactionData;

	// Constructors

	public GroupKickTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.groupKickTransactionData = (GroupKickTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.groupKickTransactionData.getMember());
	}

	// Navigation

	public Account getAdmin() throws DataException {
		return new PublicKeyAccount(this.repository, this.groupKickTransactionData.getAdminPublicKey());
	}

	public Account getMember() throws DataException {
		return new Account(this.repository, this.groupKickTransactionData.getMember());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		int groupId = this.groupKickTransactionData.getGroupId();

		// Check member address is valid
		if (!Crypto.isValidAddress(this.groupKickTransactionData.getMember()))
			return ValidationResult.INVALID_ADDRESS;

		GroupRepository groupRepository = this.repository.getGroupRepository();
		GroupData groupData = groupRepository.fromGroupId(groupId);

		// Check group exists
		if (groupData == null)
			return ValidationResult.GROUP_DOES_NOT_EXIST;

		Account admin = getAdmin();

		// Can't kick if not an admin
		if (!groupRepository.adminExists(groupId, admin.getAddress()))
			return ValidationResult.NOT_GROUP_ADMIN;

		Account member = getMember();

		// Check member actually in group UNLESS there's a pending join request
		if (!groupRepository.joinRequestExists(groupId, member.getAddress()) && !groupRepository.memberExists(groupId, member.getAddress()))
			return ValidationResult.NOT_GROUP_MEMBER;

		// Can't kick group owner
		if (member.getAddress().equals(groupData.getOwner()))
			return ValidationResult.INVALID_GROUP_OWNER;

		// Can't kick another admin unless kicker is the group owner
		if (!admin.getAddress().equals(groupData.getOwner()) && groupRepository.adminExists(groupId, member.getAddress()))
			return ValidationResult.INVALID_GROUP_OWNER;

		// Can't kick if not group's current owner
		if (!admin.getAddress().equals(groupData.getOwner()))
			return ValidationResult.INVALID_GROUP_OWNER;

		// Check creator has enough funds
		if (admin.getConfirmedBalance(Asset.QORT) < this.groupKickTransactionData.getFee())
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
		Group group = new Group(this.repository, this.groupKickTransactionData.getGroupId());
		group.kick(this.groupKickTransactionData);

		// Save this transaction with updated member/admin references to transactions that can help restore state
		this.repository.getTransactionRepository().save(this.groupKickTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert group membership
		Group group = new Group(this.repository, this.groupKickTransactionData.getGroupId());
		group.unkick(this.groupKickTransactionData);

		// Save this transaction with removed member/admin references
		this.repository.getTransactionRepository().save(this.groupKickTransactionData);
	}

}
