#!/bin/sh

# Multipaz Server Bundle Startup Script
# Starts all servers with configurable base URLs

set -e

# Base URL for proxy mode (nginx routes by path)
BASE_URL="${BASE_URL:-http://localhost:8000}"

no_protocol="${BASE_URL#*://}"      # strip protocol
host_port="${no_protocol%%/*}"     # strip path
host="${host_port%:*}"  # strip port

# Mode: "proxy" (default) routes through nginx; "direct" exposes ports directly
MODE="${MODE:-proxy}"

# Additional params that can be passed to all servers
EXTRA_PARAMS="${EXTRA_PARAMS:-}"

if [ -z "$ADMIN_PASS" ] ; then
  if [ "$BASE_URL" = "http://localhost:8000" ] ; then
    ADMIN_PASS=multipaz
    echo "Admin password is set to 'multipaz'"
  else
    echo "ADMIN_PASS must be set for non-test deployments"
  fi
fi

echo "=========================================="
echo "Multipaz Server Bundle"
echo "=========================================="
echo "Mode: ${MODE}"
echo "Base URL: ${BASE_URL}"
echo "=========================================="
echo ""

pids=""

service () {
  local service="$1"
  shift
  local instance="$1"
  shift
  local port="$1"
  shift
  local extra="$EXTRA_PARAMS"
  if [ "records" = "$instance" ] ; then
    extra="$extra -param ca_allow_enrollment=[\"$host\"]"
    extra="$extra -param issuer_url=${BASE_URL}/openid4vci"
  else
    if [ "openid4vci" = "$instance" ] ; then
      extra="$extra -param system_of_record_url=${BASE_URL}/records"
    fi
    extra="$extra -param enrollment_server_url=${BASE_URL}/records"
  fi
  echo "Starting $service service ($instance) at port $port..."
  java -jar "/app/jars/$service.jar" \
    -param server_port=$port \
    -param base_url=${BASE_URL}/$instance \
    -param ca_trust_servers="[\"$host\"]" \
    -param server_trace_file="/app/logs/$instance-trace.log" \
    -param database_connection="jdbc:hsqldb:file:/app/data/$instance" \
    $extra \
    $* \
    > "/app/logs/$instance-log.log" 2>&1 &
  pids="$pids $!"
  echo "  PID: $!"
}

# Start nginx if in proxy mode, must be first, as services will want to connect to each other
if [ "$MODE" = "proxy" ]; then
    echo "Starting nginx reverse proxy on port 8000..."
    nginx -g 'daemon off;' &
    NGINX_PID=$!
    echo "  PID: ${NGINX_PID}"
    pids="$pids ${NGINX_PID}"
fi

# records server must be started first, as it processes enrollments
service records records 8004 -param admin_password=$ADMIN_PASS
service openid4vci openid4vci 8007 -param admin_password=$ADMIN_PASS
service csa csa 8005
service verifier verifier 8006
service backend backend 8008

echo ""
echo "All services started."

echo ""
echo "=========================================="
echo "Multipaz Server Bundle is running"
if [ "$MODE" = "proxy" ]; then
    echo "Access via: ${BASE_URL}"
else
    echo "Access services directly on ports 8004-8008"
fi
echo "=========================================="
echo ""

# Handle shutdown gracefully
cleanup() {
    echo "Shutting down, killing $pids..."
    kill $pids 2>/dev/null || true
    exit 0
}

trap cleanup TERM INT
wait

exit 1
