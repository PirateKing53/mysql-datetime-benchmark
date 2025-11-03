package org.bench;
import java.sql.*;

/**
 * Utility class for executing SQL operations with automatic deadlock retry logic.
 * 
 * <p>This class provides robust error handling for database deadlocks by:
 * <ul>
 *   <li>Detecting deadlock errors (SQLState 40001 or 40xxx)</li>
 *   <li>Automatically rolling back the transaction</li>
 *   <li>Retrying the operation with exponential backoff (50ms delay)</li>
 *   <li>Supporting up to 3 retry attempts</li>
 * </ul>
 * 
 * <p>This is critical for concurrent benchmark workloads where deadlocks can
 * occur due to lock contention, especially in high-concurrency scenarios with
 * multiple threads performing updates or deletes.
 * 
 * @author krishna.sundar
 * @version 1.0
 */
public class DeadlockRetry {
    /**
     * Functional interface for SQL operations that may throw SQLException.
     */
    @FunctionalInterface
    public interface SQLRunnable {
        void run() throws SQLException;
    }
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 50;
    
    /**
     * Executes a SQL operation with automatic deadlock retry logic.
     * 
     * <p>If a deadlock is detected (SQLState 40001 or starting with "40"), the
     * transaction is rolled back and the operation is retried. Up to 3 retry
     * attempts are made with a 50ms delay between attempts.
     * 
     * <p>Non-deadlock SQLExceptions are immediately rethrown without retry.
     * 
     * @param conn The database connection (used for rollback on deadlock)
     * @param sqlOperation The SQL operation to execute (may throw SQLException)
     * @throws SQLException If the operation fails after all retries, or if a non-deadlock exception occurs
     */
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
