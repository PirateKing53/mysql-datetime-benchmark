package org.bench;
import java.sql.*;

public class DeadlockRetry {
    @FunctionalInterface
    public interface SQLRunnable {
        void run() throws SQLException;
    }
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 50;
    
    public static void executeWithRetry(Connection conn, SQLRunnable sqlOperation) throws SQLException {
        int retries = MAX_RETRIES;
        
        while (retries-- > 0) {
            try {
                sqlOperation.run();
                return; // Success
            } catch (SQLException e) {
                String sqlState = e.getSQLState();
                // MySQL deadlock error code is 40001
                if (sqlState != null && ("40001".equals(sqlState) || sqlState.startsWith("40"))) {
                    if (retries > 0) {
                        try {
                            conn.rollback();
                            Thread.sleep(RETRY_DELAY_MS);
                            continue;
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new SQLException("Retry interrupted", e);
                        }
                    } else {
                        throw e; // Out of retries
                    }
                } else {
                    throw e; // Not a deadlock, rethrow immediately
                }
            }
        }
    }
}
