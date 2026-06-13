(function() {
    const pagePath = document.location.pathname;
    const basePath = pagePath.substring(0, pagePath.lastIndexOf("/") + 1);
    const applyMetadata = async function() {
        let metadata = await(await fetch(basePath + "identity/metadata")).json();
        for (let name in metadata.names) {
            for (let element of document.getElementsByClassName(name + "_name")) {
                element.textContent = metadata.names[name];
            }
        }
    }
    applyMetadata()
})()