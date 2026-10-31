package antikristianos.stealthxpmpfix;

import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.skills.PerkFactory;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerLOS;

public final class ServerStealthXP {
    private static final Map<IsoPlayer, PlayerState> PLAYERS = new WeakHashMap<>();
    private static final StackWalker CALLERS = StackWalker.getInstance();
    private static boolean attempted;
    private static volatile boolean enabled;
    private static boolean probingAwardHook;
    private static boolean awardHookAcknowledged;
    private static VanillaProbe probe;
    private static Object world;
    private static int lastFrame = Integer.MIN_VALUE;
    private static long nextSummary;
    private static long cycles, candidates, awards;

    private ServerStealthXP() { }
    private static final class PlayerState {
        final MovementEvidence movement = new MovementEvidence();
        final Random random = new Random();
        boolean announced;
    }

    public static boolean isVanillaSpottingCaller(String owner, String method) {
        return owner.equals("zombie.characters.IsoZombie") && (method.equals("spottedNew") || method.equals("spottedOld"));
    }

    /** Only the lowest addXp overload is intercepted. The sentinel verifies that
     * this patch really executes before enabling a second XP producer. */
    public static boolean interceptAward(IsoPlayer player, PerkFactory.Perk perk, float amount) {
        if (probingAwardHook && player == null && perk == null && amount == 0f) {
            awardHookAcknowledged = true;
            return true;
        }
        if (!enabled || !GameServer.server || GameClient.client ||
                (perk != PerkFactory.Perks.Sneak && perk != PerkFactory.Perks.Lightfoot)) return false;
        return CALLERS.walk(frames -> frames.anyMatch(f -> isVanillaSpottingCaller(f.getClassName(), f.getMethodName())));
    }

    private static void initialize() {
        if (attempted) return;
        attempted = true;
        try {
            probe = new VanillaProbe();
            probingAwardHook = true;
            try { GameServer.addXp(null, null, 0f, false, false); }
            finally { probingAwardHook = false; }
            if (!awardHookAcknowledged) throw new IllegalStateException("XP suppression hook did not execute");
            enabled = true;
            log("ACTIVE 1.5: server owns movement, sight queries and XP; duplicate-award hook verified.");
        } catch (Throwable error) { disable(error); }
    }

    public static void tick(IsoPlayer player) {
        if (!GameServer.server || GameClient.client || player == null || player.isAnimal()) return;
        initialize();
        if (!enabled) return;
        try {
            if (IsoWorld.instance == null || IsoWorld.instance.currentCell == null) return;
            int frame = IsoWorld.instance.getFrameNo();
            if (world != IsoWorld.instance.currentCell || frame < lastFrame) {
                PLAYERS.clear(); world = IsoWorld.instance.currentCell;
            }
            lastFrame = frame;
            if (!GameServer.Players.contains(player) || player.isDead() || player.getCurrentSquare() == null ||
                    player.getNetworkCharacterAI().isDisconnected()) { PLAYERS.remove(player); return; }
            PlayerState state = PLAYERS.computeIfAbsent(player, ignored -> new PlayerState());
            if (state.movement.duplicate(frame)) return;
            long now = System.nanoTime();
            boolean moving = state.movement.update(frame, now, player.realx, player.realy, player.realz,
                    player.getNetworkCharacterAI().moving);
            if (!moving || !player.isSneaking() || player.isAsleep() || player.isGhostMode() ||
                    player.getVehicle() != null || player.isSitOnGround()) return;
            float multiplier = GameTime.getInstance().getMultiplier();
            if (!Float.isFinite(multiplier) || multiplier <= 0) return;
            XpCycle cycle = new XpCycle(multiplier, GameServer.debug, state.random::nextInt);
            boolean modern = SandboxOptions.instance.lore.spottedLogic.getValue();
            var objects = player.getCell().getObjectList();
            int considered = 0;
            for (IsoMovingObject object : objects) {
                if (!(object instanceof IsoZombie zombie) || zombie.isDead() || zombie.isReanimatedForGrappleOnly() ||
                        zombie.getCurrentSquare() == null ||
                        !ServerLOS.instance.isCouldSee(player, zombie.getCurrentSquare())) continue;
                cycle.consider(probe.sample(zombie, player, modern));
                considered++;
            }
            cycles++; candidates += considered;
            int earned = cycle.sneak() + cycle.lightfoot();
            float previousSneak = player.getXp().getXP(PerkFactory.Perks.Sneak);
            float previousLightfoot = player.getXp().getXP(PerkFactory.Perks.Lightfoot);
            for (int i = 0; i < cycle.sneak(); i++) GameServer.addXp(player, PerkFactory.Perks.Sneak, 1f);
            for (int i = 0; i < cycle.lightfoot(); i++) GameServer.addXp(player, PerkFactory.Perks.Lightfoot, 1f);
            if (player.getXp().getXP(PerkFactory.Perks.Sneak) != previousSneak ||
                    player.getXp().getXP(PerkFactory.Perks.Lightfoot) != previousLightfoot) {
                player.getNetworkCharacterAI().syncXp();
                awards += earned;
                if (!state.announced) {
                    state.announced = true;
                    log("First XP award for onlineID=" + player.getOnlineID() + "; server PlayerXp sync sent.");
                }
            }
            if (now >= nextSummary) {
                nextSummary = now + 60_000_000_000L;
                log("Counters: moving-player cycles=" + cycles + ", sight candidates=" + candidates + ", XP rolls in changed batches=" + awards);
            }
        } catch (Throwable error) { disable(error); }
    }

    private static void disable(Throwable error) {
        enabled = false; PLAYERS.clear();
        log("DISABLED; Vanilla XP path retained. " + error);
        error.printStackTrace();
    }
    private static void log(String message) { System.out.println("[StealthXPMPFix] " + message); }
}
