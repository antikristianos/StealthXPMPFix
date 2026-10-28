package antikristianos.stealthxpmpfix;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoPlayer;

@Patch(className = "zombie.characters.IsoPlayer", methodName = "update", warmUp = true, strictMatch = true)
public final class PatchPlayerUpdate {
    @Patch.OnExit
    public static void exit(@Patch.This IsoPlayer player) { ServerStealthXP.tick(player); }
}
