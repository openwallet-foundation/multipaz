const formatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'short', timeStyle: 'short' });

async function refreshSessions() {
    let base = location.pathname.substring(0, location.pathname.lastIndexOf("/") + 1);
    let table = document.getElementById("sessions");
    let data = await (await fetch(base + "admin_list_sessions")).json();
    table.innerHTML = "<tr><th>id</th><th>Type</th><th>Authorized</th><th>Expiration</th><th>Credentials</th></tr>"
    for (session of data.sessions) {
        let row = document.createElement("tr");
        let idCell = document.createElement("td");
        let a = document.createElement("a")
        a.textContent = session.id;
        a.href = "session.html?session_id=" + session.id;
        idCell.appendChild(a)
        row.appendChild(idCell);
        let typeCell = document.createElement("td");
        typeCell.textContent = session.configuration_id;
        row.appendChild(typeCell);
        let authCell = document.createElement("td");
        authCell.textContent = session.authorized
            ? formatter.format(new Date(session.authorized))
            : "not authorized";
        row.appendChild(authCell);
        let expCell = document.createElement("td");
        expCell.textContent = formatter.format(new Date(session.expiration));
        row.appendChild(expCell);
        let credCell = document.createElement("td");
        credCell.textContent = session.credential_count || "0";
        row.appendChild(credCell);
        table.appendChild(row);
    }
}

let login = document.getElementById("login")

login.addEventListener("loggedIn", refreshSessions)
if (login.getAttribute("loggedIn") == "true") {
    refreshSessions()
}