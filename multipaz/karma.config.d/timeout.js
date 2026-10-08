// Increase Mocha test timeout for browser tests (cryptographic operations like
// RSA key generation can take longer than the 2000ms default).
config.set({
    client: {
        mocha: {
            timeout: 30000
        }
    }
});
