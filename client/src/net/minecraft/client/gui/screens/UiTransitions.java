package net.minecraft.client.gui.screens;

import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;

/**
 * Stage-2 W7, instant UI transitions (docs/stage2/STAGE2-PLAN.md W7, docs/metal/CONTRACT.md 16.W7; approved by the user
 * 2026-10-05, default on). {@code -Dmc.ui.instantTransitions=false} (or {@code 0}) restores every vanilla wait.
 *
 * <p>What it removes (waits between launch and gameplay that guard no work and no handshake a remote server sees; the one
 * gameplay-visible effect is local: a new singleplayer world lets the player act 0.5 s sooner, see the last item):
 * <ul>
 *   <li>{@link LoadingOverlay}: the 2.0 s fade-out after a resource reload completes (1 s logo + progress-bar fade, 1 s
 *       background fade over the screen; input is blocked and singleplayer is paused while it runs) — with no level
 *       (startup, title screen) the overlay is removed in the same client tick that runs its completion callback, right
 *       after the vanilla {@code screen.init}. <b>In a world</b> the completion callback discards every compiled section
 *       ({@code LevelExtractor.allChanged}), which vanilla's opaque first second of fade-out hides; there the overlay stays
 *       opaque (and pausing, as vanilla's) until {@link #terrainShown} has held for {@link #IN_WORLD_STABLE_TICKS} client
 *       ticks, at most {@link #IN_WORLD_HOLD_CAP_MS} (vanilla's whole fade-out) after the callback, then goes at once; on
 *       later reloads (F3+T, pack / language changes) also the 0.5 s fade-in animation (the overlay is opaque at
 *       once) and the 1.0 s minimum hold before the completion callback may run. <b>Exception:</b> while a server or
 *       world resource-pack reload is pending ({@code DownloadedPackSource.serverPackReloadPending()}), the hold is kept,
 *       because that completion callback sends {@code ServerboundResourcePackPacket} (SUCCESSFULLY_LOADED) and its
 *       timing must stay vanilla's (servers can observe it). The fade-out is still skipped there: it starts only after
 *       the callback, so no packet depends on it.</li>
 *   <li>{@link TitleScreen}: the 2.0 s widget fade-in of the first title screen after startup ({@code fading=false};
 *       widgets are invisible for the first second and fade in during the second; they were already clickable).</li>
 *   <li>{@code Minecraft.doWorldLoad}: the 500 ms {@link LevelLoadTracker} close delay that vanilla adds only for a
 *       newly created singleplayer world ({@link #levelLoadCloseDelayMs}). Effect: the level-loading screen closes and
 *       {@code ServerboundPlayerLoadedPacket} is sent to the <i>integrated</i> server as soon as the player's section is
 *       compiled, as vanilla already does for existing worlds, respawns, dimension changes and every remote / LAN join
 *       (those always use a 0 ms delay, so nothing a remote server sees changes). Not purely cosmetic: until that packet
 *       the server keeps the player invulnerable ({@code ServerPlayer.isInvulnerableTo}: {@code !hasClientLoaded()}) and
 *       ignores its movement and actions, and the client ({@code LocalPlayer}) holds them back, so in a new singleplayer world both end 500 ms
 *       sooner — exactly as vanilla already behaves when that world is opened again.</li>
 * </ul>
 *
 * <p>Read once per {@link LoadingOverlay} (at construction), per {@link TitleScreen} (constructor) and per world load. Volatile
 * so a test harness or a debugger can flip it at runtime; the bench config block records it (ConfigBlock COMMON_CLASSES).
 */
public final class UiTransitions {
    /** {@code -Dmc.ui.instantTransitions} (default true). */
    public static volatile boolean instant = flag("mc.ui.instantTransitions", true);

    /** In-world reload hold: {@link #terrainShown} must hold for this many consecutive client ticks (the compile queue
     *  can drain for a moment while the occlusion graph still propagates). */
    public static final int IN_WORLD_STABLE_TICKS = 3;
    /** In-world reload hold cap, from the completion callback: vanilla's whole fade-out (it shows the world from 1 s on). */
    public static final long IN_WORLD_HOLD_CAP_MS = 2000L;

    /**
     * Whether the terrain around the camera is back after an in-world reload: the section storage was rebuilt
     * ({@code LevelExtractor.isGeometryInvalidationPending} false), the camera's own section is compiled and faded in
     * (vanilla's {@code LevelLoadTracker} test, skipped when the camera is outside the build height) and the compile queue
     * is empty. A field so the headless test can substitute it; the game never reassigns it.
     */
    public static volatile Predicate<Minecraft> terrainShown = UiTransitions::terrainShownNow;

    private UiTransitions() {
    }

    private static boolean terrainShownNow(final Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.levelExtractor.isGeometryInvalidationPending() || !minecraft.levelRenderer.hasRenderedAllSections()) {
            return false;
        }

        BlockPos camera = minecraft.gameRenderer.mainCamera().blockPosition();
        return level.isOutsideBuildHeight(camera.getY())
            || minecraft.levelRenderer.isSectionCompiledAndVisible(camera, Util.toMillis(minecraft.options.chunkSectionFadeInTime().get()));
    }

    /**
     * The {@link LevelLoadTracker} close delay for {@code Minecraft.doWorldLoad}: vanilla's 500 ms for a new world, 0 for an
     * existing one; 0 for both when instant transitions are on.
     */
    public static long levelLoadCloseDelayMs(final boolean newWorld) {
        return newWorld && !instant ? LevelLoadTracker.LEVEL_LOAD_CLOSE_DELAY_MS : 0L;
    }

    private static boolean flag(final String name, final boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : !value.equalsIgnoreCase("false") && !value.equals("0");
    }
}
