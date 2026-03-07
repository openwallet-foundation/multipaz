package org.multipaz.web

import org.multipaz.web.records.PersonApp
import org.multipaz.web.records.RecordsApp
import org.multipaz.web.tools.ConverterApp
import react.create
import react.dom.client.createRoot
import web.cssom.ClassName
import web.dom.document

// Map class names to app implementations
private val apps = mapOf(
    "multipaz-records-list" to RecordsApp,
    "multipaz-records-person" to PersonApp,
    "multipaz-converter" to ConverterApp
)

fun main() {
    for (appEntry in apps) {
        val elements = document.getElementsByClassName(ClassName(appEntry.key))
        for (index in 0..<elements.length) {
            val element = elements[index]
            createRoot(element).render(appEntry.value.create {
                definition = element
            })
        }
    }
}
