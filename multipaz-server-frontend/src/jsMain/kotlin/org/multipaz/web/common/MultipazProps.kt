package org.multipaz.web.common

import react.Props
import web.dom.Element

@OptIn(ExperimentalStdlibApi::class)
@JsExternalInheritorsOnly
external interface MultipazProps : Props {
    var definition: Element
}