window.addEventListener("DOMContentLoaded", onLoad);


async function onLoad() {
    const query = document.getElementById("query");
    const transactionData = document.getElementById("transaction_data");
    const transactionDataPresent = document.getElementById("transaction_data_present");
    const select = document.getElementById("examples");
    const cannedList = await (await fetch("canned_requests")).json()
    const requestList = [];
    for (let canned of cannedList) {
        const docName = canned.display_name;
        for (let request of canned.requests) {
            const name = request.display_name;
            const option = document.createElement("option");
            option.value = requestList.length;
            option.text = name + " - " + docName;
            requestList.push(request);
            select.appendChild(option);
        }
    }
    transactionDataPresent.addEventListener("change", function() {
        transactionData.style.display = transactionDataPresent.checked ? "" : "none";
    });
    document.getElementById("apply_example").addEventListener("click", function() {
        const example = requestList[select.value];
        query.value = JSON.stringify(example.dcql, null, 4);
        if (example.transaction_data) {
            transactionData.style.display = "";
            transactionData.value = JSON.stringify(example.transaction_data, null, 4);
            transactionDataPresent.checked = true;
        } else {
            transactionData.style.display = "none";
            transactionDataPresent.checked = false;
        }
    });
}

async function run() {
    const query = JSON.parse(document.getElementById("query").value);
    const protocols = ["openid4vp-v1-uri"];
    if (document.getElementById("protocol_iso").checked) {
        protocols.push("org-iso-mdoc")
    }
    if (document.getElementById("protocol_openid4vp").checked) {
        protocols.push("openid4vp-v1")
    }
    const req = { dcql: query, protocols: protocols };
    const transactionDataPresent = document.getElementById("transaction_data_present");
    const transactionDataText = document.getElementById("transaction_data").value;
    if (transactionDataPresent.checked && transactionDataText.trim().length !== 0) {
        req["transaction_data"] = JSON.parse(transactionDataText);
    }
    req.sign = document.getElementById("sign-request").checked
    req.encrypt = document.getElementById("encrypt-response").checked
    const result = document.getElementById("result");
    result.innerHTML = "";
    const response = await multipazVerifyCredentials(req);
    for (let label in response) {
        renderContent(result, label, response[label], 0);
    }
}
