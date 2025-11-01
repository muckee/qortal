package org.qortal.transaction;

import com.google.common.base.Utf8;
import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.block.BlockChain;
import org.qortal.controller.repository.NamesDatabaseIntegrityCheck;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.CancelSellNameTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.naming.Name;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Unicode;

import java.util.Collections;
import java.util.List;

public class CancelSellNameTransaction extends Transaction {

	// Properties
	private CancelSellNameTransactionData cancelSellNameTransactionData;

	// Constructors

	public CancelSellNameTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.cancelSellNameTransactionData = (CancelSellNameTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	// Navigation

	public Account getOwner() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		String name = this.cancelSellNameTransactionData.getName();

		// Check name size bounds
		int nameLength = Utf8.encodedLength(name);
		if (nameLength < 1 || nameLength > Name.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check name is in normalized form (no leading/trailing whitespace, etc.)
		if (!name.equals(Unicode.normalize(name)))
			return ValidationResult.NAME_NOT_NORMALIZED;

		NameData nameData = this.repository.getNameRepository().fromName(name);

		// Check name exists
		if (nameData == null)
			return ValidationResult.NAME_DOES_NOT_EXIST;

		// Check name is currently for sale
		if (!nameData.isForSale()) {
			// Only validate after feature-trigger timestamp, due to a small number of double cancelations in the chain history
			if (this.cancelSellNameTransactionData.getTimestamp() > BlockChain.getInstance().getCancelSellNameValidationTimestamp())
				return ValidationResult.NAME_NOT_FOR_SALE;
		}

		// Check transaction creator matches name's current owner
		Account owner = getOwner();
		if (!owner.getAddress().equals(nameData.getOwner()))
			return ValidationResult.INVALID_NAME_OWNER;

		// Check issuer has enough funds
		if (owner.getConfirmedBalance(Asset.QORT) < cancelSellNameTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;

	}

	@Override
	public void preProcess() throws DataException {
		CancelSellNameTransactionData cancelSellNameTransactionData = (CancelSellNameTransactionData) transactionData;

		// Rebuild this name in the Names table from the transaction history
		// This is necessary because in some rare cases names can be missing from the Names table after registration
		// but we have been unable to reproduce the issue and track down the root cause
		NamesDatabaseIntegrityCheck namesDatabaseIntegrityCheck = new NamesDatabaseIntegrityCheck();
		namesDatabaseIntegrityCheck.rebuildName(cancelSellNameTransactionData.getName(), this.repository);
	}

	@Override
	public void process() throws DataException {
		// Update Name
		Name name = new Name(this.repository, cancelSellNameTransactionData.getName());
		name.cancelSell(cancelSellNameTransactionData);

		// Save this transaction, with updated "name reference" to previous transaction that updated name
		this.repository.getTransactionRepository().save(cancelSellNameTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert name
		Name name = new Name(this.repository, cancelSellNameTransactionData.getName());
		name.uncancelSell(cancelSellNameTransactionData);

		// Save this transaction, with removed "name reference"
		this.repository.getTransactionRepository().save(cancelSellNameTransactionData);
	}

}
