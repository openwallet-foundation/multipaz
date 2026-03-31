# Multipaz Server Bundle
# Single image containing all reference servers and web frontend

FROM eclipse-temurin:17-jre-jammy

# Install nginx for reverse proxy
RUN apt-get update && apt-get install -y --no-install-recommends \
   nginx less sqlite3 libsqlite3-dev curl && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r multipaz && useradd -r -g multipaz multipaz

WORKDIR /app

# Copy shared dependency JARs (deduplicated across all servers)
COPY multipaz-server-deployment/build/docker-staging/libs/ /app/libs/

# Copy thin server JARs
COPY multipaz-server-deployment/build/docker-staging/jars/ /app/jars/

# Copy web frontend (built by Gradle before docker build)
COPY multipaz-server-frontend/build/dist/js/productionExecutable /app/web
RUN chmod -R 755 /app/web

# Copy nginx configuration
COPY multipaz-server-deployment/docker/nginx.conf /etc/nginx/nginx.conf

# Copy Multipaz services configurations
COPY multipaz-server-deployment/docker/services/backend.conf /etc/multipaz/backend.conf
COPY multipaz-server-deployment/docker/services/csa.conf /etc/multipaz/csa.conf
COPY multipaz-server-deployment/docker/services/openid4vci.conf /etc/multipaz/openid4vci.conf
COPY multipaz-server-deployment/docker/services/records.conf /etc/multipaz/records.conf
COPY multipaz-server-deployment/docker/services/verifier.conf /etc/multipaz/verifier.conf

# Copy initial data
COPY multipaz-server-deployment/docker/init/records.json /app/init/records.json

# Copy startup script
COPY multipaz-server-deployment/docker/start-servers.sh /app/start-servers.sh
RUN chmod +x /app/start-servers.sh

# Create directories for data and logs
RUN mkdir -p /app/data /app/logs && chown -R multipaz:multipaz /app

# nginx needs to write to these directories
RUN mkdir -p /var/lib/nginx/body /var/log/nginx /run /run/nginx && \
    chown -R multipaz:multipaz /var/lib/nginx /var/log/nginx /run /run/nginx

USER multipaz

# Expose unified port (nginx) and individual service ports
EXPOSE 8080 8004 8005 8006 8007 8008

ENTRYPOINT ["/app/start-servers.sh"]
