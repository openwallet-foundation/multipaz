(function() {
    const pagePath = document.location.pathname;
    const basePath = pagePath.substring(0, pagePath.lastIndexOf("/") + 1);
    const loginStyle = document.createElement("style");
    document.head.appendChild(loginStyle);

    const isLoggedIn = function() {
        for (let cookie of document.cookie.split(/\s*;\s*/)) {
            if (cookie.startsWith("admin_auth=")) {
                return true;
            }
        }
        return false;
    };

    const loginRefresh = function() {
        let hideClass = isLoggedIn() ? "logged_out" : "logged_in";
        loginStyle.textContent = "." + hideClass + " { display: none; }"
    };

    const login = document.getElementById("login");
    if (login) {
        const loggedIn = document.createElement("div");
        loggedIn.className = "logged_in";
        login.appendChild(loggedIn);
        const logOutButton = document.createElement("button");
        loggedIn.appendChild(logOutButton);
        logOutButton.textContent = "Logout";
        logOutButton.addEventListener("click", function() {
            document.cookie =
                "admin_auth=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=" + basePath + ";"
            loginRefresh();
        });

        const loggedOut = document.createElement("div");
        loggedOut.className = "logged_out";
        login.appendChild(loggedOut);
        const prompt = document.createElement("label");
        prompt.style.display = "none";
        loggedOut.appendChild(prompt);
        prompt.textContent = "Admin password:"
        const passwordElement = document.createElement("input");
        passwordElement.style.marginLeft = "0.5em";
        passwordElement.style.marginRight = "0.5em";
        passwordElement.type = "password";
        prompt.appendChild(passwordElement);

        const logInButton = document.createElement("button");
        loggedOut.appendChild(logInButton);
        logInButton.textContent = "Login";

        const logIn = async function() {
            let response = await(await fetch(basePath + "identity/auth", {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    password: passwordElement.value
                })
            })).json();
            passwordElement.value = "";
            if (response.error) {
                alert("Password incorrect, try again");
            } else {
                document.cookie =
                    "admin_auth=" + response.cookie +
                    "; max_age=" + response.expires_in +
                    "; path=" + basePath + ";"
                prompt.style.display = "none";
                loginRefresh();
            }
        };

        logInButton.addEventListener("click", function() {
            if (prompt.style.display == "none") {
                prompt.style.display = "inline";
            } else {
                logIn();
            }
        });
    }

    loginRefresh();
})()
