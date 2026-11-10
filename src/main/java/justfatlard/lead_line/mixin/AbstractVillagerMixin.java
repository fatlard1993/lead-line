package justfatlard.lead_line.mixin;

import net.minecraft.world.entity.npc.villager.AbstractVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Villagers and wandering traders wear a lead, to be walked to a trading hall or a new village. */
@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerMixin {
	@Inject(method = "canBeLeashed", at = @At("HEAD"), cancellable = true)
	private void leadLine$leadable(CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(true);
	}
}
