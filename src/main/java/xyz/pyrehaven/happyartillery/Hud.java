package xyz.pyrehaven.happyartillery;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Sole boss/action-bar and warning-particle owner for every rider. */
public final class Hud {
    private static final long AUXILIARY_MIN_CADENCE_TICKS = 5L;
    private final Map<Object, Session> sessions = new HashMap<>();

    <R, H> RiderState update(
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

        Session session = sessions.get(riderId);
        boolean freshSession = session == null || !ghastId.equals(session.ghastId);
        boolean viewerReplaced = false;
        boolean attachmentDelivered = false;
        if (freshSession) {
            if (session != null && session.display != null) {
                @SuppressWarnings("unchecked")
                H handle = (H) session.display.handle();
                @SuppressWarnings("unchecked")
                R oldViewer = (R) session.viewer;
                access.removeViewer(handle, oldViewer);
            }
            session = new Session(ghastId, rider, config.hud().actionBar(), now);
            sessions.put(riderId, session);
        } else if (session.viewer != rider) {
            if (session.display != null) {
                @SuppressWarnings("unchecked")
                H handle = (H) session.display.handle();
                @SuppressWarnings("unchecked")
                R oldViewer = (R) session.viewer;
                access.removeViewer(handle, oldViewer);
                access.addViewer(handle, rider);
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
        Color color = color(progress, snapshot.biomeClass(), config.hud().warningFromPercent());
        double warning = warningThreshold(config.hud().warningFromPercent());
        boolean aboveWarning = progress >= warning;
        if (aboveWarning && !session.warningAbove) {
            session.warningPending = true;
        }
        session.warningAbove = aboveWarning;
        Display display = session.display;
        if (display != null && !config.hud().bossBar()) {
            @SuppressWarnings("unchecked")
            H handle = (H) display.handle();
            access.removeViewer(handle, rider);
            display = null;
            session.display = null;
            attachmentDelivered = true;
            cache = new RiderState.HudCache(
                    -1.0, "", cache.actionBarText(), cache.lastActionBarTick());
        }
        if (config.hud().bossBar() && display == null) {
            H handle = access.createBossBar(progress, color);
            access.addViewer(handle, rider);
            display = new Display(handle);
            session.display = display;
            cache = new RiderState.HudCache(
                    progress, color.name(), cache.actionBarText(), cache.lastActionBarTick());
            attachmentDelivered = true;
        }

        String actionText = actionText(progress, snapshot);
        boolean controlWarning = snapshot.pilotControls().isPresent()
                && !normalControlStatus(snapshot.pilotControls().orElseThrow());
        boolean persistentAction = controlWarning || snapshot.activeFireControl();
        boolean actionChanged = !actionText.equals(cache.actionBarText());
        boolean actionDue = refreshDue(now, session.lastActionTick, config.hud().refreshTicks());
        boolean promptWarning = freshSession && controlWarning;
        if (session.actionEnabled && (persistentAction || actionChanged)
                && (promptWarning || actionReenabled || actionDue)
                && (!attachmentDelivered || promptWarning)) {
            Color actionColor = controlWarning
                    ? (missingControl(snapshot.pilotControls().orElseThrow()) ? Color.RED : Color.GOLD)
                    : snapshot.biomeClass() == BiomeClass.NETHER ? Color.RED : color;
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
                for (int offset = 0; offset < 3; offset++) {
                    int channel = (session.nextChannel + offset) % 3;
                    boolean delivered = false;
                    if (channel == 0 && session.warningPending) {
                        access.warningParticle(rider);
                        session.warningPending = false;
                        delivered = true;
                    } else if (channel == 1 && display != null
                            && Double.compare(progress, cache.bossProgress()) != 0) {
                        @SuppressWarnings("unchecked")
                        H handle = (H) display.handle();
                        access.setProgress(handle, progress);
                        cache = new RiderState.HudCache(
                                progress, cache.bossColor(),
                                cache.actionBarText(), cache.lastActionBarTick());
                        delivered = true;
                    } else if (channel == 2 && display != null
                            && !color.name().equals(cache.bossColor())) {
                        @SuppressWarnings("unchecked")
                        H handle = (H) display.handle();
                        access.setColor(handle, color);
                        cache = new RiderState.HudCache(
                                cache.bossProgress(), color.name(),
                                cache.actionBarText(), cache.lastActionBarTick());
                        delivered = true;
                    }
                    if (delivered) {
                        session.nextChannel = (channel + 1) % 3;
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

    RiderState update(
            ServerPlayer rider,
            ServerLevel level,
            HappyGhast ghast,
            RiderState riderState,
            long now,
            Snapshot snapshot,
            Config config) {
        Objects.requireNonNull(ghast, "ghast");
        if (ghast.level() != Objects.requireNonNull(level, "level")) {
            throw new IllegalArgumentException("HUD ghast must be loaded in the rider's server level");
        }
        return update(rider.getUUID(), rider, ghast.getUUID(), riderState, now, snapshot, config,
                new MinecraftPresentationAccess(level, ghast));
    }

    void remove(ServerPlayer rider) {
        Session session = sessions.remove(rider.getUUID());
        if (session != null && session.display != null) {
            ((ServerBossEvent) session.display.handle()).removePlayer(rider);
        }
    }

    void clear() {
        for (Session session : sessions.values()) {
            if (session.display != null) {
                ((ServerBossEvent) session.display.handle()).removePlayer((ServerPlayer) session.viewer);
            }
        }
        sessions.clear();
    }

    <R, H> void remove(Object riderId, R rider, PresentationAccess<R, H> access) {
        Objects.requireNonNull(riderId, "riderId");
        Objects.requireNonNull(rider, "rider");
        Objects.requireNonNull(access, "access");
        Session session = sessions.remove(riderId);
        if (session != null && session.display != null) {
            @SuppressWarnings("unchecked")
            H handle = (H) session.display.handle();
            @SuppressWarnings("unchecked")
            R currentViewer = (R) session.viewer;
            access.removeViewer(handle, currentViewer);
        }
    }

    <R, H> void clear(PresentationAccess<R, H> access) {
        Objects.requireNonNull(access, "access");
        for (Map.Entry<Object, Session> entry : sessions.entrySet()) {
            if (entry.getValue().display == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            R rider = (R) entry.getValue().viewer;
            @SuppressWarnings("unchecked")
            H handle = (H) entry.getValue().display.handle();
            access.removeViewer(handle, rider);
        }
        sessions.clear();
    }


    static Component bossBarName(Component customName) {
        return customName != null ? customName : Component.literal("HappyGhast");
    }

    private static String actionText(double progress, Snapshot snapshot) {
        if (snapshot.pilotControls().isPresent()) {
            Controls.InventorySnapshot controls = snapshot.pilotControls().orElseThrow();
            if (missingControl(controls)) {
                return "CONTROL MISSING · DISMOUNT AND REMOUNT";
            }
            int inventoryOnly = (controls.fire() == Controls.ControlLocation.MAIN_INVENTORY_ONLY ? 1 : 0)
                    + (controls.cry() == Controls.ControlLocation.MAIN_INVENTORY_ONLY ? 1 : 0);
            if (inventoryOnly == 1) {
                return "CONTROL IN INVENTORY";
            }
            if (inventoryOnly == 2) {
                return "CONTROLS IN INVENTORY";
            }
        }
        if (snapshot.biomeClass() == BiomeClass.NETHER) {
            return "NETHER · NO COOLING";
        }
        return "HEAT " + Math.round(progress * 100.0) + "% · " + snapshot.status().label;
    }

    private static boolean missingControl(Controls.InventorySnapshot controls) {
        return controls.fire() == Controls.ControlLocation.MISSING
                || controls.cry() == Controls.ControlLocation.MISSING;
    }

    private static boolean normalControlStatus(Controls.InventorySnapshot controls) {
        return !missingControl(controls)
                && controls.fire() != Controls.ControlLocation.MAIN_INVENTORY_ONLY
                && controls.cry() != Controls.ControlLocation.MAIN_INVENTORY_ONLY;
    }

    private static double normalized(double heat, double limit) {
        return Math.max(0.0, Math.min(1.0, heat / limit));
    }

    private static Color color(double progress, BiomeClass biomeClass, int warningFromPercent) {
        double warning = warningThreshold(warningFromPercent);
        if (progress >= warning) {
            return Color.RED;
        }
        return switch (biomeClass) {
            case COLD -> Color.BLUE;
            case HOT, NETHER -> Color.GOLD;
            case BASE, END -> Color.GREEN;
        };
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
            double heat, BiomeClass biomeClass, Status status,
            Optional<Controls.InventorySnapshot> pilotControls,
            boolean activeFireControl) {
        public Snapshot(double heat, BiomeClass biomeClass, Status status) {
            this(heat, biomeClass, status, Optional.empty(), false);
        }

        public Snapshot(
                double heat, BiomeClass biomeClass, Status status,
                Optional<Controls.InventorySnapshot> pilotControls) {
            this(heat, biomeClass, status, pilotControls, false);
        }

        public Snapshot {
            Objects.requireNonNull(biomeClass, "biomeClass");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(pilotControls, "pilotControls");
        }
    }

    public enum Status {
        COOLING,
        FIRING,
        NO_COOLING;

        private final String label;

        Status() {
            label = name().replace('_', ' ');
        }
    }

    public enum Color {
        RED,
        GOLD,
        BLUE,
        GREEN
    }

    interface PresentationAccess<R, H> {
        H createBossBar(double progress, Color color);

        void addViewer(H handle, R rider);

        void setProgress(H handle, double progress);

        void setColor(H handle, Color color);

        void removeViewer(H handle, R rider);

        void actionBar(R rider, String text, Color color);

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
        public ServerBossEvent createBossBar(double progress, Color color) {
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
        public void setColor(ServerBossEvent handle, Color color) {
            handle.setColor(bossColor(color));
        }

        @Override
        public void removeViewer(ServerBossEvent handle, ServerPlayer rider) {
            handle.removePlayer(rider);
        }

        @Override
        public void actionBar(ServerPlayer rider, String text, Color color) {
            rider.sendOverlayMessage(Component.literal(text).withStyle(textColor(color)));
        }

        @Override
        public void warningParticle(ServerPlayer rider) {
            level.sendParticles(rider, ParticleTypes.FLAME, false, false,
                    ghast.getX(), ghast.getY(0.5) + 0.5, ghast.getZ(),
                    8, 0.5, 0.5, 0.5, 0.01);
        }

        private static BossEvent.BossBarColor bossColor(Color color) {
            return switch (color) {
                case RED -> BossEvent.BossBarColor.RED;
                case GOLD -> BossEvent.BossBarColor.YELLOW;
                case BLUE -> BossEvent.BossBarColor.BLUE;
                case GREEN -> BossEvent.BossBarColor.GREEN;
            };
        }

        private static ChatFormatting textColor(Color color) {
            return switch (color) {
                case RED -> ChatFormatting.RED;
                case GOLD -> ChatFormatting.GOLD;
                case BLUE -> ChatFormatting.BLUE;
                case GREEN -> ChatFormatting.GREEN;
            };
        }
    }

    private static final class Session {
        private final Object ghastId;
        private Object viewer;
        private Display display;
        private long lastRefreshTick = Long.MIN_VALUE;
        private long lastActionTick;
        private boolean refreshed;
        private boolean actionEnabled;
        private boolean warningAbove;
        private boolean warningPending;
        private int nextChannel;

        private Session(Object ghastId, Object viewer, boolean actionEnabled, long now) {
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
            warningAbove = false;
            warningPending = false;
            nextChannel = 0;
        }
    }

    private record Display(Object handle) {
    }
}
