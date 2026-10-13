package dev.mcserver.extras;

import org.bukkit.plugin.java.JavaPlugin;

/** Ein Plugin mit zwei Funktionen: eigene Rezepte (CustomRecipes) und Bilder auf Maps (MapImage). */
public final class ServerExtrasPlugin extends JavaPlugin {

    private RecipesModule recipes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        recipes = new RecipesModule(this);
        recipes.enable();
        new MapImageModule(this).enable();
    }

    @Override
    public void onDisable() {
        if (recipes != null) {
            recipes.disable();
        }
    }
}
