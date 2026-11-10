package justfatlard.lead_line.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractArrow.class)
public interface AbstractArrowAccessor {
	@Invoker("setPickupItemStack")
	void leadLine$setPickupItemStack(ItemStack stack);

	@Invoker("isInGround")
	boolean leadLine$isInGround();
}
