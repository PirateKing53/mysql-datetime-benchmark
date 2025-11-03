package org.bench;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

public class DBSetup {
    private static final DatabaseAdapter adapter = Config.DB_ADAPTER;
    
    public static void createTables(DataSource ds) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            // Common column definitions
            String commonCols = "cf3 BIGINT NOT NULL, tenant_module_range BIGINT NOT NULL, " +
                               "other_bigint BIGINT, other_decimal DECIMAL(18,4), " +
                               "other_varchar VARCHAR(255), other_blob BYTEA, " +
                               "created_at BIGINT, updated_at BIGINT, flag_tiny SMALLINT";
            
            // PostgreSQL uses SMALLINT instead of TINYINT, BYTEA instead of BLOB
            if (adapter.getType() == DatabaseType.MYSQL) {
                commonCols = commonCols.replace("BYTEA", "BLOB").replace("SMALLINT", "TINYINT");
            }
            
            String pkDef = adapter.getPrimaryKeyDef();
            String engine = adapter.getTableEngine();
            
            // Create epoch table
            String t1 = "CREATE TABLE IF NOT EXISTS bench_common_epoch (" +
                       pkDef + ", " + commonCols + ")" + engine + ";";
            
            // Create bitpack table
            String t2 = "CREATE TABLE IF NOT EXISTS bench_common_bitpack (" +
                       pkDef + ", " + commonCols + ")" + engine + ";";
            
            s.execute(t1);
            s.execute(t2);
            
            // For Citus: distribute tables and optionally convert to columnar
            if (adapter.getType() == DatabaseType.POSTGRESQL_CITUS) {
                try {
                    // Distribute tables by tenant_module_range for Citus
                    String dist1 = adapter.getCitusDistributionSQL("bench_common_epoch");
                    String dist2 = adapter.getCitusDistributionSQL("bench_common_bitpack");
                    if (dist1 != null) {
                        s.execute(dist1);
                        System.out.println("Distributed bench_common_epoch table for Citus");
                    }
                    if (dist2 != null) {
                        s.execute(dist2);
                        System.out.println("Distributed bench_common_bitpack table for Citus");
                    }
                    
                    // Optionally convert to columnar storage
                    String col1 = adapter.getCitusColumnarSQL("bench_common_epoch");
                    String col2 = adapter.getCitusColumnarSQL("bench_common_bitpack");
                    if (col1 != null) {
                        s.execute(col1);
                        System.out.println("Converted bench_common_epoch to columnar storage");
                    }
                    if (col2 != null) {
                        s.execute(col2);
                        System.out.println("Converted bench_common_bitpack to columnar storage");
                    }
                } catch (Exception e) {
                    System.err.println("Warning: Citus-specific setup failed (may not be Citus-enabled): " + e.getMessage());
                    // Continue - table creation succeeded
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    public static void clearTables(DataSource ds) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            // PostgreSQL uses TRUNCATE, but for Citus distributed tables, may need DELETE
            if (adapter.getType() == DatabaseType.POSTGRESQL_CITUS) {
                // For distributed tables, TRUNCATE might not work, use DELETE
                try {
                    s.execute("TRUNCATE TABLE bench_common_epoch");
                    s.execute("TRUNCATE TABLE bench_common_bitpack");
                } catch (Exception e) {
                    // Fallback to DELETE for distributed tables
                    s.execute("DELETE FROM bench_common_epoch");
                    s.execute("DELETE FROM bench_common_bitpack");
                }
            } else {
                s.execute("TRUNCATE TABLE bench_common_epoch");
                s.execute("TRUNCATE TABLE bench_common_bitpack");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
