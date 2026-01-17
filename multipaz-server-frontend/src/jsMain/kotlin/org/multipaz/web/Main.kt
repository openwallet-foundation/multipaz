package org.multipaz.web

import react.create
import react.dom.client.createRoot
import web.dom.ElementId
import web.dom.document

fun main() {
    val rootElement = document.getElementById(ElementId("root"))
        ?: error("Could not find root element")
    
    createRoot(rootElement).render(App.create())
}
