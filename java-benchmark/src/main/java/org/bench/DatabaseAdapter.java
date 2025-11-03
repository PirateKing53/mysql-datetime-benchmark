package org.bench;

/**
 * Database adapter to handle SQL dialect differences between MySQL and PostgreSQL/Citus
 */
public class DatabaseAdapter {
    private final DatabaseType dbType;
    
    public DatabaseAdapter(DatabaseType dbType) {
        this.dbType = dbType;
    }
    
    public static DatabaseAdapter fromUrl(String url) {
        return new DatabaseAdapter(DatabaseType.fromUrl(url));
    }
    
    /**
     * Get the primary key column definition
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
     * Get table engine/cluster type for CREATE TABLE
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
     * Convert MySQL FROM_UNIXTIME to PostgreSQL to_timestamp
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
     * Get EXTRACT expression for year extraction
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
     * Check if database supports LIMIT in UPDATE/DELETE
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
     * Convert UPDATE with LIMIT to PostgreSQL-compatible syntax
     * PostgreSQL uses: UPDATE ... WHERE id IN (SELECT id FROM ... LIMIT ?)
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
     * Convert DELETE with LIMIT to PostgreSQL-compatible syntax
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
     * Get database-specific connection properties for HikariCP
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
     * Create Citus-specific table distribution commands
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
     * Create Citus columnar table conversion
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
    
    public DatabaseType getType() {
        return dbType;
    }
}

