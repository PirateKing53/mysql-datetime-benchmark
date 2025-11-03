package org.bench;

public enum DatabaseType {
    MYSQL,
    POSTGRESQL,
    POSTGRESQL_CITUS;
    
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

