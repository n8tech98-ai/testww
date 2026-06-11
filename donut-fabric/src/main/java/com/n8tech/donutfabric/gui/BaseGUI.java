package com.n8tech.donutfabric.gui;

import com.n8tech.donutfabric.utils.ChatUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared base for all server-side GUI screens.
 * Uses a 54-slot (6-row) chest inventory unless overridden.
 *
 * MC 1.21.1: ItemStack uses Data Components instead of NBT directly.
 * - setCustomName  → stack.set(DataComponentTypes.CUSTOM_NAME, text)
 * - setLore        → stack.set(DataComponentTypes.LORE, new LoreComponent(...))
 */
public abstract class BaseGUI {

    protected static final int ROWS = 6;
    protected static final int COLS = 9;
    protected static final int SIZE = ROWS * COLS; // 54

    protected static final int SLOT_PREV   = 45;
    protected static final int SLOT_INFO   = 49;
    protected static final int SLOT_NEXT   = 53;
    protected static final int SLOT_BACK   = 47;
    protected static final int SLOT_SEARCH = 48;
    protected static final int SLOT_SORT   = 50;
    protected static final int SLOT_CREATE = 52;

    // -----------------------------------------------------------------------
    //  GUI item factories
    // -----------------------------------------------------------------------

    protected static ItemStack filler(Item glass) {
        ItemStack stack = new ItemStack(glass);
        setName(stack, " ");
        return stack;
    }

    protected static ItemStack makeItem(Item item, String name, String... loreLines) {
        ItemStack stack = new ItemStack(item);
        setName(stack, name);
        if (loreLines.length > 0) setLore(stack, Arrays.asList(loreLines));
        return stack;
    }

    protected static ItemStack prevPage(int currentPage) {
        return makeItem(Items.ARROW, "&7◀ Previous Page", "&8Page " + currentPage);
    }

    protected static ItemStack nextPage(int currentPage, int totalPages) {
        return makeItem(Items.ARROW, "&7▶ Next Page", "&8Page " + currentPage + "/" + totalPages);
    }

    protected static void fillBorder(SimpleInventory inv) {
        ItemStack border = filler(Items.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < 9; i++) inv.setStack(i, border.copy());
        for (int i = 45; i < 54; i++) inv.setStack(i, border.copy());
        for (int row = 1; row < 5; row++) {
            inv.setStack(row * 9,     border.copy());
            inv.setStack(row * 9 + 8, border.copy());
        }
    }

    protected static void fillEmpty(SimpleInventory inv) {
        ItemStack gray = filler(Items.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < SIZE; i++) {
            if (inv.getStack(i).isEmpty()) inv.setStack(i, gray.copy());
        }
    }

    // -----------------------------------------------------------------------
    //  Name / lore via Data Components (MC 1.21.1+)
    // -----------------------------------------------------------------------

    protected static void setName(ItemStack stack, String coloredName) {
        // DataComponentTypes.CUSTOM_NAME replaces setCustomName() in 1.21.1
        stack.set(DataComponentTypes.CUSTOM_NAME,
            Text.literal(ChatUtil.color(coloredName))
                .styled(s -> s.withItalic(false)));
    }

    protected static void setLore(ItemStack stack, List<String> lines) {
        List<Text> loreTexts = new ArrayList<>();
        for (String line : lines) {
            loreTexts.add(Text.literal(ChatUtil.color(line))
                .styled(s -> s.withItalic(false)));
        }
        // LoreComponent replaces NBT display.Lore in 1.21.1
        stack.set(DataComponentTypes.LORE, new LoreComponent(loreTexts));
    }

    // -----------------------------------------------------------------------
    //  Open helpers
    // -----------------------------------------------------------------------

    protected static void openInventory(ServerPlayerEntity player,
                                        SimpleInventory inv,
                                        String title) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInv, p) ->
                new GenericContainerScreenHandler(
                    ScreenHandlerType.GENERIC_9X6, syncId, playerInv, inv, 6),
            Text.literal(ChatUtil.color(title))
        ));
    }

    protected static void openInventory4Row(ServerPlayerEntity player,
                                            SimpleInventory inv,
                                            String title) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInv, p) ->
                new GenericContainerScreenHandler(
                    ScreenHandlerType.GENERIC_9X4, syncId, playerInv, inv, 4),
            Text.literal(ChatUtil.color(title))
        ));
    }
}
