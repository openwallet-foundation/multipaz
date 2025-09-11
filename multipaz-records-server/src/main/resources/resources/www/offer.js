load()

async function load() {
    let token = new URLSearchParams(location.search).get("token");
    let base = location.pathname.substring(0, location.pathname.lastIndexOf("/") + 1);
    let rawSchema = await (await fetch(base + "identity/schema")).json();
    let byRecordTypeId = {};
    let recordTypeSelect = document.getElementById("recordType")
    for (let recordType of rawSchema.schema) {
        let option = document.createElement("option");
        option.value = recordType.identifier;
        option.textContent = recordType.display_name;
        recordTypeSelect.appendChild(option);
        byRecordTypeId[recordType.identifier] = recordType;
    }

    let coreRecordType = byRecordTypeId.core;
    let fields = [];
    let records = {}
    for (let attribute of coreRecordType.type.attributes) {
        fields.push(attribute.identifier);
    }
    for (let recordTypeId in byRecordTypeId) {
        if (recordTypeId != "core") {
            records[recordTypeId] = [];
        }
    }
    let request = {
        token: token,
        core: fields,
        records: records
    };
    let data = await(await fetch(base + "identity/get", {
       method: 'POST',
       headers: {
           'Content-Type': 'application/json',
       },
       body: JSON.stringify(request)
    })).json();

    let givenName = data.core.given_name || "";
    let familyName = data.core.family_name || "";
    let nameInput = document.getElementById("name");
    nameInput.value = givenName + " " + familyName;

    let instanceSelect = document.getElementById("instance");
    let instanceRow = document.getElementById("instanceRow");
    let txCodeSelect = document.getElementById("txCode");
    let txPromptInput = document.getElementById("txPrompt");
    let txPromptRow = document.getElementById("txPrompt");
    let button = document.getElementById("generate");

    const updateInstanceList = function() {
        let recordType = recordTypeSelect.value;
        instanceSelect.innerHTML = "";
        if (recordType == "core") {
            let option = document.createElement("option");
            option.value = "";
            instanceSelect.appendChild(option);
            instanceRow.style.display = "none";
            button.disabled = false;
        } else {
            let records = data.records[recordType] || {};
            let count = 0;
            for (let instanceId in records) {
                let title = records[instanceId].instance_title || "Record " + instanceId;
                let option = document.createElement("option");
                option.value = instanceId;
                option.textContent = title;
                instanceSelect.appendChild(option);
                count++;
            }
            if (count == 0) {
                instanceRow.style.display = "";
                let option = document.createElement("option");
                option.value = "";
                option.textContent = "[No records found]";
                instanceSelect.appendChild(option);
                button.disabled = true;
            } else if (count == 1) {
                instanceRow.style.display = "none";
                button.disabled = false;
            } else {
                instanceRow.style.display = "";
                button.disabled = false;
            }
        }
    };
    updateInstanceList();
    recordTypeSelect.addEventListener("change", updateInstanceList);

    txCodeSelect.addEventListener("change", function() {
        txPromptRow.style.display = txCodeSelect.value == "none" ? "none" : ""
    });

    button.addEventListener("click", function() {
        generateOffer(base, {
            token: token,
            scope: recordTypeSelect.value,
            instance: instanceSelect.value,
            tx_kind: txCodeSelect.value,
            tx_prompt: txPromptInput.value
        });
    });
}

async function generateOffer(base, request) {
    let responses = await(await fetch(base + "identity/offer", {
       method: 'POST',
       headers: {
           'Content-Type': 'application/json',
       },
       body: JSON.stringify(request)
    })).json();
    let metadata = await(await fetch(base + "identity/metadata")).json();
    let offers = document.getElementById("offers");
    offers.innerHTML = "";
    let title = document.createElement("h2")
    offers.appendChild(title);
    if (responses.length == 0) {
        title.textContent = "No offers could be generated";
    } else {
        title.textContent = "Generated credential offers";
        for (let response of responses) {
            let credentialTitle = document.createElement("h3")
            let display = response.display[0];
            credentialTitle.textContent = display.name;
            offers.appendChild(credentialTitle);
            let credentialPara = document.createElement("p")
            offers.appendChild(credentialPara);
            let credentialLink = document.createElement("a")
            credentialLink.textContent = "Offer link"
            credentialLink.href = response.offer;
            credentialPara.appendChild(credentialLink);
            let qr = document.createElement("img");
            qr.className = "qr"
            qr.setAttribute("src", metadata.issuer_url +
                    "/qr?q=" + encodeURIComponent(response.offer));
            qr.setAttribute("style", "image-rendering: pixelated");
            offers.appendChild(qr);
            let txp = document.createElement("p");
            offers.appendChild(txp);
            if (response.tx_code) {
                txp.textContent = "Transaction code: ";
                let code = document.createElement("code");
                txp.appendChild(code);
                code.textContent = response.tx_code;
            } else {
                txp.textContent = "No transaction code required";
            }
        }
    }
}