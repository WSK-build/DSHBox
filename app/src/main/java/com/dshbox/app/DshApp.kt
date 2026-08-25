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
        // Start the foreground service as early as possible. On first run it
        // will bootstrap both the sandbox and DSH; afterwards the user controls
        // each independently.
        SandboxService.start(this)
    }
}
