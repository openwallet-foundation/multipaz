# Multipaz Developer Tools (`tools.multipaz.org`)

A secure, fully client-side developer utility for working with ISO 18013-5 mDocs, CBOR/COSE structures, and SD-JWT tokens. All computations and parsing run directly inside the visitor's browser using the Multipaz SDK compiled to Kotlin/JS.

## Project Structure

This directory uses a split-project layout to support both modern frontend development (React) and single-archive deployments (FatJar):

- **[`web/`](web/)**: A Kotlin Multiplatform (Kotlin/JS) project that compiles the React Single Page Application (SPA).
- **[`server/`](server/)**: A Kotlin JVM Ktor server project that serves the compiled React assets statically. It outputs a production-ready FatJar embedding the entire application.

---

## Getting Started

### Local Development (with Hot Reload)

To run the frontend with hot reload during development:

1. **Start the Web Dev Server**:
   ```bash
   ./gradlew :multipaz-tools:web:jsBrowserDevelopmentRun --continuous
   ```
   This will run Webpack Dev Server (typically on port `8080` or next available) and watch for source changes in `web/` to hot-reload them in the browser.

2. **Start the Backend Server**:
   ```bash
   ./gradlew :multipaz-tools:server:run
   ```
   This will run the Ktor backend server on port `8012`.

---

## Production Build & Packaging

To compile the React web application and package everything into a single runnable FatJar:

```bash
./gradlew :multipaz-tools:server:assemble
```

This task compiles the frontend, copies the static production bundles into the server's resource package, and outputs the FatJar to:
`multipaz-tools/server/build/libs/server-all.jar`

---

## Running and Deploying the FatJar

Once compiled, you can run the application anywhere with a Java 17+ runtime:

```bash
java -jar multipaz-tools/server/build/libs/server-all.jar
```

### Configuration
By default, the server runs on port `8012`. You can override the port by setting the `PORT` environment variable:

```bash
PORT=8080 java -jar multipaz-tools/server/build/libs/server-all.jar
```
