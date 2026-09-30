package com.openai.companion.ios

import com.openai.companion.kmp.A2aStore
import com.openai.companion.kmp.HarnessA2aStore
import uniffi.harness.AppResult
import uniffi.harness.appA2aDeleteAgent
import uniffi.harness.appA2aListAgents
import uniffi.harness.appA2aListTasks
import uniffi.harness.appA2aPutAgent
import uniffi.harness.appA2aPutTask

class IosA2aStore : A2aStore by HarnessA2aStore(
    listAgentsJson = { appA2aListAgents().value() },
    putAgentJson = { appA2aPutAgent(it).value() },
    deleteAgentJson = { appA2aDeleteAgent(it).value() },
    listTasksJson = { appA2aListTasks().value() },
    putTaskJson = { appA2aPutTask(it).value() },
)

private fun AppResult.value(): String {
    check(ok) { error }
    return valueJson
}
