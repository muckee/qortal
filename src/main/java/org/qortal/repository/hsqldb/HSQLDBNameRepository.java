package org.qortal.repository.hsqldb;

import org.qortal.data.naming.NameData;
import org.qortal.repository.DataException;
import org.qortal.repository.NameRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class HSQLDBNameRepository implements NameRepository {

	protected HSQLDBRepository repository;

	public HSQLDBNameRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	@Override
	public NameData fromName(String name) throws DataException {
		String sql = "SELECT reduced_name, owner, data, registered_when, updated_when, "
				+ "is_for_sale, sale_price, reference, creation_group_id FROM Names WHERE name = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, name)) {
			if (resultSet == null)
				return null;

			String reducedName = resultSet.getString(1);
			String owner = resultSet.getString(2);
			String data = resultSet.getString(3);
			long registered = resultSet.getLong(4);

			// Special handling for possibly-NULL "updated" column
			Long updated = resultSet.getLong(5);
			if (updated == 0 && resultSet.wasNull())
				updated = null;

			boolean isForSale = resultSet.getBoolean(6);

			Long salePrice = resultSet.getLong(7);
			if (salePrice == 0 && resultSet.wasNull())
				salePrice = null;

			byte[] reference = resultSet.getBytes(8);
			int creationGroupId = resultSet.getInt(9);

			return new NameData(name, reducedName, owner, data, registered, updated, isForSale, salePrice, reference, creationGroupId);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch name info from repository", e);
		}
	}

	@Override
	public boolean nameExists(String name) throws DataException {
		try {
			return this.repository.exists("Names", "name = ?", name);
		} catch (SQLException e) {
			throw new DataException("Unable to check for name in repository", e);
		}
	}

	@Override
	public NameData fromReducedName(String reducedName) throws DataException {
		String sql = "SELECT name, owner, data, registered_when, updated_when, "
				+ "is_for_sale, sale_price, reference, creation_group_id FROM Names WHERE reduced_name = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, reducedName)) {
			if (resultSet == null)
				return null;

			String name = resultSet.getString(1);
			String owner = resultSet.getString(2);
			String data = resultSet.getString(3);
			long registered = resultSet.getLong(4);

			// Special handling for possibly-NULL "updated" column
			Long updated = resultSet.getLong(5);
			if (updated == 0 && resultSet.wasNull())
				updated = null;

			boolean isForSale = resultSet.getBoolean(6);

			Long salePrice = resultSet.getLong(7);
			if (salePrice == 0 && resultSet.wasNull())
				salePrice = null;

			byte[] reference = resultSet.getBytes(8);
			int creationGroupId = resultSet.getInt(9);

			return new NameData(name, reducedName, owner, data, registered, updated, isForSale, salePrice, reference, creationGroupId);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch name info from repository", e);
		}
	}

	@Override
	public boolean reducedNameExists(String reducedName) throws DataException {
		try {
			return this.repository.exists("Names", "reduced_name = ?", reducedName);
		} catch (SQLException e) {
			throw new DataException("Unable to check for reduced name in repository", e);
		}
	}

	public List<NameData> searchNames(String query, boolean prefixOnly, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT name, reduced_name, owner, data, registered_when, updated_when, "
				+ "is_for_sale, sale_price, reference, creation_group_id FROM Names "
				+ "WHERE LCASE(name) LIKE ? ORDER BY name");

		// Search anywhere in the name, unless "prefixOnly" has been requested
		// Note that without prefixOnly it will bypass any indexes
		String queryWildcard = prefixOnly ? String.format("%s%%", query.toLowerCase()) : String.format("%%%s%%", query.toLowerCase());
		bindParams.add(queryWildcard);

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<NameData> names = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return names;

			do {
				String name = resultSet.getString(1);
				String reducedName = resultSet.getString(2);
				String owner = resultSet.getString(3);
				String data = resultSet.getString(4);
				long registered = resultSet.getLong(5);

				// Special handling for possibly-NULL "updated" column
				Long updated = resultSet.getLong(6);
				if (updated == 0 && resultSet.wasNull())
					updated = null;

				boolean isForSale = resultSet.getBoolean(7);

				Long salePrice = resultSet.getLong(8);
				if (salePrice == 0 && resultSet.wasNull())
					salePrice = null;

				byte[] reference = resultSet.getBytes(9);
				int creationGroupId = resultSet.getInt(10);

				names.add(new NameData(name, reducedName, owner, data, registered, updated, isForSale, salePrice, reference, creationGroupId));
			} while (resultSet.next());

			return names;
		} catch (SQLException e) {
			throw new DataException("Unable to search names in repository", e);
		}
	}

	@Override
	public List<NameData> getAllNames(Long after, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(256);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT name, reduced_name, owner, data, registered_when, updated_when, "
				+ "is_for_sale, sale_price, reference, creation_group_id FROM Names");

		if (after != null) {
			sql.append(" WHERE registered_when > ? OR updated_when > ?");
			bindParams.add(after);
			bindParams.add(after);
		}

		sql.append(" ORDER BY name");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<NameData> names = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return names;

			do {
				String name = resultSet.getString(1);
				String reducedName = resultSet.getString(2);
				String owner = resultSet.getString(3);
				String data = resultSet.getString(4);
				long registered = resultSet.getLong(5);

				// Special handling for possibly-NULL "updated" column
				Long updated = resultSet.getLong(6);
				if (updated == 0 && resultSet.wasNull())
					updated = null;

				boolean isForSale = resultSet.getBoolean(7);

				Long salePrice = resultSet.getLong(8);
				if (salePrice == 0 && resultSet.wasNull())
					salePrice = null;

				byte[] reference = resultSet.getBytes(9);
				int creationGroupId = resultSet.getInt(10);

				names.add(new NameData(name, reducedName, owner, data, registered, updated, isForSale, salePrice, reference, creationGroupId));
			} while (resultSet.next());

			return names;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch names from repository", e);
		}
	}

	@Override
	public List<NameData> getNamesForSale(Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);

		sql.append("SELECT name, reduced_name, owner, data, registered_when, updated_when, "
				+ "sale_price, reference, creation_group_id  FROM Names WHERE is_for_sale = TRUE ORDER BY name");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<NameData> names = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString())) {
			if (resultSet == null)
				return names;

			do {
				String name = resultSet.getString(1);
				String reducedName = resultSet.getString(2);
				String owner = resultSet.getString(3);
				String data = resultSet.getString(4);
				long registered = resultSet.getLong(5);

				// Special handling for possibly-NULL "updated" column
				Long updated = resultSet.getLong(6);
				if (updated == 0 && resultSet.wasNull())
					updated = null;

				boolean isForSale = true;

				Long salePrice = resultSet.getLong(7);
				if (salePrice == 0 && resultSet.wasNull())
					salePrice = null;

				byte[] reference = resultSet.getBytes(8);
				int creationGroupId = resultSet.getInt(9);

				names.add(new NameData(name, reducedName, owner, data, registered, updated, isForSale, salePrice, reference, creationGroupId));
			} while (resultSet.next());

			return names;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch names from repository", e);
		}
	}

	@Override
	public List<NameData> getNamesByOwner(String owner, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);

		sql.append("SELECT name, reduced_name, data, registered_when, updated_when, "
				+ "is_for_sale, sale_price, reference, creation_group_id FROM Names WHERE owner = ? ORDER BY name");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<NameData> names = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), owner)) {
			if (resultSet == null)
				return names;

			do {
				String name = resultSet.getString(1);
				String reducedName = resultSet.getString(2);
				String data = resultSet.getString(3);
				long registered = resultSet.getLong(4);

				// Special handling for possibly-NULL "updated" column
				Long updated = resultSet.getLong(5);
				if (updated == 0 && resultSet.wasNull())
					updated = null;

				boolean isForSale = resultSet.getBoolean(6);

				Long salePrice = resultSet.getLong(7);
				if (salePrice == 0 && resultSet.wasNull())
					salePrice = null;

				byte[] reference = resultSet.getBytes(8);
				int creationGroupId = resultSet.getInt(9);

				names.add(new NameData(name, reducedName, owner, data, registered, updated, isForSale, salePrice, reference, creationGroupId));
			} while (resultSet.next());

			return names;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account's names from repository", e);
		}
	}

	@Override
	public List<String> getRecentNames(long startTimestamp) throws DataException {
		String sql = "SELECT name FROM RegisterNameTransactions JOIN Names USING (name) "
				+ "JOIN Transactions USING (signature) "
				+ "WHERE created_when >= ?";

		List<String> names = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, startTimestamp)) {
			if (resultSet == null)
				return names;

			do {
				String name = resultSet.getString(1);

				names.add(name);
			} while (resultSet.next());

			return names;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch recent names from repository", e);
		}
	}

	@Override
	public void save(NameData nameData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Names");

		saveHelper.bind("name", nameData.getName()).bind("reduced_name", nameData.getReducedName())
				.bind("owner", nameData.getOwner()).bind("data", nameData.getData())
				.bind("registered_when", nameData.getRegistered()).bind("updated_when", nameData.getUpdated())
				.bind("is_for_sale", nameData.isForSale()).bind("sale_price", nameData.getSalePrice())
				.bind("reference", nameData.getReference()).bind("creation_group_id", nameData.getCreationGroupId());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save name info into repository", e);
		}
	}

	@Override
	public void delete(String name) throws DataException {
		try {
			this.repository.delete("Names", "name = ?", name);
		} catch (SQLException e) {
			throw new DataException("Unable to delete name info from repository", e);
		}
	}

}
