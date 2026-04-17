async function run() {
    const account = document.getElementById("account").value;
    const amount = document.getElementById("amount").value - 0;
    const description = document.getElementById("description").value;
    const protocols = [];
    if (document.getElementById("protocol_iso").checked) {
        protocols.push("org-iso-mdoc")
    }
    if (document.getElementById("protocol_openid4vp").checked) {
        protocols.push("openid4vp-v1-signed")
    }
    const req = {
        payee_account: account,
        amount: amount,
        protocols: protocols
    };
    if (description != "") {
        req["description"] = description
    }
    const result = document.getElementById("result");
    result.innerHTML = "";
    const response = await multipazVerifyCredentials(req);
    if (response.error) {
        result.textContent = response.error + ": " + response.error_description;
    } else {
        const fields = document.createElement("div");
        fields.className = "controls";
        result.appendChild(fields);
        addRow(fields, "Payee", response.payee.name);
        addRow(fields, "Amount", formatBalance(response.amount));
        addRow(fields, "Transaction id", response.transaction_id);
    }
}

function formatBalance(balance) {
    return balance.toLocaleString('en-US', {
        minimumFractionDigits: 2,
    });
}

function addRow(container, name, value, elementName) {
    const row = document.createElement("div");
    row.className = "row";
    const label = document.createElement("span");
    label.className = "label";
    label.textContent = name + ":"
    row.appendChild(label);
    const field = document.createElement(elementName || "span");
    field.className = "field";
    field.textContent = value
    row.appendChild(field);
    container.appendChild(row);
    return field;
}
