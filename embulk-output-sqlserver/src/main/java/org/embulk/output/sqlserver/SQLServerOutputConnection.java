package org.embulk.output.sqlserver;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.embulk.output.jdbc.JdbcColumn;
import org.embulk.output.jdbc.JdbcOutputConnection;
import org.embulk.output.jdbc.JdbcSchema;
import org.embulk.output.jdbc.MergeConfig;
import org.embulk.output.jdbc.TableIdentifier;

public class SQLServerOutputConnection
        extends JdbcOutputConnection
{
    private final Product product;

    public SQLServerOutputConnection(Connection connection, String schemaName, Product product)
            throws SQLException
    {
        super(connection, schemaName);
        this.product = product;
    }

    @Override
    protected String buildRenameTableSql(TableIdentifier fromTable, TableIdentifier toTable)
    {
        // https://learn.microsoft.com/en-us/sql/relational-databases/system-stored-procedures/sp-rename-transact-sql?view=azure-sqldw-latest#remarks
        // In Azure Synapse Analytics, sp_rename is in Preview for dedicated SQL pools and can only be used to rename a 'COLUMN' in a user object.
        if (Product.AZURE_SYNAPSE_ANALYTICS.equals(product)) {
            return buildRenameTableSqlForAzureSynapseAnalytics(fromTable, toTable);
        }
        // sp_rename cannot change schema of table
        StringBuilder sb = new StringBuilder();
        sb.append("EXEC sp_rename ");
        if (fromTable.getSchemaName() == null) {
            sb.append(quoteIdentifierString(fromTable.getTableName()));
        } else {
            sb.append(quoteIdentifierString(fromTable.getSchemaName() + "." + fromTable.getTableName()));
        }
        sb.append(", ");
        sb.append(quoteIdentifierString(toTable.getTableName()));
        sb.append(", 'OBJECT'");
        return sb.toString();
    }

    private String buildRenameTableSqlForAzureSynapseAnalytics(TableIdentifier fromTable, TableIdentifier toTable)
    {
        // https://learn.microsoft.com/en-us/sql/t-sql/statements/rename-transact-sql?view=azure-sqldw-latest#rename-object---database_name---schema_name------schema_name----table_name-to-new_table_name
        return "RENAME OBJECT " + quoteTableIdentifier(fromTable) + " TO " + quoteIdentifierString(toTable.getTableName());
    }

    @Override
    protected String buildColumnTypeName(JdbcColumn c)
    {
        switch(c.getSimpleTypeName()) {
        case "BOOLEAN":
            return "BIT";
        case "CLOB":
            // https://learn.microsoft.com/en-us/azure/synapse-analytics/sql/develop-tables-data-types#minimize-row-length
            // https://learn.microsoft.com/en-us/azure/synapse-analytics/sql/develop-tables-data-types#unsupported-data-types
            // Workarounds for unsupported data types
            if(Product.AZURE_SYNAPSE_ANALYTICS.equals(product)) {
                return "NVARCHAR(4000)";
            }
            return "TEXT";
        case "TIMESTAMP":
            return "DATETIME2";
        case "NVARCHAR":
            if(c.getSizeTypeParameter() > 4000) {
                return "NVARCHAR(max)";
            }
        case "VARCHAR":
            if(c.getSizeTypeParameter() > 8000) {
                return "VARCHAR(max)";
            }

        default:
            return super.buildColumnTypeName(c);
        }
    }

    @Override
    protected void setSearchPath(String schema) throws SQLException
    {
        // NOP
    }

    @Override
    protected boolean supportsTableIfExistsClause()
    {
        return false;
    }

    private static final String[] SIMPLE_TYPE_NAMES = {
        "BIT", "FLOAT",
    };

    @Override
    protected ColumnDeclareType getColumnDeclareType(String convertedTypeName, JdbcColumn col)
    {
        if (Arrays.asList(SIMPLE_TYPE_NAMES).contains(convertedTypeName)) {
            return ColumnDeclareType.SIMPLE;
        }
        return super.getColumnDeclareType(convertedTypeName, col);
    }

    @Override
    protected String buildCollectMergeSql(List<TableIdentifier> fromTables, JdbcSchema schema, TableIdentifier toTable, MergeConfig mergeConfig) throws SQLException
    {
        StringBuilder sb = new StringBuilder();

        sb.append("MERGE INTO ");
        sb.append(quoteTableIdentifier(toTable));
        sb.append(" AS T");
        sb.append(" USING (");
        for (int i = 0; i < fromTables.size(); i++) {
            if (i != 0) { sb.append(" UNION ALL "); }
            sb.append(" SELECT ");
            sb.append(buildColumns(schema, ""));
            sb.append(" FROM ");
            sb.append(quoteTableIdentifier(fromTables.get(i)));
        }
        sb.append(") AS S");
        sb.append(" ON (");
        for (int i = 0; i < mergeConfig.getMergeKeys().size(); i++) {
            if (i != 0) { sb.append(" AND "); }
            String mergeKey = quoteIdentifierString(mergeConfig.getMergeKeys().get(i));
            sb.append("T.");
            sb.append(mergeKey);
            sb.append(" = S.");
            sb.append(mergeKey);
        }
        sb.append(")");
        sb.append(" WHEN MATCHED THEN");
        sb.append(" UPDATE SET ");
        if (mergeConfig.getMergeRule().isPresent()) {
            for (int i = 0; i < mergeConfig.getMergeRule().get().size(); i++) {
                if (i != 0) { sb.append(", "); }
                sb.append(mergeConfig.getMergeRule().get().get(i));
            }
        } else {
            for (int i = 0; i < schema.getCount(); i++) {
                if (i != 0) { sb.append(", "); }
                String column = quoteIdentifierString(schema.getColumnName(i));
                sb.append(column);
                sb.append(" = S.");
                sb.append(column);
            }
        }
        sb.append(" WHEN NOT MATCHED THEN");
        sb.append(" INSERT (");
        sb.append(buildColumns(schema, ""));
        sb.append(") VALUES (");
        sb.append(buildColumns(schema, "S."));
        sb.append(");");

        return sb.toString();
    }

    private String buildColumns(JdbcSchema schema, String prefix)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < schema.getCount(); i++) {
            if (i != 0) { sb.append(", "); }
            sb.append(prefix);
            sb.append(quoteIdentifierString(schema.getColumnName(i)));
        }
        return sb.toString();
    }

    @FunctionalInterface
    private interface DDL
    {
        void execute() throws SQLException;
    }

    private void executeDDLInAutoCommit(DDL ddl) throws SQLException
    {
        // https://learn.microsoft.com/en-us/azure/synapse-analytics/sql-data-warehouse/sql-data-warehouse-develop-transactions#limitations
        // No support for DDL such as CREATE TABLE inside a user-defined transaction
        boolean autoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(true);
            ddl.execute();
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    @Override
    public void createTable(TableIdentifier table, JdbcSchema schema, Optional<String> tableConstraint, Optional<String> tableOption) throws SQLException
    {
        if (Product.AZURE_SYNAPSE_ANALYTICS.equals(product)) {
            executeDDLInAutoCommit(() -> super.createTable(table, schema, tableConstraint, tableOption));
        } else {
            super.createTable(table, schema, tableConstraint, tableOption);
        }
    }

    @Override
    public void replaceTable(TableIdentifier fromTable, JdbcSchema schema, TableIdentifier toTable, Optional<String> postSql) throws SQLException
    {
        if (Product.AZURE_SYNAPSE_ANALYTICS.equals(product)) {
            executeDDLInAutoCommit(() -> super.replaceTable(fromTable, schema, toTable, postSql));
        } else {
            super.replaceTable(fromTable, schema, toTable, postSql);
        }
    }

    @Override
    protected void dropTable(Statement stmt, TableIdentifier table) throws SQLException
    {
        if (Product.AZURE_SYNAPSE_ANALYTICS.equals(product)) {
            executeDDLInAutoCommit(() -> super.dropTable(stmt, table));
        } else {
            super.dropTable(stmt, table);
        }
    }
}
