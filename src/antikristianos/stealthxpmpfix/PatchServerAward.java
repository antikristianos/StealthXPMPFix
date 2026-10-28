package antikristianos.stealthxpmpfix;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoPlayer;
import zombie.characters.skills.PerkFactory;

@Patch(className = "zombie.network.GameServer", methodName = "addXp", warmUp = true, strictMatch = true)
public final class PatchServerAward {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.Argument(0) IsoPlayer player, @Patch.Argument(1) PerkFactory.Perk perk,
            @Patch.Argument(2) float amount, @Patch.Argument(3) boolean noMultiplier, @Patch.Argument(4) boolean showXp) {
        return ServerStealthXP.interceptAward(player, perk, amount);
    }
}
