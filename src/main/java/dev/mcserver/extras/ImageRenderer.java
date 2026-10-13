package dev.mcserver.mapimage;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Zeichnet ein 128x128-Bild einmal pro Spieler auf die Karte. */
final class ImageRenderer extends MapRenderer {

    private final BufferedImage image;
    private final Set<UUID> drawn = ConcurrentHashMap.newKeySet();

    ImageRenderer(BufferedImage image) {
        super(true);
        this.image = image;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (drawn.add(player.getUniqueId())) {
            canvas.drawImage(0, 0, image);
        }
    }
}
