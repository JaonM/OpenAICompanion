package com.harness.kmp

import platform.UIKit.UIDevice

private class IOSPlatform : Platform {
    override val name: String = UIDevice.currentDevice.systemName()
    override val version: String = UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()