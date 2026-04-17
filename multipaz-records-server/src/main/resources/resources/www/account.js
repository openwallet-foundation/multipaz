load()

async function load() {
    const account = new URLSearchParams(location.search).get("id");
    const base = location.pathname.substring(0, location.pathname.lastIndexOf("/") + 1);
    const data = await (await fetch(base + "payment/account/" + account)).json();
    const main = document.getElementById("main");
    const fields = document.createElement("div");
    fields.className = "field-block";
    main.appendChild(fields);
    addRow(fields, "Holder", data.holder_name)
    addRow(fields, "Holder id", data.holder_id)
    addRow(fields, "Account number", account);
    const balance = addRow(fields, "Account balance", formatBalance(data.balance));
    if (data.balance < 0) {
        balance.style.color = "#880000";
    }
    for (let transaction of data.transactions) {
        const record = document.createElement("div");
        record.className = "record";
        record.style.padding = "4px";
        const container = document.createElement("div");
        container.className = "field-block";
        const amount = addRow(container, "Amount", formatBalance(transaction.amount));
        if (transaction.description) {
            addRow(container, "Description", transaction.description);
        }
        if (transaction.to) {
            record.style.backgroundColor = "#FFCCCC";
            addRow(container, "To", transaction.to.name);
            let account = addRow(container, "Account", transaction.to.account, "a");
            account.href = "account.html?id=" + transaction.to.account;
        } else {
            record.style.backgroundColor = "#CCFFCC";
            addRow(container, "From", transaction.from.name);
            let account = addRow(container, "Account", transaction.from.account, "a");
            account.href = "account.html?id=" + transaction.from.account;
        }
        addRow(container, "Transaction id", transaction.id);
        addRow(container, "Time", transaction.time);
        record.appendChild(container);
        main.appendChild(record);
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