# Multipaz Server Frontend

This directory contains code for the Multipaz Server Web front-end which provides
web experience for Multipaz Server. Generally, Multipaz Server is deployed using
a podman/docker image created using `multipaz-server-deployment` package, which
combines the front-end (this package) with Multipaz services.

The web frontend (`multipaz-server-frontend`) is a Kotlin/JS React application that uses
the `multipaz` package using Kotlin to JavaScript compilation.

### Development server with hot reload

```bash
./gradlew :multipaz-server-frontend:jsBrowserDevelopmentRun --continuous
```

Opens at http://localhost:3000. Changes to Kotlin files auto-reload. Note that when the code
is run this way, the back-end is not included.

TODO: determine how we could run with *both* hot reload *and* back-end.

### Production build only

```bash
./gradlew :multipaz-server-frontend:jsBrowserDistribution
```

Output is in `multipaz-server-frontend/build/dist/js/productionExecutable`.

