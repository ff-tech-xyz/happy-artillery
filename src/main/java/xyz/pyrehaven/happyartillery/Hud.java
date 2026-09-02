package xyz.pyrehaven.happyartillery;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Sole boss/action-bar and warning-particle owner for every rider. */
public final class Hud<R, H> {
    private static final long AUXILIARY_MIN_CADENCE_TICKS = 5L;
    private final Map<Object, Session<R, H>> sessions = new HashMap<>();
    private final ViewerAccess<R, H> viewerAccess;

    Hud(ViewerAccess<R, H> viewerAccess) {
        this.viewerAccess = Objects.requireNonNull(viewerAccess, "viewerAccess");
    }

    static Hud<ServerPlayer, ServerBossEvent> minecraft() {
        return new Hud<>(MinecraftViewerAccess.INSTANCE);
    }

    RiderState update(
            Object riderId,
            R rider,
            Object ghastId,
            RiderState riderState,
            long now,
            Snapshot snapshot,
            Config config,
            PresentationAccess<R, H> access) {
        Objects.requireNonNull(riderId, "riderId");
        Objects.requireNonNull(rider, "rider");
        Objects.requireNonNull(ghastId, "ghastId");
        Objects.requireNonNull(riderState, "riderState");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(access, "access");

        Session<R, H> session = sessions.get(riderId);
        boolean freshSession = session == null || !ghastId.equals(session.ghastId);
        boolean viewerReplaced = false;
        boolean attachmentDelivered = false;
        if (freshSession) {
            if (session != null && session.display != null) {
                access.removeViewer(session.display, session.viewer);
            }
            session = new Session<>(ghastId, rider, config.hud().actionBar(), now);
            sessions.put(riderId, session);
        } else if (session.viewer != rider) {
            if (session.display != null) {
                access.removeViewer(session.display, session.viewer);
                access.addViewer(session.display, rider);
                attachmentDelivered = true;
            }
            session.viewer = rider;
            session.resetChannels(config.hud().actionBar(), now);
            viewerReplaced = true;
        }
        boolean actionReenabled = !session.actionEnabled && config.hud().actionBar();
        session.actionEnabled = config.hud().actionBar();

        RiderState.HudCache cache = freshSession
                ? freshCache()
                : riderState.hudCache().orElseGet(Hud::freshCache);
        if (viewerReplaced || actionReenabled) {
            cache = new RiderState.HudCache(
                    cache.bossProgress(), cache.bossColor(), "", Long.MIN_VALUE);
        }
        double progress = normalized(snapshot.heat(), config.heat().limit());
        Config.Color color = color(progress, snapshot.mode(), config.hud());
        double warning = warningThreshold(config.hud().warningFromPercent());
        if (progress >= warning) {
            session.warningPending = true;
        }
        H display = session.display;
        if (display != null && !config.hud().bossBar()) {
            access.removeViewer(display, rider);
            display = null;
            session.display = null;
            attachmentDelivered = true;
            cache = new RiderState.HudCache(
                    -1.0, "", cache.actionBarText(), cache.lastActionBarTick());
        }
        if (config.hud().bossBar() && display == null) {
            H handle = access.createBossBar(progress, color);
            access.addViewer(handle, rider);
            display = handle;
            session.display = display;
            cache = new RiderState.HudCache(
                    progress, color.name(), cache.actionBarText(), cache.lastActionBarTick());
            attachmentDelivered = true;
        }

        String actionText = actionText(progress, snapshot, config);
        boolean controlWarning = snapshot.pilotControls().isPresent()
                && !normalControlStatus(snapshot.pilotControls().orElseThrow(), config);
        boolean persistentAction = controlWarning || snapshot.activeFireControl();
        boolean actionChanged = !actionText.equals(cache.actionBarText());
        boolean actionDue = refreshDue(now, session.lastActionTick, config.hud().refreshTicks());
        boolean promptWarning = freshSession && controlWarning;
        if (session.actionEnabled && (persistentAction || actionChanged)
                && (promptWarning || actionReenabled || actionDue)
                && (!attachmentDelivered || promptWarning)) {
            Config.Color actionColor = controlWarning
                    ? (missingControl(snapshot.pilotControls().orElseThrow(), config)
                    ? Config.Color.RED : Config.Color.GOLD)
                    : color;
            access.actionBar(rider, actionText, actionColor);
            cache = new RiderState.HudCache(
                    cache.bossProgress(), cache.bossColor(), actionText, now);
            session.lastActionTick = now;
        }
        if (attachmentDelivered) {
            session.lastRefreshTick = now;
            session.refreshed = true;
        } else {
            boolean auxiliaryDue = !session.refreshed
                    || refreshDue(now, session.lastRefreshTick,
                    Math.max(AUXILIARY_MIN_CADENCE_TICKS, config.hud().refreshTicks()));
            if (auxiliaryDue) {
                for (int offset = 0; offset < 4; offset++) {
                    int channel = (session.nextChannel + offset) % 4;
                    boolean delivered = false;
                    if (channel == 0 && session.warningPending) {
                        access.warningParticle(rider);
                        session.warningPending = false;
                        delivered = true;
                    } else if (channel == 1 && display != null
                            && Double.compare(progress, cache.bossProgress()) != 0) {
                        access.setProgress(display, progress);
                        cache = new RiderState.HudCache(
                                progress, cache.bossColor(),
                                cache.actionBarText(), cache.lastActionBarTick());
                        delivered = true;
                    } else if (channel == 2 && display != null
                            && !color.name().equals(cache.bossColor())) {
                        access.setColor(display, color);
                        cache = new RiderState.HudCache(
                                cache.bossProgress(), color.name(),
                                cache.actionBarText(), cache.lastActionBarTick());
                        delivered = true;
                    } else if (channel == 3 && display != null
                            && !access.hasCurrentName(display)) {
                        access.setName(display);
                        delivered = true;
                    }
                    if (delivered) {
                        session.nextChannel = (channel + 1) % 4;
                        break;
                    }
                }
                session.lastRefreshTick = now;
                session.refreshed = true;
            }
        }
        if (riderState.hudCache().isPresent() && riderState.hudCache().get().equals(cache)) {
            return riderState;
        }
        return riderState.withHudCache(cache);
    }

    static PresentationAccess<ServerPlayer, ServerBossEvent> minecraftPresentation(
            ServerLevel level, HappyGhast ghast) {
        Objects.requireNonNull(ghast, "ghast");
        if (ghast.level() != Objects.requireNonNull(level, "level")) {
            throw new IllegalArgumentException("HUD ghast must be loaded in the rider's server level");
        }
        return new MinecraftPresentationAccess(level, ghast);
    }

    void remove(ServerPlayer rider) {
        remove(rider.getUUID());
    }

    void clear() {
        for (Session<R, H> session : sessions.values()) {
            if (session.display != null) {
                viewerAccess.removeViewer(session.display, session.viewer);
            }
        }
        sessions.clear();
    }

    void remove(Object riderId) {
        Objects.requireNonNull(riderId, "riderId");
        Session<R, H> session = sessions.remove(riderId);
        if (session != null && session.display != null) {
            viewerAccess.removeViewer(session.display, session.viewer);
        }
    }


    static Component bossBarName(Component customName) {
        return customName != null ? customName : Component.literal("HappyGhast");
    }

    private static String actionText(double progress, Snapshot snapshot, Config config) {
        if (snapshot.pilotControls().isPresent()) {
            Controls.InventorySnapshot controls = snapshot.pilotControls().orElseThrow();
            if (missingControl(controls, config)) {
                return matchingControls(controls, config, Controls.ControlLocation.MISSING) == 1
                        ? "CONTROL MISSING · DISMOUNT AND REMOUNT"
                        : "CONTROLS MISSING · DISMOUNT AND REMOUNT";
            }
            int inventoryOnly = matchingControls(
                    controls, config, Controls.ControlLocation.MAIN_INVENTORY_ONLY);
            if (inventoryOnly == 1) {
                return "CONTROL IN INVENTORY";
            }
            if (inventoryOnly == 2) {
                return "CONTROLS IN INVENTORY";
            }
        }
        String status;
        if (snapshot.mode() == Firing.FIRING) {
            status = "FIRING";
        } else {
            Cooling cooling = (Cooling) snapshot.mode();
            status = cooling.perSecond() == 0.0
                    ? config.hud().cooling().noCoolingText()
                    : "COOLING " + formatRate(cooling.perSecond()) + "/s";
        }
        return "HEAT " + Math.round(progress * 100.0) + "% · " + status;
    }

    private static String formatRate(double perSecond) {
        return BigDecimal.valueOf(perSecond).stripTrailingZeros().toPlainString();
    }


    private static int matchingControls(
            Controls.InventorySnapshot controls, Config config, Controls.ControlLocation location) {
        return (config.fire().enabled() && controls.fire() == location ? 1 : 0)
                + (config.cry().enabled() && controls.cry() == location ? 1 : 0);
    }

    private static boolean missingControl(Controls.InventorySnapshot controls, Config config) {
        return matchingControls(controls, config, Controls.ControlLocation.MISSING) > 0;
    }

    private static boolean normalControlStatus(Controls.InventorySnapshot controls, Config config) {
        return !missingControl(controls, config)
                && matchingControls(controls, config,
                Controls.ControlLocation.MAIN_INVENTORY_ONLY) == 0;
    }

    private static double normalized(double heat, double limit) {
        return Math.max(0.0, Math.min(1.0, heat / limit));
    }

    private static Config.Color color(double progress, Mode mode, Config.Hud hud) {
        double warning = warningThreshold(hud.warningFromPercent());
        if (progress >= warning) {
            return Config.Color.RED;
        }
        if (mode == Firing.FIRING) {
            return hud.firingColor();
        }
        double rate = ((Cooling) mode).perSecond();
        Config.Cooling cooling = hud.cooling();
        if (rate == 0.0) {
            return cooling.noCoolingColor();
        }
        if (rate <= cooling.slowMaxPerSecond()) {
            return cooling.slowColor();
        }
        if (rate <= cooling.normalMaxPerSecond()) {
            return cooling.normalColor();
        }
        return cooling.fastColor();
    }

    private static double warningThreshold(int warningFromPercent) {
        return Math.max(0.0, Math.min(1.0, warningFromPercent / 100.0));
    }

    private static boolean refreshDue(long now, long lastRefreshTick, long refreshTicks) {
        if (now < lastRefreshTick) {
            return true;
        }
        long elapsed = now - lastRefreshTick;
        return elapsed < 0 || elapsed >= refreshTicks;
    }

    private static RiderState.HudCache freshCache() {
        return new RiderState.HudCache(-1.0, "", "", Long.MIN_VALUE);
    }

    public record Snapshot(
            double heat, Mode mode, Optional<Controls.InventorySnapshot> pilotControls,
            boolean activeFireControl) {
        public Snapshot {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(pilotControls, "pilotControls");
        }
    }

    public sealed interface Mode permits Firing, Cooling {
    }

    public enum Firing implements Mode {
        FIRING
    }

    public record Cooling(double perSecond) implements Mode {
        public Cooling {
            if (!Double.isFinite(perSecond) || perSecond < 0.0) {
                throw new IllegalArgumentException(
                        "Cooling rate must be finite and non-negative: " + perSecond);
            }
        }
    }

    interface ViewerAccess<R, H> {
        void removeViewer(H handle, R rider);
    }

    interface PresentationAccess<R, H> extends ViewerAccess<R, H> {
        H createBossBar(double progress, Config.Color color);

        void addViewer(H handle, R rider);

        void setProgress(H handle, double progress);

        void setColor(H handle, Config.Color color);

        boolean hasCurrentName(H handle);

        void setName(H handle);

        void actionBar(R rider, String text, Config.Color color);

        void warningParticle(R rider);
    }

    private static final class MinecraftPresentationAccess
            implements PresentationAccess<ServerPlayer, ServerBossEvent> {
        private final ServerLevel level;
        private final HappyGhast ghast;

        private MinecraftPresentationAccess(ServerLevel level, HappyGhast ghast) {
            this.level = Objects.requireNonNull(level, "level");
            this.ghast = Objects.requireNonNull(ghast, "ghast");
        }

        @Override
        public ServerBossEvent createBossBar(double progress, Config.Color color) {
            ServerBossEvent bar = new ServerBossEvent(
                    UUID.randomUUID(), bossBarName(ghast.getCustomName()), bossColor(color),
                    BossEvent.BossBarOverlay.PROGRESS);
            bar.setProgress((float) progress);
            return bar;
        }

        @Override
        public void addViewer(ServerBossEvent handle, ServerPlayer rider) {
            handle.addPlayer(rider);
        }

        @Override
        public void setProgress(ServerBossEvent handle, double progress) {
            handle.setProgress((float) progress);
        }

        @Override
        public void setColor(ServerBossEvent handle, Config.Color color) {
            handle.setColor(bossColor(color));
        }

        @Override
        public boolean hasCurrentName(ServerBossEvent handle) {
            return handle.getName().equals(bossBarName(ghast.getCustomName()));
        }

        @Override
        public void setName(ServerBossEvent handle) {
            handle.setName(bossBarName(ghast.getCustomName()));
        }

        @Override
        public void removeViewer(ServerBossEvent handle, ServerPlayer rider) {
            MinecraftViewerAccess.INSTANCE.removeViewer(handle, rider);
        }

        @Override
        public void actionBar(ServerPlayer rider, String text, Config.Color color) {
            rider.sendOverlayMessage(Component.literal(text).withStyle(textColor(color)));
        }

        @Override
        public void warningParticle(ServerPlayer rider) {
            level.sendParticles(rider, ParticleTypes.FLAME, false, false,
                    ghast.getX(), ghast.getY(0.5) + 0.5, ghast.getZ(),
                    8, 0.5, 0.5, 0.5, 0.01);
        }

        private static BossEvent.BossBarColor bossColor(Config.Color color) {
            return switch (color) {
                case RED -> BossEvent.BossBarColor.RED;
                case GOLD -> BossEvent.BossBarColor.YELLOW;
                case BLUE -> BossEvent.BossBarColor.BLUE;
                case GREEN -> BossEvent.BossBarColor.GREEN;
            };
        }

        private static ChatFormatting textColor(Config.Color color) {
            return switch (color) {
                case RED -> ChatFormatting.RED;
                case GOLD -> ChatFormatting.GOLD;
                case BLUE -> ChatFormatting.BLUE;
                case GREEN -> ChatFormatting.GREEN;
            };
        }
    }

    private enum MinecraftViewerAccess implements ViewerAccess<ServerPlayer, ServerBossEvent> {
        INSTANCE;

        @Override
        public void removeViewer(ServerBossEvent handle, ServerPlayer rider) {
            handle.removePlayer(rider);
        }
    }

    private static final class Session<R, H> {
        private final Object ghastId;
        private R viewer;
        private H display;
        private long lastRefreshTick = Long.MIN_VALUE;
        private long lastActionTick;
        private boolean refreshed;
        private boolean actionEnabled;
        private boolean warningPending;
        private int nextChannel;

        private Session(Object ghastId, R viewer, boolean actionEnabled, long now) {
            this.ghastId = ghastId;
            this.viewer = viewer;
            this.actionEnabled = actionEnabled;
            this.lastActionTick = now;
        }

        private void resetChannels(boolean enabled, long now) {
            lastRefreshTick = Long.MIN_VALUE;
            lastActionTick = now;
            refreshed = false;
            actionEnabled = enabled;
            warningPending = false;
            nextChannel = 0;
        }
    }

}
