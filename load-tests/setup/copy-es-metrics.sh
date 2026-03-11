#!/bin/bash

set -euo pipefail

if [ -z "${1:-}" ]; then
  echo "Usage: $0 <namespace> [destination_dir]"
  echo "Example: $0 c8-pg-typical-big-bh"
  echo "Example: $0 c8-pg-typical-big-bh ~/Desktop/my-metrics"
  exit 1
fi

namespace="$1"
dest="${2:-$HOME/Desktop/es-metrics-${namespace}}"

echo "Copying ES metrics from namespace: ${namespace}"
echo "Destination: ${dest}"

# Start temp pod with PVC
echo "Starting temp pod..."
kubectl run es-metrics-copy --namespace "${namespace}" \
  --image=busybox:1.36 --restart=Never \
  --overrides="{
    \"spec\":{
      \"containers\":[{
        \"name\":\"es-metrics-copy\",
        \"image\":\"busybox:1.36\",
        \"command\":[\"sleep\",\"120\"],
        \"volumeMounts\":[{\"name\":\"data\",\"mountPath\":\"/data\"}]
      }],
      \"volumes\":[{
        \"name\":\"data\",
        \"persistentVolumeClaim\":{\"claimName\":\"es-metrics-data\"}
      }]
    }
  }"

echo "Waiting for pod to be ready..."
kubectl wait --for=condition=ready pod/es-metrics-copy -n "${namespace}" --timeout=30s

# Copy data
echo "Copying data..."
kubectl cp "${namespace}/es-metrics-copy:/data" "${dest}"

# Show summary
echo ""
echo "=== Summary ==="
snapshot_count=$(ls -1 "${dest}/snapshots/" 2>/dev/null | wc -l | tr -d ' ')
echo "Snapshots: ${snapshot_count}"
echo "Timeseries entries: $(wc -l < "${dest}/timeseries.ndjson" 2>/dev/null || echo 0)"
du -sh "${dest}"
echo "Saved to: ${dest}"

# Cleanup
echo ""
echo "Cleaning up temp pod..."
kubectl delete pod es-metrics-copy -n "${namespace}"
echo "Done!"
