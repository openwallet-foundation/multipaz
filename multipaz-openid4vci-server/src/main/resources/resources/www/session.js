const formatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'long', timeStyle: 'long' });

const container = document.getElementById("sessionInfo")

async function refreshSession() {
    let sessionId = new URLSearchParams(location.search).get("session_id");
    let base = location.pathname.substring(0, location.pathname.lastIndexOf("/") + 1);
    let data = await (await fetch(base + "admin_session_info?session_id=" + sessionId)).json();
    container.innerHTML = "";
    addField(container, "Id", data.id);
    if (data.configuration_id) {
        addField(container, "Configuration id", data.configuration_id);
    }
    addField(container, "Scope", data.scope);
    if (data.client_id) {
        addField(container, "Client Id", data.client_id);
    }
    if (data.redirect_uri) {
        addField(container, "Redirect URI", data.redirect_uri);
    }
    if (data.authorized) {
        addField(container, "Authorized", formatter.format(new Date(data.authorized)));
    }
    addField(container, "Expiration", formatter.format(new Date(data.expiration)));
    for (credential of data.credentials) {
        let cred = document.createElement("div");
        cred.className = "cred";
        container.appendChild(cred);
        let header = document.createElement("h4");
        header.textContent = "Credential " + (credential.kid || "[keyless]");
        cred.appendChild(header);
        addField(cred, "Revocation bucket", credential.bucket);
        addField(cred, "Index", credential.idx);
        let statusRow = document.createElement("div");
        let label = document.createElement("span");
        label.className = "label"
        label.textContent = "Status: ";
        statusRow.appendChild(label);
        let status = document.createElement("select");
        let valid = document.createElement("option");
        valid.value = "valid";
        valid.textContent = "Valid";
        status.appendChild(valid);
        let invalid = document.createElement("option");
        invalid.value = "invalid";
        invalid.textContent = "Invalid";
        status.appendChild(invalid);
        let suspended = document.createElement("option");
        suspended.value = "suspended";
        suspended.textContent = "Suspended";
        status.appendChild(suspended);
        status.value = credential.status;
        statusRow.appendChild(status);
        let apply = document.createElement("button");
        apply.textContent = "Apply";
        statusRow.appendChild(apply);
        apply.addEventListener("click", setStatusHandler(base, credential, status));
        cred.appendChild(statusRow);
        addField(cred, "Creation", formatter.format(new Date(credential.creation)));
        addField(cred, "Expiration", formatter.format(new Date(credential.expiration)));
    }
}

function setStatusHandler(base, credential, status) {
    return async function() {
        let result = await(await fetch(base + "admin_set_credential_status", {
            method: 'POST',
            headers: {
               'Content-Type': 'application/json',
            },
            body: JSON.stringify({
                idx: credential.idx,
                bucket: credential.bucket,
                status: status.value
            })
        })).json();
        if (result && result.error) {
            alert("Error: " + result.error);
        } else {
            refreshSession();
        }
    }
}

function addField(container, name, data) {
    let div = document.createElement("div");
    let label = document.createElement("span");
    label.className = "label"
    label.textContent = name + ": ";
    div.appendChild(label);
    let field = document.createElement("span");
    field.textContent = data;
    div.appendChild(field);
    container.appendChild(div);
}

let login = document.getElementById("login")

login.addEventListener("loggedIn", refreshSession)
if (login.getAttribute("loggedIn") == "true") {
    refreshSession()
}