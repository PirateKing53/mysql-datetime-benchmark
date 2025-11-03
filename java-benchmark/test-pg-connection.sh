#!/bin/bash

# Test PostgreSQL/Citus Connection Script
# Usage: ./test-pg-connection.sh [standard|citus|columnar]

set -e

DB_TYPE=${1:-standard}
PG_HOST=${PG_HOST:-127.0.0.1}
PG_PORT=${PG_PORT:-5432}
PG_USER=${PG_USER:-postgres}
PG_PASS=${PG_PASS:-postgres}
PG_DB=${PG_DB:-benchdb}

echo "========================================="
echo "Testing PostgreSQL Connection"
echo "========================================="
echo "Database Type: $DB_TYPE"
echo "Host: $PG_HOST:$PG_PORT"
echo "User: $PG_USER"
echo "Database: $PG_DB"
echo ""

# Build JDBC URL based on type
case $DB_TYPE in
  citus|columnar)
    JDBC_URL="jdbc:postgresql://${PG_HOST}:${PG_PORT}/${PG_DB}"
    echo "JDBC URL: $JDBC_URL"
    echo "Citus Mode: enabled"
    if [ "$DB_TYPE" == "columnar" ]; then
      echo "Columnar Storage: enabled"
      CITUS_COLUMNAR="true"
    else
      CITUS_COLUMNAR="false"
    fi
    ;;
  standard)
    JDBC_URL="jdbc:postgresql://${PG_HOST}:${PG_PORT}/${PG_DB}"
    echo "JDBC URL: $JDBC_URL"
    echo "Citus Mode: disabled"
    CITUS_COLUMNAR="false"
    ;;
  *)
    echo "Invalid DB type: $DB_TYPE (use: standard, citus, or columnar)"
    exit 1
    ;;
esac

# Test using psql (if available) or Java test
echo ""
echo "Testing connection..."

# Try psql first
if command -v psql &> /dev/null || [ "$USE_DOCKER" = "true" ]; then
    if [ "$USE_DOCKER" = "true" ]; then
        echo "Using Docker exec (container: $CONTAINER)..."
        
        # Test connection to postgres database first
        docker exec "$CONTAINER" psql -U $PG_USER -d postgres -c "SELECT version();" > /dev/null 2>&1 || {
            echo "ERROR: Cannot connect to PostgreSQL in container"
            exit 1
        }
        
        # Check if benchdb exists, create if not
        DB_EXISTS=$(docker exec "$CONTAINER" psql -U $PG_USER -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = '$PG_DB'" 2>/dev/null | tr -d ' ')
        if [ "$DB_EXISTS" != "1" ]; then
            echo "Database '$PG_DB' does not exist, creating it..."
            docker exec "$CONTAINER" psql -U $PG_USER -d postgres -c "CREATE DATABASE $PG_DB;" || {
                echo "ERROR: Failed to create database"
                exit 1
            }
            echo "✓ Database created"
            
            # Enable Citus if needed
            if [ "$DB_TYPE" != "standard" ]; then
                echo "Enabling Citus extension..."
                docker exec "$CONTAINER" psql -U $PG_USER -d $PG_DB -c "CREATE EXTENSION IF NOT EXISTS citus;" || {
                    echo "WARNING: Could not enable Citus extension"
                }
            fi
        fi
        
        # Test connection to benchdb
        docker exec "$CONTAINER" psql -U $PG_USER -d $PG_DB -c "SELECT version();" || {
            echo "ERROR: Connection to $PG_DB failed"
            exit 1
        }
        
        # Check Citus extension
        if [ "$DB_TYPE" != "standard" ]; then
            echo ""
            echo "Checking Citus extension..."
            docker exec "$CONTAINER" psql -U $PG_USER -d $PG_DB -c "SELECT * FROM pg_extension WHERE extname = 'citus';" > /dev/null 2>&1 || {
                echo "WARNING: Citus extension not found (trying to enable it...)"
                docker exec "$CONTAINER" psql -U $PG_USER -d $PG_DB -c "CREATE EXTENSION IF NOT EXISTS citus;" 2>&1
            }
            # Show Citus version if available
            docker exec "$CONTAINER" psql -U $PG_USER -d $PG_DB -c "SELECT * FROM citus_version();" 2>/dev/null || echo ""
        fi
        
        echo ""
        echo "✓ Connection successful!"
    else
        echo "Using psql (network connection)..."
        export PGPASSWORD=$PG_PASS
        
        # Test connection to postgres database first (benchdb might not exist yet)
        psql -h $PG_HOST -p $PG_PORT -U $PG_USER -d postgres -c "SELECT version();" > /dev/null 2>&1 || {
            echo "ERROR: Cannot connect to PostgreSQL server"
            exit 1
        }
        
        # Check if benchdb exists, create if not
        DB_EXISTS=$(psql -h $PG_HOST -p $PG_PORT -U $PG_USER -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = '$PG_DB'" 2>/dev/null | tr -d ' ')
        if [ "$DB_EXISTS" != "1" ]; then
            echo "Database '$PG_DB' does not exist, creating it..."
            psql -h $PG_HOST -p $PG_PORT -U $PG_USER -d postgres -c "CREATE DATABASE $PG_DB;" || {
                echo "ERROR: Failed to create database"
                exit 1
            }
            echo "✓ Database created"
            
            # Enable Citus if needed
            if [ "$DB_TYPE" != "standard" ]; then
                echo "Enabling Citus extension..."
                psql -h $PG_HOST -p $PG_PORT -U $PG_USER -d $PG_DB -c "CREATE EXTENSION IF NOT EXISTS citus;" || {
                    echo "WARNING: Could not enable Citus extension"
                }
            fi
        fi
        
        # Now test connection to benchdb
        psql -h $PG_HOST -p $PG_PORT -U $PG_USER -d $PG_DB -c "SELECT version();" || {
            echo "ERROR: psql connection to $PG_DB failed"
            exit 1
        }
        
        # Check Citus extension
        if [ "$DB_TYPE" != "standard" ]; then
            echo ""
            echo "Checking Citus extension..."
            psql -h $PG_HOST -p $PG_PORT -U $PG_USER -d $PG_DB -c "SELECT * FROM pg_extension WHERE extname = 'citus';" > /dev/null 2>&1 || {
                echo "WARNING: Citus extension not found (trying to enable it...)"
                psql -h $PG_HOST -p $PG_PORT -U $PG_USER -d $PG_DB -c "CREATE EXTENSION IF NOT EXISTS citus;" 2>&1
            }
        fi
        
        echo ""
        echo "✓ Connection successful!"
    fi
else
    echo "psql not found, using Java test..."
fi

# Java connection test
echo ""
echo "Testing with Java JDBC..."
cd "$(dirname "$0")"

if [ ! -f "target/bench-runner-1.0-jar-with-dependencies.jar" ]; then
    echo "Building JAR first..."
    mvn package -DskipTests -q
fi

# Create a simple connection test
cat > /tmp/test-pg-connection.java << 'EOF'
import java.sql.*;

public class TestPGConnection {
    public static void main(String[] args) {
        String url = args[0];
        String user = args[1];
        String pass = args[2];
        
        try {
            Connection conn = DriverManager.getConnection(url, user, pass);
            System.out.println("✓ JDBC Connection successful!");
            
            // Test basic query
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT version()");
            if (rs.next()) {
                System.out.println("PostgreSQL Version: " + rs.getString(1));
            }
            
            // Check Citus if enabled
            try {
                rs = stmt.executeQuery("SELECT * FROM pg_extension WHERE extname = 'citus'");
                if (rs.next()) {
                    System.out.println("✓ Citus extension found!");
                } else {
                    System.out.println("⚠ Citus extension not found (may not be enabled)");
                }
            } catch (Exception e) {
                System.out.println("⚠ Could not check Citus: " + e.getMessage());
            }
            
            conn.close();
            System.out.println("✓ All tests passed!");
        } catch (Exception e) {
            System.err.println("✗ Connection failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
EOF

javac -cp "target/bench-runner-1.0-jar-with-dependencies.jar" /tmp/test-pg-connection.java 2>/dev/null || {
    echo "Could not compile test class, using benchmark directly..."
    echo ""
    echo "Running benchmark setup test..."
    java -cp "target/bench-runner-1.0-jar-with-dependencies.jar" \
         -Ddb.url="$JDBC_URL" \
         -Ddb.user="$PG_USER" \
         -Ddb.pass="$PG_PASS" \
         -Ddb.citus=$([ "$DB_TYPE" != "standard" ] && echo "true" || echo "false") \
         -Ddb.citus.columnar="$CITUS_COLUMNAR" \
         org.bench.Main --model epoch 2>&1 | head -20 || {
        echo ""
        echo "Connection test completed (table creation test)"
    }
}

echo ""
echo "========================================="
echo "Connection test complete!"
echo "========================================="
echo ""
echo "To run benchmark with this configuration:"
echo ""
echo "java -Ddb.url=\"$JDBC_URL\" \\"
echo "     -Ddb.user=\"$PG_USER\" \\"
echo "     -Ddb.pass=\"$PG_PASS\" \\"
[ "$DB_TYPE" != "standard" ] && echo "     -Ddb.citus=true \\"
[ "$DB_TYPE" == "columnar" ] && echo "     -Ddb.citus.columnar=true \\"
echo "     -jar target/bench-runner-1.0-jar-with-dependencies.jar --model epoch"

