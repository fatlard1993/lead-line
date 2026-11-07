package justfatlard.lead_line.mixin;

import justfatlard.lead_line.Tethers;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The golem holding the post end of a lead in a player's hand lets it run as far as the player
 * walks, near enough: to {@link Tethers#LONGEST} rather than vanilla's twelve, which is too short to
 * measure a build with. Every other golem, and this one once the lead is tied to a second post,
 * snaps where vanilla does.
 */
@Mixin(CopperGolem.class)
public abstract class CopperGolemMixin implements Leashable {
	private static final double VANILLA_SNAP = 12.0;

	@Override
	public double leashSnapDistance() {
		return Tethers.holdsForPlayer((CopperGolem) (Object) this) ? Tethers.LONGEST : VANILLA_SNAP;
	}
}
