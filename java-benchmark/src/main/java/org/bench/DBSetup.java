package org.bench;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
public class DBSetup {
    public static void createTables(DataSource ds) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            String t1 = "CREATE TABLE IF NOT EXISTS bench_common_epoch (id BIGINT AUTO_INCREMENT PRIMARY KEY, cf3 BIGINT NOT NULL, tenant_module_range BIGINT NOT NULL, other_bigint BIGINT, other_decimal DECIMAL(18,4), other_varchar VARCHAR(255), other_blob BLOB, created_at BIGINT, updated_at BIGINT, flag_tiny TINYINT) ENGINE=InnoDB;";
            String t2 = "CREATE TABLE IF NOT EXISTS bench_common_bitpack (id BIGINT AUTO_INCREMENT PRIMARY KEY, cf3 BIGINT NOT NULL, tenant_module_range BIGINT NOT NULL, other_bigint BIGINT, other_decimal DECIMAL(18,4), other_varchar VARCHAR(255), other_blob BLOB, created_at BIGINT, updated_at BIGINT, flag_tiny TINYINT) ENGINE=InnoDB;";
            s.execute(t1);
            s.execute(t2);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    public static void clearTables(DataSource ds) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE TABLE bench_common_epoch");
            s.execute("TRUNCATE TABLE bench_common_bitpack");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
