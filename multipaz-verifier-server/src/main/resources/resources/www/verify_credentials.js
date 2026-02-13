(function() {
    const scriptUrl = document.currentScript.src;
    const baseUrl = scriptUrl.substring(scriptUrl, scriptUrl.lastIndexOf('/') + 1);

    const delay = function(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    };

    const useUrlSchema = typeof DigitalCredential == "undefined";

    /**
     * Asynchronous function to request and verify credentials.
     *
     * This function uses DC API using ISO 18013 Annex C and OpenID4VP v1 protocols with fallback
     * to "haip-vp" URL scheme.
     *
     * Note that ISO 18013 Annex C only supports ISO mDoc credential format and does not (yet)
     * support transaction data, it is turned off if there are non-mDoc documents requested or
     * transaction data is used.
     *
     * Parameters: this function takes a single object parameter with the following fields:
     * - dcql (required): DCQL query (as Json object), as described in OpenID4VP spec
     * - transaction_data (optional): transaction data as an array of Json objects as described
     *   in described in OpenID4VP spec (before Json serialization and Base64Url-encoding).
     * - protocols (optional): an array of DC API protocols to use in the order of preference, the
     *   following values are supported: "org-iso-mdoc" (ISO 18013 Annex C), "openid4vp-v1-signed"
     *   (OpenID4VP v1). Both are enabled by default.
     */
    window.multipazVerifyCredentials = async function(request) {
        const adjustedRequest = {};
        for (let key in request) {
            adjustedRequest[key] = request[key];
        }
        if (useUrlSchema) {
            adjustedRequest.protocols = [];
        }
        const rq = await(await fetch(baseUrl + "make_request", {
             method: 'POST',
             headers: {
                 'Content-Type': 'application/json',
             },
             body: JSON.stringify(adjustedRequest)
        })).json();
        const sessionId = rq["session_id"];
        const clientId = rq["client_id"];
        const dcRequest = rq["dc_request"];
        if (useUrlSchema || dcRequest.digital.requests.length == 0) {
            window.location = "haip-vp://?client_id=" + encodeURIComponent(clientId) +
                "&request_uri=" + encodeURIComponent(baseUrl + "get_request/" + sessionId);
            while (true) {
                // NB: "get_result" does long poll with 3 minute timeout
                const res = await(await fetch(baseUrl + "get_result/" + sessionId)).json();
                if (res["status"] != "not_ready") {
                    return res;
                }
                // add a bit of a delay to rate-limit a bit if something goes terribly wrong
                delay(100);
            }
        } else {
            const dcResponse = await navigator.credentials.get(dcRequest);
            console.log("Response: " + JSON.stringify(dcResponse));
            return await(await fetch(baseUrl + "process_response", {
                    method: 'POST',
                    headers: {
                         'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({
                        session_id: sessionId,
                        dc_response: dcResponse
                    })
                })).json();
        }
    }
})();