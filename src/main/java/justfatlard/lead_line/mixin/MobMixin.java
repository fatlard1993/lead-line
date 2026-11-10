package justfatlard.lead_line.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A zombie villager wears a lead, to be led off somewhere to be cured, and a piglin, to be led to where
 * it barters. The game keeps leads off every monster in one place, here; these are let through. A
 * piglin brute, which nobody barters with, is not.
 */
@Mixin(Mob.class)
public abstract class MobMixin {
	@Inject(method = "canBeLeashed", at = @At("RETURN"), cancellable = true)
	private void leadLine$leadable(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ZombieVillager || (Object) this instanceof Piglin) cir.setReturnValue(true);
	}
}
