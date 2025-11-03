package org.bench;
import io.prometheus.client.Histogram;
import io.prometheus.client.Counter;
import io.prometheus.client.exporter.HTTPServer;
import java.io.IOException;

/**
 * Prometheus metrics collection and HTTP server for metric exposition.
 * 
 * <p>This class manages Prometheus metrics and provides an HTTP endpoint for
 * metric scraping. Metrics include:
 * <ul>
 *   <li>{@code bench_db_retrieval_seconds}: Database retrieval latency histogram</li>
 *   <li>{@code bench_processing_seconds}: Processing latency histogram</li>
 *   <li>{@code bench_ops_total}: Total operations counter</li>
 * </ul>
 * 
 * <p>The HTTP server starts on a specified port, and automatically finds an
 * available port if the requested port is already in use (tries up to 100
 * additional ports).
 * 
 * @author krishna.sundar
 * @version 1.0
 */
public class BenchMetrics {
    public static final Histogram dbRetrieval = Histogram.build()
            .name("bench_db_retrieval_seconds").help("DB retrieval latency seconds").register();
    public static final Histogram processing = Histogram.build()
            .name("bench_processing_seconds").help("Processing latency seconds").register();
    public static final Counter ops = Counter.build().name("bench_ops_total").help("Total operations").register();
    private static HTTPServer server;
    
    /**
     * Starts the Prometheus metrics HTTP server on the specified port.
     * 
     * <p>If the requested port is already in use, automatically tries ports
     * up to 100 higher to find an available port. This handles cases where
     * multiple benchmark instances are running simultaneously.
     * 
     * @param port The preferred port number for the HTTP server
     * @throws IOException If no available port is found within the range
     */
    public static void startHttpServer(int port) throws IOException {
        if (server != null) {
            System.out.println("Metrics server already running on port " + server.getPort());
            return;
        }
        
        // Try the requested port first
        try {
            server = new HTTPServer(port);
            System.out.println("Started Prometheus metrics server on port " + port);
            return;
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Address already in use")) {
                System.out.println("Port " + port + " is already in use, trying to find an available port...");
            } else {
                throw e;
            }
        }
        
        // Port is in use, try to find an available port
        for (int tryPort = port + 1; tryPort < port + 100; tryPort++) {
            try {
                server = new HTTPServer(tryPort);
                System.out.println("Started Prometheus metrics server on port " + tryPort + " (original port " + port + " was in use)");
                return;
            } catch (IOException e) {
                // Continue trying next port
            }
        }
        
        throw new IOException("Could not find an available port starting from " + port);
    }
}
