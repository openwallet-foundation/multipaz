load()

async function load() {
    let token = new URLSearchParams(location.search).get("token");
    let base = location.pathname.substring(0, location.pathname.lastIndexOf("/") + 1);
    let rawSchema = await (await fetch(base + "identity/schema")).json();
    let byRecordTypeId = {};
    let recordTypeSelect = document.getElementById("recordType")
    for (let recordType of rawSchema.schema) {
        if (recordType.identifier != "core") {
            let option = document.createElement("option");
            option.value = recordType.identifier;
            option.textContent = recordType.display_name;
            recordTypeSelect.appendChild(option);
        }
        byRecordTypeId[recordType.identifier] = recordType;
    }
    let typedefs = rawSchema.types;
    let coreRecordType = byRecordTypeId.core;
    let coreDiv = document.getElementById("core");
    let recordsDiv = document.getElementById("records");
    let inputs = {
        core: addField(coreDiv, typedefs, coreRecordType, "core"),
        records: {}
    }
    if (token) {
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
        inputs.token = token;
        setFieldData(inputs.core, typedefs, coreRecordType, data.core);
        for (let recordTypeId in data.records) {
            let recordsData = data.records[recordTypeId];
            let records = {};
            inputs.records[recordTypeId] = records;
            let recordType = byRecordTypeId[recordTypeId];
            for (let recordId in recordsData) {
                let removeFn = makeRemoveRecordFn(recordsDiv, records, recordId);
                let field = addField(recordsDiv, typedefs, recordType, "record", null, removeFn);
                records[recordId] = field;
                setFieldData(field, typedefs, recordType, recordsData[recordId]);
            }
        }
    }
    document.getElementById("addRecord").addEventListener("click", function() {
        let typeId = recordTypeSelect.value;
        let recordType = byRecordTypeId[typeId];
        if (recordType != null) {
            let records = inputs.records[typeId];
            if (!records) {
                records = {};
                inputs.records[typeId] = records;
            }
            let recordId = Math.random().toString(36).substring(2);
            let removeFn = makeRemoveRecordFn(recordsDiv, records, recordId);
            records[recordId] = addField(recordsDiv, typedefs, recordType, "record", null, removeFn);
        }
    });
    document.getElementById("save").addEventListener("click", function() {
        save(base, inputs, typedefs, byRecordTypeId);
    });
    document.getElementById("delete").addEventListener("click", function() {
        remove(base, inputs.token);
    });
}

function makeRemoveRecordFn(recordsDiv, records, recordId) {
    return function(container, field) {
        recordsDiv.removeChild(container);
        delete records[recordId];
    }
}

let idIndex = 0;

function addField(controls, typedefs, attribute, className, sectionDiv, removeFn) {
    let id = "id" + (idIndex++);
    let row = document.createElement("div");
    controls.appendChild(row);
    row.className = className;
    if (className != "item") {
        const topLevel = className == "record" || className == "core";
        const icon = document.createElement("span");
        icon.className = "icon";
        const span = document.createElement("span");
        span.className = "material-symbols-outlined";
        span.textContent = attribute.icon || " ";
        icon.appendChild(span);
        let label;
        if (topLevel) {
            label = document.createElement("h3");
            label.textContent = attribute.display_name;
            label.insertBefore(icon, label.firstChild);
        } else {
            label = document.createElement("label")
            label.setAttribute("for", id);
            label.textContent = attribute.display_name + ": "
            row.appendChild(icon);
        }
        row.appendChild(label);
    }
    let input;
    let subsection = null;
    let type = resolveType(typedefs, attribute);
    if (typeof type == "object") {
        switch (type.type) {
            case "options":
            case "int_options": {
                input = document.createElement("select");
                for (let id in type.options) {
                    let option = document.createElement("option");
                    option.value = id;
                    option.textContent = type.options[id];
                    input.appendChild(option);
                }
                break;
            }
            case "list": {
                let itemAttribute = type.elements;
                let container = document.createElement("div");
                let items = [];
                subsection = { items: items, container: container }
                input = document.createElement("div");
                input.className = "list";
                let actions = document.createElement("div");
                actions.className = "list_actions";
                input.appendChild(actions);
                input.appendChild(container);
                let add = document.createElement("button");
                add.textContent = "Add item";
                actions.appendChild(add);
                add.addEventListener("click", function() {
                    let removeFn = makeRemoveItemFn(subsection);
                    items.push(addField(container, typedefs, itemAttribute, "item", input, removeFn));
                });
                break;
            }
            case "complex": {
                let container = document.createElement("div");
                input = document.createElement("div");
                input.appendChild(container);
                let items = addRecordSection(container, typedefs, attribute, className + "_section");
                subsection = { items: items, container: container };
                break;
            }
            default:
                throw new Error("unsupported type")
        }
    } else if (type == "picture") {
        input = document.createElement("input");
        input.type = "file";
        input.accept = "image/jpeg";
        let container = document.createElement("div");
        container.style.display = "inline-block";
        container.style.verticalAlign = "top";
        container.style.margin = "0px 1em";
        container.style.padding = "0.5em";
        container.style.background = "#EEE";
        let image = document.createElement("img");
        image.style.height = "8em"
        image.style.width = "auto"
        image.style.display = "block";
        let caption = document.createElement("div")
        caption.textContent = attribute.display_name;
        caption.style.textAlign = "center";
        let imageId = "id" + (idIndex++);
        input.dataset.image = imageId;
        image.setAttribute("id", imageId);
        container.appendChild(image);
        container.appendChild(caption);
        sectionDiv.appendChild(container);
        input.addEventListener('change', imageSelected);
    } else {
        input = document.createElement("input");
        switch (type) {
            case "date":
                input.type = "date";
                break;
            case "datetime":
                input.type = "datetime-local";
                break;
            case "boolean":
                input.type = "checkbox";
                break;
            case "number":
                input.type = "number";
                break;
            default:
                input.type = "text";
        }
    }
    if (className == "core" && attribute.identifier == "utopia_id_number") {
        input.readOnly = true
    }
    row.appendChild(input);
    input.setAttribute("id", id);
    let field = subsection || input;
    if (removeFn) {
        let actions = document.createElement("div");
        actions.className = "actions";
        row.appendChild(actions);
        let remove = document.createElement("button");
        remove.textContent = "Delete " + className;
        actions.appendChild(remove);
        remove.addEventListener("click", function() {
            removeFn(row, field);
        });
    }
    return field;
}

function addRecordSection(containerDiv, typedefs, recordType, className) {
    let section = {};
    let div = document.createElement("div");
    div.className = className;
    containerDiv.appendChild(div);
    if (className == "core" || className == "record") {
        let header = document.createElement("h3")
        header.textContent = recordType.display_name;
        div.appendChild(header)
    }
    let controlsHolder = document.createElement("div");
    controlsHolder.style.display = "inline-block";
    controlsHolder.style.verticalAlign = "top";
    div.appendChild(controlsHolder);
    let controls = document.createElement("div");
    controls.className = "controls";
    controlsHolder.appendChild(controls);
    for (let attribute of recordType.type.attributes) {
        section[attribute.identifier] = addField(controls, typedefs, attribute, "row", div);
    }
    return section;
}

let dataUrlPrefix = "data:image/jpeg;base64,";

function imageSelected(evt) {
    let input = evt.target;
    let image = document.getElementById(input.dataset.image);
    let file = input.files[0];
    if (!file) {
        return;
    }
    const reader = new FileReader();
    reader.onload = function(e) {
        const base64 = btoa(e.target.result);
        image.src = dataUrlPrefix + base64;
    };
    reader.readAsBinaryString(file);
}

async function save(base, inputs, typedefs, recordTypes) {
    let data = {records:{}};
    let command;
    if (inputs.token) {
        command = "update";
        data.token = inputs.token;
    } else {
        command = "create";
    }
    data.core = readField(inputs.core, typedefs, recordTypes.core);
    for (let recordTypeId in inputs.records) {
        let records = {};
        let sections = inputs.records[recordTypeId];
        data.records[recordTypeId] = records;
        let recordType = recordTypes[recordTypeId];
        for (let recordId in sections) {
            records[recordId] = readField(sections[recordId], typedefs, recordType)
        }
    }
    let response = await (await fetch(base + "identity/" + command, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(data)
    })).json();
    if (response.error) {
        alert("Could not save data: '" + response.error + "': " + response.error_description)
    } else if (!inputs.token && response.token) {
        inputs.token = response.token;
        let src = location.href;
        let index = src.indexOf("?")
        if (index > 0) {
            src = src.substring(0, index)
        }
        location.replace(src + "?token=" + response.token);
    }
}

function readField(input, typedefs, attribute) {
    let type = resolveType(typedefs, attribute);
    if (typeof type == "object") {
        switch (type.type) {
            case "options":
                return input.value ? input.value : undefined;
            case "int_options":
                return input.value ? input.value - 0 : undefined;
            case "list":
                if (input.items.length == 0) {
                    return undefined;
                } else {
                    let list = [];
                    let itemAttribute = type.elements;
                    for (let item of input.items) {
                        list.push(readField(item, typedefs, itemAttribute));
                    }
                    return list;
                }
            case "complex":
                return readSection(input.items, typedefs, attribute);
            default:
                throw new Error("unsupported type")
        }
    } else if (type == "picture") {
        let imageSrc = document.getElementById(input.dataset.image).src;
        if (imageSrc.startsWith(dataUrlPrefix)) {
            let imageData = imageSrc.substring(dataUrlPrefix.length);
            return imageData ? imageData : undefined;
        } else {
            return undefined
        }
    } else if (type == "number") {
        return input.value ? input.value - 0 : undefined;
    } else if (type == "boolean") {
        return input.checked;
    } else {
        return input.value ? input.value : undefined;
    }
}

function readSection(section, typedefs, attribute) {
    let type = resolveType(typedefs, attribute);
    let record = {};
    for (let subattribute of type.attributes) {
        let input = section[subattribute.identifier];
        let value = readField(input, typedefs, subattribute);
        if (typeof value != 'undefined') {
            record[subattribute.identifier] = value;
        }
    }
    return record;
}

function setFieldData(input, typedefs, attribute, value) {
    let type = resolveType(typedefs, attribute);
    if (typeof type == "object") {
        switch (type.type) {
            case "options":
            case "int_options":
                input.value = typeof value == "undefined" ? "" : value;
                break;
            case "list": {
                input.items.length = 0;  // keep the array!
                while (input.container.lastChild) {
                    input.container.removeChild(input.container.lastChild);
                }
                let itemAttribute = type.elements;
                let removeFn = makeRemoveItemFn(input);
                for (let item of value || []) {
                    let field = addField(input.container, typedefs, itemAttribute, "item",
                            input.container.parentNode, removeFn)
                    input.items.push(field);
                    setFieldData(field, typedefs, itemAttribute, item);
                }
                break;
            }
            case "complex":
                setSectionData(input.items, typedefs, attribute, value || {});
                break;
            default:
                throw new Error("unsupported type")
        }
    } else if (type == "picture") {
        let image = document.getElementById(input.dataset.image);
        if (value) {
            image.src = dataUrlPrefix + value;
        }
    } else if (type == "boolean") {
        input.checked = value;
    } else {
        input.value = typeof value == "undefined" ? "" : value;
    }
}

function makeRemoveItemFn(input) {
    return function(container, field) {
       const index = input.items.indexOf(field);
       input.items.splice(index, 1);
       input.container.removeChild(container);
    }
}

function setSectionData(section, typedefs, attribute, data) {
    let type = resolveType(typedefs, attribute);
    for (let subattribute of type.attributes) {
        let input = section[subattribute.identifier];
        let value = data[subattribute.identifier];
        setFieldData(input, typedefs, subattribute, value)
    }
}

function resolveType(typedefs, attribute) {
    const type = attribute.type;
    if (typeof type == "string") {
        let namedType = typedefs[type];
        if (namedType) {
            return namedType;
        }
    }
    return type;
}

async function remove(base, token) {
    let response = await (await fetch(base + "identity/delete", {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({token: token})
    })).json();
    if (response.error) {
        alert("Failed to delete: '" + response.error + "': " + response.error_description)
    } else if (response.deleted) {
        location.href = "index.html";
    }
}
