#!/bin/bash
# Quick test to verify both models run

cd "$(dirname "$0")"

echo "Testing if both models can be run..."
echo ""

echo "1. Testing Epoch model (should print 'Starting benchmark with model: epoch'):"
java -jar target/bench-runner-1.0-jar-with-dependencies.jar --model epoch 2>&1 | head -5
echo ""

echo "2. Testing Bitpack model (should print 'Starting benchmark with model: bitpack'):"
java -jar target/bench-runner-1.0-jar-with-dependencies.jar --model bitpack 2>&1 | head -5
echo ""

echo "Done! Check output above to verify both models start correctly."

