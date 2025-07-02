package net.liukrast.dd.content;

import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import net.minecraft.core.Direction;

public class PackageRewriterModeSlot extends CenteredSideValueBoxTransform {

	public PackageRewriterModeSlot() {
		super((state, dir) -> {
			Direction facing = state.getValue(PackageRewriterBlock.FACING);
			return dir != Direction.DOWN && dir != facing.getOpposite();
		});
	}
}
