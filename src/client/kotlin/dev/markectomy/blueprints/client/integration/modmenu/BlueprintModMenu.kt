package dev.markectomy.blueprints.client.integration.modmenu

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import dev.markectomy.blueprints.client.ui.BlueprintSettingsScreen

class BlueprintModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<BlueprintSettingsScreen> =
        ConfigScreenFactory(::BlueprintSettingsScreen)
}
