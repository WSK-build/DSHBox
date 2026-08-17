package com.dshbox.app

import android.app.Application
import com.dshbox.app.di.AppContainer
import com.dshbox.app.di.ServiceLocator
import com.dshbox.app.service.SandboxService
import com.dshbox.app.ui.theme.AppThemeState

class DshApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ServiceLocator.createAppContainer(this)
        AppThemeState.load(this)
        // Start the sandbox-owning foreground service as early as possible so
        // DSH keeps running while the user works in the system browser.
        SandboxService.start(this)
    }
}
