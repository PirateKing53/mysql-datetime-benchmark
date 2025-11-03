package org.bench;

/**
 * Enumeration of supported database types for the benchmark suite.
 * 
 * <p>Used to identify the database system being benchmarked, which enables
 * database-specific SQL dialect handling and optimizations.
 * 
 * @author krishna.sundar
 * @version 1.0
 */
public enum DatabaseType {
    MYSQL,
    POSTGRESQL,
    POSTGRESQL_CITUS;
    
    /**
     * Determines the database type from a JDBC URL.
     * 
     * <p>Parses the URL to detect MySQL or PostgreSQL. For PostgreSQL, checks
     * the {@code db.citus} system property to determine if Citus is enabled.
     * 
     * @param url The JDBC connection URL
     * @return The detected {@code DatabaseType}, defaults to {@code MYSQL} if URL is null
     */
    public static DatabaseType fromUrl(String url) {
        if (url == null) return MYSQL;
        url = url.toLowerCase();
        if (url.contains("postgresql") || url.contains("postgres")) {
            // Check for Citus-specific indicators (could be in URL or environment)
            // For now, assume Citus if explicitly configured
            String citus = System.getProperty("db.citus", "false");
            if ("true".equalsIgnoreCase(citus)) {
                return POSTGRESQL_CITUS;
            }
            return POSTGRESQL;
        }
        return MYSQL;
    }
}

