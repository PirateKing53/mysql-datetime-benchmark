package org.bench;

/**
 * Database adapter to handle SQL dialect differences between MySQL and PostgreSQL/Citus.
 * 
 * <p>This class provides a unified interface for database-specific SQL generation,
 * abstracting away differences in:
 * <ul>
 *   <li>Primary key definitions (AUTO_INCREMENT vs BIGSERIAL)</li>
 *   <li>Table engines (ENGINE=InnoDB vs none)</li>
 *   <li>Date/time functions (FROM_UNIXTIME vs to_timestamp)</li>
 *   <li>LIMIT clauses in UPDATE/DELETE statements</li>
 *   <li>Bitwise operations (hex literals in MySQL vs decimal in PostgreSQL)</li>
 *   <li>Connection pool properties</li>
 *   <li>Citus-specific table distribution and columnar storage</li>
 * </ul>
 * 
 * <p>All SQL generation methods automatically adapt their output based on the
 * detected database type, allowing the benchmark workloads to remain database-agnostic.
 * 
 * @author krishna.sundar
 * @version 1.0
 */
public class DatabaseAdapter {
    private final DatabaseType dbType;
    
    /**
     * Creates a new database adapter for the specified database type.
     * 
     * @param dbType The database type to create an adapter for
     */
    public DatabaseAdapter(DatabaseType dbType) {
        this.dbType = dbType;
    }
    
    /**
     * Creates a database adapter from a JDBC URL.
     * 
     * @param url The JDBC connection URL
     * @return A new {@code DatabaseAdapter} instance for the detected database type
     */
    public static DatabaseAdapter fromUrl(String url) {
        return new DatabaseAdapter(DatabaseType.fromUrl(url));
    }
    
    /**
     * Gets the SQL definition for a primary key column.
     * 
     * <p>Returns database-specific syntax:
     * <ul>
     *   <li>MySQL: {@code id BIGINT AUTO_INCREMENT PRIMARY KEY}</li>
     *   <li>PostgreSQL: {@code id BIGSERIAL PRIMARY KEY}</li>
     * </ul>
     * 
     * @return The primary key column definition SQL fragment
     */
    public String getPrimaryKeyDef() {
        switch (dbType) {
            case MYSQL:
                return "id BIGINT AUTO_INCREMENT PRIMARY KEY";
            case POSTGRESQL:
            case POSTGRESQL_CITUS:
                return "id BIGSERIAL PRIMARY KEY";
            default:
                return "id BIGINT AUTO_INCREMENT PRIMARY KEY";
        }
    }
    
    /**
     * Gets the table engine/cluster type clause for CREATE TABLE statements.
     * 
     * <p>Returns:
     * <ul>
     *   <li>MySQL: {@code ENGINE=InnoDB}</li>
     *   <li>PostgreSQL: Empty string (no engine clause)</li>
     * </ul>
     * 
     * @return The table engine SQL clause, or empty string if not applicable
     */
    public String getTableEngine() {
        switch (dbType) {
            case MYSQL:
                return " ENGINE=InnoDB";
            case POSTGRESQL:
                return ""; // PostgreSQL doesn't use ENGINE clause
            case POSTGRESQL_CITUS:
                return ""; // Citus handles this differently
            default:
                return "";
        }
    }
    
    /**
     * Converts a MySQL FROM_UNIXTIME expression to PostgreSQL to_timestamp.
     * 
     * <p>MySQL uses {@code FROM_UNIXTIME(column/1000)} while PostgreSQL uses
     * {@code to_timestamp(column::numeric / 1000)}. This method handles the
     * conversion automatically.
     * 
     * @param columnExpr The column expression (may already include /1000 division)
     * @return Database-appropriate timestamp conversion function call
     */
    public String getUnixTimeToTimestamp(String columnExpr) {
        switch (dbType) {
            case MYSQL:
                return "FROM_UNIXTIME(" + columnExpr + ")";
            case POSTGRESQL:
            case POSTGRESQL_CITUS:
                // PostgreSQL: to_timestamp expects seconds, not milliseconds
                // If columnExpr is already divided by 1000, just use it
                // If not, we need to divide: to_timestamp(cf3::numeric / 1000)
                if (columnExpr.contains("/1000")) {
                    // Already divided, extract the column name
                    String col = columnExpr.replace("/1000", "").trim();
                    return "to_timestamp(" + col + "::numeric / 1000)";
                } else {
                    return "to_timestamp(" + columnExpr + "::numeric / 1000)";
                }
            default:
                return "FROM_UNIXTIME(" + columnExpr + ")";
        }
    }
    
    /**
     * Gets the SQL expression for extracting the year from a datetime column.
     * 
     * <p>For epoch storage:
     * <ul>
     *   <li>MySQL: {@code EXTRACT(YEAR FROM FROM_UNIXTIME(column/1000))}</li>
     *   <li>PostgreSQL: {@code EXTRACT(YEAR FROM to_timestamp(column::numeric / 1000))}</li>
     * </ul>
     * 
     * <p>For bitpack storage:
     * <ul>
     *   <li>MySQL: {@code ((column >> 35) & 0x7FF) + 2000}</li>
     *   <li>PostgreSQL: {@code ((column >> 35) & 2047) + 2000} (decimal instead of hex)</li>
     * </ul>
     * 
     * @param columnExpr The column name or expression (e.g., "cf3")
     * @param useBitpack If true, use bitwise extraction; if false, use date function extraction
     * @return SQL expression for year extraction
     */
    public String getYearExtract(String columnExpr, boolean useBitpack) {
        if (useBitpack) {
            // Bitpack extraction: use decimal for PostgreSQL compatibility
            // 0x7FF = 2047 in decimal
            // PostgreSQL doesn't always parse 0x7FF correctly, use decimal
            switch (dbType) {
                case MYSQL:
                    // MySQL accepts hex literals
                    return "((cf3 >> 35) & 0x7FF) + 2000";
                case POSTGRESQL:
                case POSTGRESQL_CITUS:
                    // PostgreSQL: use decimal literal (2047 = 0x7FF)
                    return "((cf3 >> 35) & 2047) + 2000";
                default:
                    return "((cf3 >> 35) & 2047) + 2000";
            }
        } else {
            // Epoch extraction needs database-specific handling
            switch (dbType) {
                case MYSQL:
                    return "EXTRACT(YEAR FROM FROM_UNIXTIME(" + columnExpr + "/1000))";
                case POSTGRESQL:
                case POSTGRESQL_CITUS:
                    return "EXTRACT(YEAR FROM to_timestamp(" + columnExpr + "::numeric / 1000))";
                default:
                    return "EXTRACT(YEAR FROM FROM_UNIXTIME(" + columnExpr + "/1000))";
            }
        }
    }
    
    /**
     * Checks if the database supports LIMIT clauses in UPDATE and DELETE statements.
     * 
     * <p>MySQL supports direct LIMIT in UPDATE/DELETE, while PostgreSQL requires
     * a subquery with LIMIT.
     * 
     * @return True if LIMIT is supported directly, false if subquery is required
     */
    public boolean supportsLimitInUpdateDelete() {
        switch (dbType) {
            case MYSQL:
                return true;
            case POSTGRESQL:
            case POSTGRESQL_CITUS:
                return false; // PostgreSQL doesn't support LIMIT in UPDATE/DELETE directly
            default:
                return true;
        }
    }
    
    /**
     * Converts an UPDATE statement with LIMIT to database-compatible syntax.
     * 
     * <p>MySQL: Adds LIMIT directly to the UPDATE statement.
     * PostgreSQL: Wraps the WHERE clause in a subquery with LIMIT.
     * 
     * @param baseUpdate The base UPDATE statement (without LIMIT)
     * @param table The table name
     * @param whereClause The WHERE clause condition
     * @param limit The limit value (unused in method signature, but kept for clarity)
     * @return Database-compatible UPDATE statement with LIMIT
     */
    public String convertUpdateWithLimit(String baseUpdate, String table, String whereClause, int limit) {
        if (supportsLimitInUpdateDelete()) {
            return baseUpdate + " LIMIT ?";
        } else {
            // PostgreSQL: Use subquery with LIMIT
            return baseUpdate.replace("WHERE " + whereClause, 
                "WHERE id IN (SELECT id FROM " + table + " WHERE " + whereClause + " LIMIT ?)");
        }
    }
    
    /**
     * Converts a DELETE statement with LIMIT to database-compatible syntax.
     * 
     * <p>MySQL: Adds LIMIT directly to the DELETE statement.
     * PostgreSQL: Wraps the WHERE clause in a subquery with LIMIT.
     * 
     * @param baseDelete The base DELETE statement (without LIMIT)
     * @param table The table name
     * @param whereClause The WHERE clause condition
     * @param limit The limit value (unused in method signature, but kept for clarity)
     * @return Database-compatible DELETE statement with LIMIT
     */
    public String convertDeleteWithLimit(String baseDelete, String table, String whereClause, int limit) {
        if (supportsLimitInUpdateDelete()) {
            return baseDelete + " LIMIT ?";
        } else {
            // PostgreSQL: Use subquery with LIMIT
            return baseDelete.replace("FROM " + table, 
                "FROM " + table + " WHERE id IN (SELECT id FROM " + table + " WHERE " + whereClause + " LIMIT ?)");
        }
    }
    
    /**
     * Configures HikariCP connection pool properties based on the database type.
     * 
     * <p>MySQL optimizations:
     * <ul>
     *   <li>Prepared statement caching (cachePrepStmts, prepStmtCacheSize)</li>
     *   <li>Batch statement rewriting (rewriteBatchedStatements)</li>
     *   <li>Server-side prepared statements (useServerPrepStmts)</li>
     * </ul>
     * 
     * <p>PostgreSQL optimizations:
     * <ul>
     *   <li>Prepared statement caching (preparedStatementCacheQueries, preparedStatementCacheSizeMiB)</li>
     *   <li>Batch insert rewriting (reWriteBatchedInserts)</li>
     * </ul>
     * 
     * @param config The HikariCP configuration to modify
     */
    public void configureConnectionProperties(com.zaxxer.hikari.HikariConfig config) {
        switch (dbType) {
            case MYSQL:
                config.addDataSourceProperty("cachePrepStmts", "true");
                config.addDataSourceProperty("prepStmtCacheSize", "250");
                config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                config.addDataSourceProperty("rewriteBatchedStatements", "true");
                config.addDataSourceProperty("useServerPrepStmts", "true");
                break;
            case POSTGRESQL:
            case POSTGRESQL_CITUS:
                // PostgreSQL-specific optimizations
                config.addDataSourceProperty("preparedStatementCacheQueries", "250");
                config.addDataSourceProperty("preparedStatementCacheSizeMiB", "5");
                config.addDataSourceProperty("reWriteBatchedInserts", "true");
                break;
        }
    }
    
    /**
     * Generates SQL command to distribute a table in Citus.
     * 
     * <p>Returns a {@code SELECT create_distributed_table(...)} statement that
     * distributes the table by the {@code tenant_module_range} column. For
     * single-node Citus setups, this may fail gracefully - tables will still
     * function correctly without distribution.
     * 
     * @param tableName The name of the table to distribute
     * @return SQL command for table distribution, or null if not a Citus database
     */
    public String getCitusDistributionSQL(String tableName) {
        if (dbType == DatabaseType.POSTGRESQL_CITUS) {
            // Distribute by tenant_module_range for Citus
            // For single-node Citus, distribution may not work - that's okay, tables still function
            // Use direct function call without explicit casts - let PostgreSQL infer types
            // If this fails, tables will still work (just not distributed)
            return "SELECT create_distributed_table('" + tableName + "', 'tenant_module_range');";
        }
        return null; // Not a Citus table
    }
    
    /**
     * Generates SQL command to convert a table to columnar storage in Citus.
     * 
     * <p>Returns a columnar conversion command only if:
     * <ul>
     *   <li>The database is Citus-enabled</li>
     *   <li>The {@code db.citus.columnar} system property is set to "true"</li>
     * </ul>
     * 
     * @param tableName The name of the table to convert
     * @return SQL command for columnar conversion, or null if not applicable
     */
    public String getCitusColumnarSQL(String tableName) {
        if (dbType == DatabaseType.POSTGRESQL_CITUS) {
            // Convert to columnar storage (requires Citus columnar extension)
            String citusColumnar = System.getProperty("db.citus.columnar", "false");
            if ("true".equalsIgnoreCase(citusColumnar)) {
                return "SELECT alter_table_set_access_method('" + tableName + "', 'columnar');";
            }
        }
        return null;
    }
    
    /**
     * Gets the database type this adapter is configured for.
     * 
     * @return The {@code DatabaseType} enum value
     */
    public DatabaseType getType() {
        return dbType;
    }
}

