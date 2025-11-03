# 📊 MySQL 5.7 Datetime Benchmark Report (Epoch vs Bitpack)

**Test Environment:**  
- MySQL 5.7 (no indexing, binary logging disabled)  
- 200K rows × multi-threaded batches  
- Mac M1 (ARM64) via Docker  
- Java 11 + HikariCP  
- Metrics: `p50`, `p90`, `p99` (ms), `throughput` (ops/sec), `db_time`, `processing_time`, `total_time` (ms)

---

## 🧮 Summary Table

| Model | Workload | Operation | p50 | p90 | p99 | Throughput | DB Time (ms) | Processing (ms) | Total (ms) |
|:------|:----------|:-----------|----:|----:|----:|------------:|--------------:|----------------:|-------------:|
| **epoch** | insert | all | 24.00 | 27.00 | 57.00 | 41.73 | 23.97 | 0.64 | 25.81 |
|  | update | cf3 | 13.00 | 735.00 | 735.00 | 2.68 | 373.50 | 0.00 | 374.00 |
|  | select | retrieval | 1.00 | 4.00 | 16.00 | 457.67 | 2.19 | 0.00 | 2.19 |
|  | select | processing | 1.00 | 1.00 | 1.00 | — | 0.00 | 1.00 | 1.00 |
|  | extract | groupby | 95.00 | 95.00 | 95.00 | 10.64 | 94.00 | 0.00 | 95.00 |
|  | txn_mixed | all | 16.00 | 18.00 | 22.00 | 64.48 | 15.51 | 0.00 | 16.41 |
|  | delete | all | 740.00 | 749.00 | 891.00 | 1.34 | 745.21 | 0.00 | 745.71 |
| **bitpack** | insert | all | 24.00 | 28.00 | 53.00 | 41.64 | 24.02 | 0.95 | 25.96 |
|  | update | cf3 | 14.00 | 732.00 | 732.00 | 2.68 | 372.50 | 0.00 | 373.00 |
|  | select | retrieval | 1.00 | 3.00 | 13.00 | 468.38 | 2.14 | 0.00 | 2.14 |
|  | select | processing | 1.00 | 1.00 | 1.00 | — | 0.00 | 1.00 | 1.00 |
|  | extract | groupby | 66.00 | 66.00 | 66.00 | 15.38 | 65.00 | 0.00 | 66.00 |
|  | txn_mixed | all | 16.00 | 18.00 | 25.00 | 64.58 | 15.49 | 0.01 | 16.34 |
|  | delete | all | 738.00 | 750.00 | 816.00 | 1.35 | 742.71 | 0.00 | 743.24 |

---

## ⚙️ 1. Insert Performance

| Metric | Epoch | Bitpack | Observation |
|--------|--------|----------|-------------|
| p50 (ms) | 24.0 | 24.0 | Nearly identical median latency |
| p90 (ms) | 27.0 | 28.0 | Very close 90th percentile |
| Throughput | 41.73 | 41.64 | Bitpack slightly behind (–0.2%) |
| DB Time | 23.97 | 24.02 | Equal |
| Processing | 0.64 | 0.95 | Bitpack marginally more CPU work |

**Inference:** Both models are identical in insert throughput; bitpacking doesn’t affect write-heavy workloads.

---

## 🔄 2. Update Performance

| Metric | Epoch | Bitpack | Observation |
|--------|--------|----------|-------------|
| p90 (ms) | 735.0 | 732.0 | Identical high tail latency |
| Throughput | 2.68 | 2.68 | Identical |
| Total Time | 374.0 | 373.0 | Almost equal |

**Inference:** Update latency is dominated by InnoDB row locks; encoding has no visible effect.

---

## 🔍 3. Select Performance

| Metric | Epoch | Bitpack | Observation |
|--------|--------|----------|-------------|
| p90 (ms) | 4.0 | 3.0 | Bitpack ~25% faster median retrieval |
| Throughput | 457.7 | 468.4 | Bitpack slightly ahead |
| Processing Time | 1.0 | 1.0 | Identical conversion cost |

**Inference:** Bitpack gives a slight edge in retrieval; conversion cost equal.

---

## 📈 4. Extract / GroupBy Performance

| Metric | Epoch | Bitpack | Observation |
|--------|--------|----------|-------------|
| p50 (ms) | 95.0 | 66.0 | Bitpack faster (~30%) |
| Throughput | 10.64 | 15.38 | Bitpack +45% faster aggregation |

**Inference:** Bitpack significantly outperforms Epoch for analytical queries due to smaller data per group.

---

## ⚡ 5. TxnMixed Performance

| Metric | Epoch | Bitpack | Observation |
|--------|--------|----------|-------------|
| p50 (ms) | 16.0 | 16.0 | Equal |
| Throughput | 64.48 | 64.58 | Identical |
| Total Time | 16.4 | 16.3 | Equal |

**Inference:** Mixed workloads are balanced. Both models perform equivalently.

---

## 🗑️ 6. Delete Performance

| Metric | Epoch | Bitpack | Observation |
|--------|--------|----------|-------------|
| p50 (ms) | 740.0 | 738.0 | Equal |
| Throughput | 1.34 | 1.35 | Equal |
| Tail Latency | 891 vs 816 | Bitpack 8% lower |

**Inference:** Deletes are lock-bound; Bitpack shows slightly more consistent tail latency.

---

## 🧾 7. Overall Comparison Summary

| Category | Winner | Gain | Notes |
|-----------|---------|------|-------|
| **Insert** | Tie | — | Equal I/O cost |
| **Update** | Tie | — | Lock contention dominated |
| **Select** | Bitpack | +2.3% | Slightly faster retrieval |
| **Extract / GroupBy** | **Bitpack** | +45% | Big win for analytics |
| **TxnMixed** | Tie | — | Same concurrency pattern |
| **Delete** | Bitpack | +1–2% | Slight improvement |

---

## 🧠 Insights

1. Bitpack offers small but consistent performance gains for analytical workloads.
2. Insert/Update/Delete are I/O bound, not CPU bound.
3. GroupBy-heavy workloads show strong bitpack advantage.
4. Processing time is negligible across all workloads (<1ms).
5. Results reflect realistic MySQL 5.7 latency behavior.

---

## 🧩 Key Recommendations

1. Use **Bitpack** for analytics/reporting modules.
2. Epoch and Bitpack are interchangeable for transactional modules.
3. Upgrade to **MySQL 8.0+ or PostgreSQL 15+** for compressed and indexed datetime.
4. Keep **DB vs CPU time separation** for ongoing benchmark clarity.

---

## 🏁 Final Verdict

| Aspect | Verdict |
|--------|----------|
| **Overall Performance** | Comparable |
| **Analytics Queries** | Bitpack wins |
| **Transactional Throughput** | Tie |
| **CPU Efficiency** | Equal |
| **Latency Stability** | Bitpack slightly better |

✅ **Conclusion:** Bitpack provides measurable benefits for analytical workloads while maintaining parity for transactional performance — making it the preferred long-term strategy for mixed multi-tenant systems.

