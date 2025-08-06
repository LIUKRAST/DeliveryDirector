package net.liukrast.dd.content;

import com.simibubi.create.content.equipment.clipboard.ClipboardBlockEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.crate.BottomlessItemHandler;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerItemHandler;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.nbt.NBTHelper;
import net.liukrast.dd.registry.RegisterBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import ru.zznty.create_factory_logistics.logistics.composite.CompositePackageItem;

import java.util.ArrayList;
import java.util.List;

public class PackageRewriterBlockEntity extends PackagerBlockEntity {
    // new field to expose our handler
    private final LazyOptional<IItemHandler> compositeInvProvider;

    public PackageRewriterBlockEntity(BlockPos pos, BlockState state) {
        super(RegisterBlockEntityTypes.PACKAGE_REWRITER.get(), pos, state);

        // swap in a handler that also accepts composite packages
        this.inventory = new PackagerItemHandler(this) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return super.isItemValid(slot, stack)
                        || stack.getItem() instanceof CompositePackageItem;
            }
        };

        compositeInvProvider = LazyOptional.of(() -> this.inventory);
    }

    @Override
    public <T> LazyOptional<T> getCapability(net.minecraftforge.common.capabilities.Capability<T> cap, Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return compositeInvProvider.cast();
        }
        return super.getCapability(cap, side);
    }

    protected Pair<String, String> getRegexInfo() {
        for (Direction side : Direction.values()) {
            assert level != null;
            BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(side));
            if (blockEntity instanceof SignBlockEntity sign) {
                for (boolean front : Iterate.trueAndFalse) {
                    SignText text = sign.getText(front);
                    String regex = text.getMessages(false)[0].getString();
                    String replacement = text.getMessages(false)[1].getString();
                    if (!regex.isBlank()) {
                        return Pair.of(regex, replacement);
                    }
                }
            } else if(blockEntity instanceof ClipboardBlockEntity clipboard) {
                var tag = clipboard.dataContainer.getTag();
                if(tag == null) continue;
                var pages = tag.getList("Pages", Tag.TAG_COMPOUND);
                if(pages.isEmpty()) continue;
                var page0 = pages.getCompound(0);
                var entries = page0.getList("Entries", Tag.TAG_COMPOUND);
                if(entries.isEmpty()) continue;
                var comp0 = Component.Serializer.fromJson(entries.getCompound(0).getString("Text"));
                if(comp0 == null) continue;
                String regex = comp0.getString();
                String replacement = "";
                if (entries.size() > 1) {
                    var comp1 = Component.Serializer.fromJson(entries.getCompound(1).getString("Text"));
                    if (comp1 != null) replacement = comp1.getString();
                }
                if (!regex.isBlank()) {
                    return Pair.of(regex, replacement);
                }
            }
        }
        return null;
    }

    @Override
    public boolean unwrapBox(ItemStack box, boolean simulate) {
        if(!getBlockState().getValue(PackageRewriterBlock.POWERED)) return false;
        if (animationTicks > 0) return false;

        IItemHandler targetInv = targetInventory.getInventory();
        if (targetInv == null || targetInv instanceof PackagerItemHandler) return false;

        boolean targetIsCreativeCrate = targetInv instanceof BottomlessItemHandler;
        boolean anySpace = false;
        for (int slot = 0; slot < targetInv.getSlots(); slot++) {
            ItemStack remainder = targetInv.insertItem(slot, box, simulate);
            if (remainder.isEmpty()) {
                anySpace = true;
                break;
            }
        }
        if (!targetIsCreativeCrate && !anySpace) return false;
        if (simulate) return true;

        var regex = getRegexInfo();
        if(regex != null) {
            applyRegexToBoxAndChildren(box, regex.getFirst(), regex.getSecond());
        }

        notifyUpdate();
        previouslyUnwrapped = box;
        animationInward = true;
        animationTicks = CYCLE;
        notifyUpdate();
        return true;
    }

    @Override
    public void attemptToSend(List<PackagingRequest> queuedRequests) {
        if (!heldBox.isEmpty() || animationTicks != 0 || buttonCooldown > 0) return;
        if (!queuedExitingPackages.isEmpty()) return;

        IItemHandler targetInv = targetInventory.getInventory();
        if (targetInv == null || targetInv instanceof PackagerItemHandler) return;

        for(int slot = 0; slot < targetInv.getSlots(); slot++) {
            ItemStack extracted = targetInv.extractItem(slot, 1, true);
            if(extracted.isEmpty() || !PackageItem.isPackage(extracted)) continue;
            targetInv.extractItem(slot, 1, false);
            heldBox = extracted.copy();
            animationInward = false;
            animationTicks = CYCLE;
            notifyUpdate();
            break;
        }
        if (heldBox.isEmpty()) return;

        var regex = getRegexInfo();
        if(regex != null) {
            applyRegexToBoxAndChildren(heldBox, regex.getFirst(), regex.getSecond());
        }
        notifyUpdate();
    }

    // new helper to rewrite nested composite children
    private void applyRegexToBoxAndChildren(ItemStack box, String regex, String replacement) {
        if (box.hasTag() && box.getTag().contains("Address")) {
            String oldAddr = box.getTag().getString("Address");
            box.getTag().putString("Address", oldAddr.replaceAll(regex, replacement));
        }
        if (box.getItem() instanceof CompositePackageItem) {
            List<ItemStack> children = CompositePackageItem.getChildren(box);
            List<ItemStack> rewritten = new ArrayList<>();
            for (ItemStack child : children) {
                applyRegexToBoxAndChildren(child, regex, replacement);
                rewritten.add(child);
            }
            ListTag newChildrenTag = NBTHelper.writeCompoundList(rewritten, child -> child.save(new CompoundTag()));
            box.getOrCreateTag().put(CompositePackageItem.CHILDREN_TAG, newChildrenTag);
        }
    }

    @Override
    public void recheckIfLinksPresent() {}

    @Override
    public boolean redstoneModeActive() {
        return true;
    }
}
