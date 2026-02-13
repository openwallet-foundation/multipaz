window.addEventListener("DOMContentLoaded", onLoad);

const examples = {
    "mDL age verification": {
        "dcql": {
            "credentials": [
                {
                    "id": "mDL",
                    "format": "mso_mdoc",
                    "meta": {
                        "doctype_value": "org.iso.18013.5.1.mDL"
                    },
                    "claims": [
                        {
                          "id": "allowed",
                          "path": [
                            "org.iso.18013.5.1",
                            "age_over_21"
                          ]
                        },
                        {
                          "id": "photo",
                          "path": [
                            "org.iso.18013.5.1",
                            "portrait"
                          ]
                        }
                    ]
                }
           ]
        }
    },
    "Movie ticket + EU PID age": {
        "dcql": {
          "credentials": [
            {
              "id": "pid",
              "format": "dc+sd-jwt",
              "meta": {
                "vct_values": ["urn:eudi:pid:1"]
              },
              "claims": [
                {
                  "id": "can_go",
                  "path": [
                    "age_equal_or_over",
                    "18"
                  ]
                }
              ]
            },
            {
               "id": "movie",
               "format": "dc+sd-jwt",
               "meta": {
                    "vct_values": [
                        "https://utopia.example.com/vct/movieticket"
                    ]
               },
               "claims": [
                    {
                        "id": "when",
                        "path": [
                            "show_date_time"
                        ],
                    },
                    {
                        "path": [
                            "movie"
                        ]
                    }
                ]
            }
          ]
        },
        "transaction_data": [
            {
                "type": "org.multipaz.transaction_data.test",
                "credential_ids": ["pid"]
            }
        ]
    }
};

function onLoad() {
    const query = document.getElementById("query");
    const transactionData = document.getElementById("transaction_data");
    const transactionDataPresent = document.getElementById("transaction_data_present");
    const select = document.getElementById("examples");
    for (let name in examples) {
        const example = examples[name];
        const option = document.createElement("option");
        option.value = name;
        option.text = name;
        select.appendChild(option);
    }
    transactionDataPresent.addEventListener("change", function() {
        transactionData.style.display = transactionDataPresent.checked ? "" : "none";
    });
    document.getElementById("apply_example").addEventListener("click", function() {
        const example = examples[select.value];
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
    const protocols = [];
    if (document.getElementById("protocol_iso").checked) {
        protocols.push("org-iso-mdoc")
    }
    if (document.getElementById("protocol_openid4vp").checked) {
        protocols.push("openid4vp-v1-signed")
    }
    const req = { dcql: query, protocols: protocols };
    const transactionDataPresent = document.getElementById("transaction_data_present");
    const transactionDataText = document.getElementById("transaction_data").value;
    if (transactionDataPresent.checked && transactionDataText.trim().length !== 0) {
        req["transaction_data"] = JSON.parse(transactionDataText);
    }
    const response = await multipazVerifyCredentials(req);
    const result = document.getElementById("result");
    result.innerHTML = "";
    for (let label in response.result) {
        renderContent(result, label, response.result[label], 0);
    }
}

const dataUrlPrefix = "data:image/jpeg;base64,";

function renderContent(div, label, data, depth) {
    if (typeof data == 'array') {
        depth++;
        const container = document.createElement('div');
        container.setAttribute("class", "cred_data nest" + depth);
        div.appendChild(container);
        if (label) {
            const title = document.createElement("h4");
            title.textContent = label;
            container.appendChild(title);
        }
        for (let item of data) {
            renderContent(container, "", item, depth);
        }
    } else if (typeof data == 'object') {
        depth++;
        const container = document.createElement('div');
        container.setAttribute("class", "cred_data nest" + depth);
        div.appendChild(container);
        if (label) {
            const title = document.createElement("h4");
            title.textContent = label;
            container.appendChild(title);
        }
        for (let l in data) {
            renderContent(container, l, data[l], depth);
        }
    } else if (label == 'portrait' || label == 'photo' || label.endsWith("_image")) {
        const container = document.createElement('div');
        container.setAttribute("class", "image nest" + depth);
        div.appendChild(container);
        if (label) {
            const title = document.createElement("h4");
            title.textContent = label;
            container.appendChild(title);
        }
        const image = document.createElement("img");
        image.src = dataUrlPrefix + data
        container.appendChild(image);
    } else {
        const container = document.createElement('p');
        container.setAttribute("class", "image nest" + depth);
        div.appendChild(container);
        const title = document.createElement("b");
        title.textContent = label + ": ";
        container.appendChild(title);
        container.appendChild(document.createTextNode(data + ""));
    }
}