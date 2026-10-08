package com.openai.companion.ios

import com.openai.companion.kmp.AppModelServe
import com.openai.companion.kmp.MobileController
import com.openai.companion.kmp.createIosComposeViewController
import kotlinx.coroutines.MainScope
import platform.UIKit.UIViewController

/** Composes the iOS app's Kotlin UI, Harness backend, and MCP approval callback. */
class IosMobileHost(
    modelServe: AppModelServe,
    importLocalModel: suspend () -> Unit,
    localModelStatus: () -> String,
    cancelLocalModel: () -> Unit,
    localModelEngine: () -> String = { "llama.cpp" },
    localModelImportLabel: () -> String = { "导入 GGUF" },
    selectLocalModelEngine: suspend (String) -> Unit = {},
) {
    private val scope = MainScope()
    private val controller = MobileController(scope) { approve, requestInput, approveA2a ->
        IosMobileBackend(
            approve = approve,
            approveA2a = approveA2a,
            requestInput = requestInput,
            modelServe = modelServe,
            importLocalModel = importLocalModel,
            localModelStatus = localModelStatus,
            cancelLocalModel = cancelLocalModel,
            localModelEngine = localModelEngine,
            localModelImportLabel = localModelImportLabel,
            selectLocalModelEngine = selectLocalModelEngine,
        )
    }

    val viewController: UIViewController = createIosComposeViewController(controller.state, controller)

    fun start() {
        controller.start()
    }
}

/** Ready-to-present Compose host with device-local llama.cpp and the Rust Harness. */
fun createIosMobileHost(): IosMobileHost {
    val localModel = IosLocalLlamaModel()
    return IosMobileHost(
        modelServe = localModel,
        importLocalModel = localModel::importModel,
        localModelStatus = localModel::status,
        cancelLocalModel = localModel::cancel,
        localModelEngine = { localModel.selectedEngine },
        localModelImportLabel = { localModel.importLabel },
        selectLocalModelEngine = localModel::selectEngine,
    )
}
