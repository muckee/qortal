package org.qortal.repository.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.model.DatasetStatus;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Class HSQLDBUtils
 */
public class HSQLDBUtils {

    private static final Logger LOGGER = LogManager.getLogger(HSQLDBUtils.class);

    /**
     * Get Row Counts
     *
     * Get row counts for each table in the database
     *
     * @param connection the database
     *
     * @return the table name and current row count paired together for each table
     */
    public static List<DatasetStatus> getRowCounts(Connection connection) {
        List<DatasetStatus> dbTables = new ArrayList<>();

        try {
            // Get the database metadata
            DatabaseMetaData metaData = connection.getMetaData();

            // Retrieve a list of all tables in the database
            ResultSet tables = metaData.getTables(null, null, "%", null);

            // Process each table and get its row count
            while (tables.next()) {

                String tableName = tables.getString(3);

                // skip system tables
                String tableType = tables.getString("TABLE_TYPE");
                if (tableType.equals("SYSTEM TABLE")) continue;

                // Execute a query to count the rows in the table
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName);

                // Get the row count from the ResultSet
                if (resultSet.next()) dbTables.add(new DatasetStatus(tableName, resultSet.getLong(1)));

                // Close the statement
                statement.close();
            }
        } catch (Exception e) {
           LOGGER.error(e.getMessage(), e);
        }

        return dbTables;
    }
}