window.addEventListener("DOMContentLoaded", onLoad);


async function onLoad() {
    const pillsTab = document.getElementById("pills-tab");
    const pillsTabContent = document.getElementById("pills-tabContent");
    const cannedList = await (await fetch("canned_requests")).json();
    for (let index = 0; index < cannedList.length; index++) {
        const canned = cannedList[index];
        const extra = index == 0 ? " active" : "";
        const li = document.createElement("li");
        li.className = "nav-item"
        li.role = "presentation"
        const button = document.createElement("button");
        button.className = "nav-link" + extra;
        button.setAttribute("data-bs-toggle", "pill");
        button.setAttribute("data-bs-target", "#pills-" + index);
        button.setAttribute("type", "button");
        button.role="tab";
        button.ariaControls = "pills-home";
        button.ariaSelected = true;
        button.textContent = canned.display_name;
        li.appendChild(button);
        pillsTab.appendChild(li);
        const tab = {};

        const pane = document.createElement("div");
        pane.className = "tab-pane fade show" + extra;
        pane.id = "pills-" + index;
        pane.role="tabpanel"
        pane.ariaLabelledBy = [button];
        const content = document.createElement("div");
        content.className = "d-grid gap-2 mx-auto";
        pane.tabIndex = 0;
        for (let request of canned.requests) {
            const launchButton = document.createElement("button");
            launchButton.className = "btn btn-primary btn-lg";
            launchButton.addEventListener("click", makeLauncher(request));
            launchButton.textContent = request.display_name;
            content.appendChild(launchButton);
        }
        pane.appendChild(content);
        pillsTabContent.appendChild(pane);
    }

    const identityList = await (await fetch("verifier_identities")).json();
    const signing = document.getElementById("signing");
    for (let name of identityList) {
        let input = document.createElement("input");
        input.type = "checkbox"
        input.id = "sign-" + name;
        input.checked = name == "default";
        let label = document.createElement("label");
        label.setAttribute("for", "sign-" + name);
        label.appendChild(input);
        label.appendChild(document.createTextNode(" " + name));
        signing.append(label);
    }
}

function makeLauncher(request) {
    return async function () {
        const resultElement = document.getElementById("result");
        resultElement.innerHTML = "";
        const protocols = [];
        if (document.getElementById("protocol-dc-mdoc").checked) {
            protocols.push("org-iso-mdoc")
        }
        if (document.getElementById("protocol-dc-vp").checked) {
            protocols.push("openid4vp-v1")
        }
        if (document.getElementById("protocol-uri-vp").checked) {
            protocols.push("openid4vp-v1-uri")
        }
        request.protocols = protocols;
        request.sign = [];
        const signing = document.getElementById("signing");
        for (let child = signing.firstChild; child; child = child.nextSibling) {
            if (child.localName == "label") {
                let input = child.firstElementChild
                if (input.localName == "input" && input.id.startsWith("sign-") && input.checked) {
                    request.sign.push(input.id.substring(5))
                }
            }
        }
        request.encrypt = document.getElementById("encrypt-response").checked
        const response = await multipazVerifyCredentials(request);
        for (let label in response) {
            renderContent(result, label, response[label], 0);
        }
        var modal = new bootstrap.Modal(document.getElementById('resultModal'), {})
        modal.show()
    }
}