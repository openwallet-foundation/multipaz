package org.multipaz.openid4vci.util

import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration

suspend fun BackendEnvironment.Companion.getSystemOfRecordUrl(): String? =
    getInterface(Configuration::class)?.getValue("system_of_record_url")