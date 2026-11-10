package justfatlard.lead_line;

import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * An arrow with a lead knotted to it, fired as the game's own arrow marked with {@link ArrowLeads#TAG}.
 * The game's arrow, so every client draws it and every bow, crossbow and dispenser fires it; where it
 * sticks, {@link ArrowLeads} ties its lead back to whoever shot it.
 */
public class LeadArrowItem extends ArrowItem {
	public LeadArrowItem(Properties properties) {
		super(properties);
	}

	@Override
	public AbstractArrow createArrow(Level level, ItemStack itemStack, LivingEntity owner, @Nullable ItemStack firedFromWeapon) {
		return marked(new Arrow(level, owner, itemStack.copyWithCount(1), firedFromWeapon));
	}

	@Override
	public Projectile asProjectile(Level level, Position position, ItemStack itemStack, Direction direction) {
		Arrow arrow = new Arrow(level, position.x(), position.y(), position.z(), itemStack.copyWithCount(1), null);
		arrow.pickup = AbstractArrow.Pickup.ALLOWED;
		return marked(arrow);
	}

	private static Arrow marked(Arrow arrow) {
		arrow.addTag(ArrowLeads.TAG);
		return arrow;
	}
}
