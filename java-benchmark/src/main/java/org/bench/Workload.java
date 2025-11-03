package org.bench;

/**
 * Marker interface for benchmark workloads.
 * 
 * <p>All benchmark workloads implement this interface and the {@code Runnable}
 * interface, allowing them to be executed in thread pools. Each workload
 * measures specific database operations and records metrics using HdrHistogram.
 * 
 * <p>Implementations include:
 * <ul>
 *   <li>{@code InsertWorkload}: Batch insert operations</li>
 *   <li>{@code UpdateWorkload}: Range-based update operations</li>
 *   <li>{@code SelectWorkload}: Range query retrieval and processing</li>
 *   <li>{@code ExtractWorkload}: GROUP BY with year extraction</li>
 *   <li>{@code TxnMixedWorkload}: Mixed transactional operations</li>
 *   <li>{@code DeleteWorkload}: Chunked delete operations</li>
 * </ul>
 * 
 * @author krishna.sundar
 * @version 1.0
 */
public interface Workload extends Runnable {}
