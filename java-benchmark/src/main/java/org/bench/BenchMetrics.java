package org.bench;
import io.prometheus.client.Histogram;
import io.prometheus.client.Counter;
import io.prometheus.client.exporter.HTTPServer;
import java.io.IOException;

public class BenchMetrics {
    public static final Histogram dbRetrieval = Histogram.build()
            .name("bench_db_retrieval_seconds").help("DB retrieval latency seconds").register();
    public static final Histogram processing = Histogram.build()
            .name("bench_processing_seconds").help("Processing latency seconds").register();
    public static final Counter ops = Counter.build().name("bench_ops_total").help("Total operations").register();
    private static HTTPServer server;
    
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
