# Multipaz Server Bundle Deployment

This directory contains everything needed to build and run all Multipaz reference servers and the web frontend as a single container image.

## What's Included

The container bundles:
- **Web Frontend** (`multipaz-server-frontend`) - Kotlin/JS React application at `/`
- **Verifier Server** - at `/verifier/` (port 8006)
- **OpenID4VCI Server** (Issuer) - at `/openid4vci/` (port 8007)
- **Records Server** (System of Record) - at `/records/` (port 8004)
- **CSA Server** (Credential Security Agent) - at `/csa/` (port 8005)
- **Backend Server** - at `/backend/` (port 8008)
- **nginx** - reverse proxy routing all services through port 8000

## Prerequisites

- Java 17+ (for building)
- Podman or Docker (for running containers)

## Installing Podman

Podman is recommended over Docker because it's free and does not require
a license for commercial use.

### macOS

```bash
# Install Podman
brew install podman

# Initialize the Podman virtual machine (one-time setup)
# Podman runs Linux containers in a VM on macOS
podman machine init

# Start the VM
podman machine start
```

After this setup, Podman is ready to use. The VM persists across reboots, but you need to
run `podman machine start` after restarting your Mac.

**Troubleshooting:**
- If you get errors about the machine not running: `podman machine start`
- If you get permission errors: `podman machine stop && podman machine start`
- To completely reset: `podman machine rm` then `podman machine init` again

### Linux

On Linux, Podman runs natively without a VM:

```bash
# Ubuntu/Debian
sudo apt-get update && sudo apt-get install -y podman

# Fedora
sudo dnf install -y podman
```

No additional setup is required.

## Building

### Build for your machine's architecture (fastest)

```bash
./gradlew :multipaz-server-deployment:buildDockerImage
```

### Build for a specific architecture

```bash
# For x86_64 servers (e.g., most cloud VMs)
./gradlew :multipaz-server-deployment:buildDockerImageAmd64

# For ARM64 servers (e.g., AWS Graviton, Apple Silicon)
./gradlew :multipaz-server-deployment:buildDockerImageArm64
```

Note: Building for a non-native architecture uses emulation and is slower.

### What gets built

The build creates a container image stored locally. View your images with:

```bash
podman images
```

You'll see entries like:
```
REPOSITORY                  TAG           IMAGE ID      SIZE
multipaz/server-bundle      latest        abc123...     500 MB
multipaz/server-bundle      0.97.0-pre.1  abc123...     500 MB
```

## Running Locally

```bash
podman run --rm -p 8000:8000 multipaz/server-bundle:latest
```

Then open http://localhost:8000 in your browser.

To stop: press `Ctrl+C`

### Shell in the container

```bash
# First, find the container ID
podman ps

# Then exec into it
podman exec -it <container_id> /bin/bash
```

### Accessing individual services

All services are available through the nginx proxy on port 8000:

| Service | URL                               |
|---------|-----------------------------------|
| Web Frontend | http://localhost:8000/            |
| Verifier | http://localhost:8000/verifier/   |
| OpenID4VCI (Issuer) | http://localhost:8000/openid4vci/ |
| Records | http://localhost:8000/records/    |
| CSA | http://localhost:8000/csa/        |
| Backend | http://localhost:8000/backend/    |

## Deploying to a Server

### Step 1: Export the image to a file

```bash
# For amd64 (most Linux servers)
podman save -o multipaz-server-bundle-amd64.tar multipaz/server-bundle:latest-amd64

# Or for arm64
podman save -o multipaz-server-bundle-arm64.tar multipaz/server-bundle:latest-arm64
```

This creates a `.tar` file (typically 700+ MB) containing the complete image.

### Step 2: Copy to the server

```bash
scp multipaz-server-bundle-amd64.tar user@yourserver:/path/to/destination/
```

### Step 3: Load and run on the server

On the server:

```bash
# Load the image
podman load -i multipaz-server-bundle-amd64.tar

# Run it
podman run -d --rm \
    -p 127.0.0.1:8000:8000 \
    -e BASE_URL=https://your-domain.com \
    multipaz/server-bundle:latest-amd64
```

The `-d` flag runs it in the background. Remove `--rm` if you want the container to persist after
stopping.

Note: this will deploy the bundle as HTTP service on port 8000, you would still need to use your
environment to expose it as HTTP service. Also, if not running on the root of the domain (e.g.
your BASE_URL is `https://foo.com/bar` rather than `https://foo.bar`), handlers for `/.well-known` 
urls have to be mapped correctly, e.g. for ngnix:
```
location = /.well-known/oauth-authorization-server/bar/openid4vci {
    proxy_pass http://localhost:8000/.well-known/oauth-authorization-server/openid4vci;
}

location = /.well-known/openid-credential-issuer/bar/openid4vci {
    proxy_pass http://localhost:8000/.well-known/openid-credential-issuer/openid4vci;
}
```

Make sure that your domain is resolved to a correct address and not loopback. Specifically,
from withing the container in the example above, `https://foo.com` must be reachable. Some
hosting services map `foo.com` to `127.0.0.1` on the host itself, such mapping will cause problems
and must be removed at very least in the container environment.

## Configuration

### Environment Variables

| Variable | Default                 | Description                                                                     |
|----------|-------------------------|---------------------------------------------------------------------------------|
| `BASE_URL` | `http://localhost:8000` | Base URL for all services (used in protocol messages)                           |
| `MODE` | `proxy`                 | `proxy` for nginx routing, `direct` for port-only access to individual services |
| `ADMIN_PASS` | `multipaz`        | default is only used for localhost deployment, otherwise it must be specified

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Container                                                       │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  nginx (port 8000)                                        │   │
│  │    /*            → web frontend (static files)            │   │
│  │    /verifier/*   → localhost:8006                         │   │
│  │    /openid4vci/* → localhost:8007                         │   │
│  │    /records/*    → localhost:8004                         │   │
│  │    /csa/*        → localhost:8005                         │   │
│  │    /backend/*    → localhost:8008                         │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                   │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐   │
│  │verifier │ │openid4  │ │records  │ │  csa    │ │backend  │   │
│  │ :8006   │ │vci:8007 │ │ :8004   │ │ :8005   │ │ :8008   │   │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Building Manually (without Gradle wrapper)

This may be useful if you are trying to troubleshoot a build problem.

```bash
# 1. Build all components
./gradlew :multipaz-verifier-server:buildFatJar
./gradlew :multipaz-openid4vci-server:buildFatJar
./gradlew :multipaz-backend-server:buildFatJar
./gradlew :multipaz-records-server:buildFatJar
./gradlew :multipaz-csa-server:buildFatJar
./gradlew :multipaz-server-frontend:jsBrowserDistribution

# 2. Build the container image
podman build -f multipaz-server-deployment/docker/Dockerfile -t multipaz/server-bundle:latest .

# 3. For a specific architecture
podman build --platform linux/amd64 -f multipaz-server-deployment/docker/Dockerfile -t multipaz/server-bundle:latest-amd64 .
```