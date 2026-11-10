package justfatlard.lead_line.mixin;

import justfatlard.lead_line.ArrowLeads;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A lead arrow ties where it sticks or leads what it strikes, and a tied one neither despawns nor comes out from under someone on it; see {@link ArrowLeads}. */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {

	/** At the tail, once the game has settled the arrow where it stuck. */
	@Inject(method = "onHitBlock", at = @At("TAIL"))
	private void leadLine$stuck(BlockHitResult hit, CallbackInfo ci) {
		ArrowLeads.stuck((AbstractArrow) (Object) this);
	}

	@Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true)
	private void leadLine$struck(EntityHitResult hit, CallbackInfo ci) {
		if (ArrowLeads.struck((AbstractArrow) (Object) this, hit.getEntity())) ci.cancel();
	}

	@Inject(method = "tickDespawn", at = @At("HEAD"), cancellable = true)
	private void leadLine$keep(CallbackInfo ci) {
		if (ArrowLeads.isTied((AbstractArrow) (Object) this)) ci.cancel();
	}

	@Inject(method = "tryPickup", at = @At("HEAD"), cancellable = true)
	private void leadLine$holdFast(Player player, CallbackInfoReturnable<Boolean> cir) {
		if (ArrowLeads.keepsHold((AbstractArrow) (Object) this, player)) cir.setReturnValue(false);
	}
}
