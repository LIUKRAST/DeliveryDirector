package net.liukrast.dd.content;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardBlockEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.crate.BottomlessItemHandler;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerItemHandler;
import com.simibubi.create.content.logistics.packager.PackagingRequest;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.INamedIconOptions;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import com.simibubi.create.foundation.gui.AllIcons;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.data.Pair;
import net.liukrast.dd.DeliveryDirector;
import net.liukrast.dd.DeliveryDirectorIcons;
import net.liukrast.dd.registry.RegisterBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class PackageRewriterBlockEntity extends PackagerBlockEntity {
    protected ScrollOptionBehaviour<PackageRewriterBlockEntity.SelectionMode> selectionMode;
    public PackageRewriterBlockEntity(BlockPos pos, BlockState state) {
        super(RegisterBlockEntityTypes.PACKAGE_REWRITER.get(), pos, state);
    }

    protected Pair<String, String> getRegexInfo() {
        for(Direction side : Direction.values()) {
            assert level != null;
            BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(side));
            if(blockEntity instanceof SignBlockEntity sign) {
                for (boolean front : Iterate.trueAndFalse) {
                    SignText text = sign.getText(front);
                    String regex = text.getMessages(false)[0].getString();
                    String replacement = text.getMessages(false)[1].getString();
                    if (!regex.isBlank())
                        return Pair.of(regex, replacement);
                }
            } else if(blockEntity instanceof ClipboardBlockEntity clipboard) {
                var data = clipboard.dataContainer.get(AllDataComponents.CLIPBOARD_PAGES);
                if(data == null) continue;
                var firstPage = data.getFirst();
                if(firstPage == null) continue;
                if(firstPage.isEmpty()) continue;
                var regex = firstPage.getFirst().text.getString();
                var replacement = firstPage.size() > 1 ? firstPage.get(1).text.getString() : "";
                if (!regex.isBlank())
                    return Pair.of(regex, replacement);
            }
        }
        return null;
    }

    public Pair<String, String> validateRegex(Pair<String, String> regex, String current) {
        String regexString;
        String replacementString;
        try {
            // Compile regex and get pattern string
            Pattern pattern = Pattern.compile(regex.getFirst());
            regexString = pattern.pattern();
            int groupCount = pattern.matcher("").groupCount();

            // Check replacement string for backreferences like $1, $2, ...
            Pattern backrefPattern = Pattern.compile("\\$(\\d+)");
            Matcher m = backrefPattern.matcher(regex.getSecond());
            boolean invalidGroupReference = false;
            while (m.find()) {
                int ref = Integer.parseInt(m.group(1));
                if (ref > groupCount) {
                    invalidGroupReference = true;
                    break;
                }
            }
            // Catch exceptions and turn expressions into literal
            if (invalidGroupReference) {
                replacementString = Matcher.quoteReplacement(regex.getSecond());
            } else {
                pattern.matcher(current).replaceAll(regex.getSecond());
                replacementString = regex.getSecond();
            }
        } catch (PatternSyntaxException e) {
            regexString = Pattern.quote(regex.getFirst());
            replacementString = Matcher.quoteReplacement(regex.getSecond());
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            replacementString = Matcher.quoteReplacement(regex.getSecond());
            try {
                regexString = Pattern.compile(regex.getFirst()).pattern();
            } catch (Exception ex) {
                regexString = Pattern.quote(regex.getFirst());
            }
        }
        return Pair.of(regexString, replacementString);
    }

    @Override
    public boolean unwrapBox(ItemStack box, boolean simulate) {
        if(!getBlockState().getValue(PackageRewriterBlock.POWERED)) return false;
        if (animationTicks > 0)
            return false;

        IItemHandler targetInv = targetInventory.getInventory();
        if (targetInv == null || targetInv instanceof PackagerItemHandler)
            return false;

        boolean targetIsCreativeCrate = targetInv instanceof BottomlessItemHandler;
        boolean anySpace = false;

        for (int slot = 0; slot < targetInv.getSlots(); slot++) {
            ItemStack remainder = targetInv.insertItem(slot, box, simulate);
            if (!remainder.isEmpty())
                continue;
            anySpace = true;
            break;
        }

        if (!targetIsCreativeCrate && !anySpace)
            return false;
        if (simulate)
            return true;

        String current = PackageItem.getAddress(box);
        var regex = getRegexInfo();
        if (regex != null) {
            if (selectionMode.get() == SelectionMode.FULL_REGEX) {
                Pair<String, String> correctRegex = validateRegex(regex, current);
                PackageItem.addAddress(box, current.replaceAll(correctRegex.getFirst(), correctRegex.getSecond()));
            }else {
                PackageItem.addAddress(box, current.replaceAll(SimplifiedPatternTransformer.toRegexPattern(regex.getFirst(), ""), SimplifiedPatternTransformer.transformReplacement(regex.getSecond())));
            }
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
        if (!heldBox.isEmpty() || animationTicks != 0 || buttonCooldown > 0)
            return;
        if (!queuedExitingPackages.isEmpty())
            return;

        IItemHandler targetInv = targetInventory.getInventory();
        if (targetInv == null || targetInv instanceof PackagerItemHandler)
            return;

        for(int slot = 0; slot < targetInv.getSlots(); slot++) {
            ItemStack extracted = targetInv.extractItem(slot, 1, true);
            if(extracted.isEmpty() || !PackageItem.isPackage(extracted))
                continue;

            targetInv.extractItem(slot, 1, false);
            heldBox = extracted.copy();
            animationInward = false;
            animationTicks = CYCLE;
            notifyUpdate();
            break;
        }

        if(heldBox.isEmpty())
            return;

        String current = PackageItem.getAddress(heldBox);
        var regex = getRegexInfo();
        if (regex != null) {
            if (selectionMode.get() == SelectionMode.FULL_REGEX) {
                Pair<String, String> correctRegex = validateRegex(regex, current);
                PackageItem.addAddress(heldBox, current.replaceAll(correctRegex.getFirst(), correctRegex.getSecond()));
            }else {
                PackageItem.addAddress(heldBox, current.replaceAll(SimplifiedPatternTransformer.toRegexPattern(regex.getFirst(), ""), SimplifiedPatternTransformer.transformReplacement(regex.getSecond())));
            }
        }
        notifyUpdate();
    }

    @Override
    public void recheckIfLinksPresent() {}

    @Override
    public boolean redstoneModeActive() {
        return true;
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        behaviours.add(selectionMode = new ScrollOptionBehaviour<>(SelectionMode.class,
                Component.translatable("delivery_director.regex_mode"), this, new PackageRewriterModeSlot()));
    }

    public enum SelectionMode implements INamedIconOptions {

        SIMPLIFIED_REGEX(DeliveryDirectorIcons.I_SIMPLIFIED_REGEX, "simplified"),
        FULL_REGEX(DeliveryDirectorIcons.I_FULL_REGEX, "full")
        ;

        private final String translationKey;
        private final AllIcons icon;

        SelectionMode(AllIcons icon, String translationKey) {
            this.icon = icon;
            this.translationKey = DeliveryDirector.MOD_ID + ".regex_mode." + translationKey;
        }

        @Override
        public AllIcons getIcon() {
            return icon;
        }

        @Override
        public String getTranslationKey() {
            return translationKey;
        }
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                RegisterBlockEntityTypes.PACKAGE_REWRITER.get(),
                (be, context) -> be.inventory
        );
    }
}
