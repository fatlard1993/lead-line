package justfatlard.lead_line.mixin;

import justfatlard.lead_line.ArrowLeads;
import justfatlard.lead_line.Tethers;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The golem holding the far end of a lead in a player's hand lets it run as far as the player walks,
 * near enough: to {@link Tethers#LONGEST} rather than vanilla's twelve, which is too short to measure a
 * build with. A lead arrow's runs as far whether a player or a fence holds it, so a rope shot up a
 * cliff can be tied off at its foot. Every other golem, and a post's once its lead is tied to a second
 * post, snaps where vanilla does.
 */
@Mixin(CopperGolem.class)
public abstract class CopperGolemMixin implements Leashable {
	private static final double VANILLA_SNAP = 12.0;

	@Override
	public double leashSnapDistance() {
		CopperGolem self = (CopperGolem) (Object) this;
		return ArrowLeads.isHolder(self) || Tethers.holdsForPlayer(self) ? Tethers.LONGEST : VANILLA_SNAP;
	}
}
