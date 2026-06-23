#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# API Gateway Startup Script
# Service: APIGateway
# Port: 8762
# ═══════════════════════════════════════════════════════════════════════════════

# Change to THIS service's directory (critical!)
cd /Users/mac/IdeaProjects/APIGateway

# Source environment variables
set -a
source /Users/mac/IdeaProjects/.env
set +a

export SPRING_PROFILES_ACTIVE=local-prod
export SERVER_PORT=8762

echo "════════════════════════════════════════════════════════════════"
echo "Starting API GATEWAY"
echo "Port: 8762"
echo "Profile: $SPRING_PROFILES_ACTIVE"
echo "════════════════════════════════════════════════════════════════"
echo ""

# Clean and build
./gradlew clean build -x test --no-daemon

# Run with explicit profile and port
./gradlew bootRun \
  -Dspring.profiles.active=local-prod \
  -Dserver.port=8762 \
  --no-daemon
