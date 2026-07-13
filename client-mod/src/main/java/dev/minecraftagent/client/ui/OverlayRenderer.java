package dev.minecraftagent.client.ui;

import dev.minecraftagent.client.ui.OverlayController.OverlaySnapshot;
import dev.minecraftagent.client.view.ItemListView;
import dev.minecraftagent.client.view.ItemStackView;
import dev.minecraftagent.client.view.MinecraftItemStackResolver;
import dev.minecraftagent.client.view.MinecraftItemStackResolver.ResolvedItemStack;
import dev.minecraftagent.client.view.RecipeView;
import dev.minecraftagent.client.view.RecipeView.IngredientSlot;
import dev.minecraftagent.client.view.RecipeView.Recipe;
import dev.minecraftagent.client.view.StructuredView;
import dev.minecraftagent.client.view.TextView;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Stable-size HUD renderer with explicit mouse entry points for the client initializer. */
public final class OverlayRenderer {
  public static final int HEADER_HEIGHT = 22;
  public static final int CONTENT_PADDING = 8;

  private static final int BUTTON_SIZE = 16;
  private static final int RESIZE_HANDLE = 10;
  private static final int BACKGROUND = 0xe6191b1f;
  private static final int HEADER_BACKGROUND = 0xf02a2d32;
  private static final int BORDER = 0xff737a83;
  private static final int ACCENT = 0xff43b5a0;
  private static final int PINNED = 0xffffc857;
  private static final int PRIMARY_TEXT = 0xfff3f5f7;
  private static final int SECONDARY_TEXT = 0xffb9c0c7;
  private static final int SLOT_BACKGROUND = 0xff30343a;
  private static final int MISSING = 0xffff5c6c;

  private final Minecraft minecraft;
  private final OverlayController controller;
  private final MinecraftItemStackResolver itemResolver;
  private final Map<ItemStackView, ResolvedItemStack> itemCache = new HashMap<>();

  private volatile OverlayBounds lastBounds;
  private UUID cachedViewId;
  private int cachedRevision;
  private int cachedTextWidth = -1;
  private List<FormattedCharSequence> cachedTextLines = List.of();
  private Interaction interaction = Interaction.NONE;
  private int dragOffsetX;
  private int dragOffsetY;

  public OverlayRenderer(Minecraft minecraft, OverlayController controller) {
    this(minecraft, controller, new MinecraftItemStackResolver());
  }

  public OverlayRenderer(
      Minecraft minecraft, OverlayController controller, MinecraftItemStackResolver itemResolver) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.controller = Objects.requireNonNull(controller, "controller");
    this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");
  }

  public OverlayController controller() {
    return controller;
  }

  public void render(GuiGraphics graphics, DeltaTracker ignoredTickCounter) {
    Optional<OverlaySnapshot> optionalSnapshot = controller.snapshot();
    if (optionalSnapshot.isEmpty()) {
      lastBounds = null;
      itemCache.clear();
      cachedViewId = null;
      cachedTextLines = List.of();
      cachedTextWidth = -1;
      return;
    }

    OverlaySnapshot snapshot = optionalSnapshot.get();
    StructuredView view = snapshot.view();
    refreshItemCache(view);
    OverlayBounds bounds = controller.bounds(graphics.guiWidth(), graphics.guiHeight());
    lastBounds = bounds;

    graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), BACKGROUND);
    graphics.fill(
        bounds.x(), bounds.y(), bounds.right(), bounds.y() + HEADER_HEIGHT, HEADER_BACKGROUND);
    graphics.renderOutline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), BORDER);
    graphics.fill(bounds.x(), bounds.y(), bounds.x() + 3, bounds.bottom(), ACCENT);

    int controlsWidth =
        view.pinnable() || snapshot.pinned() ? BUTTON_SIZE * 2 + 6 : BUTTON_SIZE + 4;
    int titleWidth = Math.max(8, bounds.width() - CONTENT_PADDING * 2 - controlsWidth);
    String title = minecraft.font.plainSubstrByWidth(view.title(), titleWidth);
    graphics.drawString(
        minecraft.font, title, bounds.x() + CONTENT_PADDING, bounds.y() + 7, PRIMARY_TEXT, false);
    renderHeaderControls(graphics, bounds, snapshot);
    renderResizeHandle(graphics, bounds);

    int contentX = bounds.x() + CONTENT_PADDING;
    int contentY = bounds.y() + HEADER_HEIGHT + 5;
    int contentWidth = Math.max(1, bounds.width() - CONTENT_PADDING * 2);
    int viewportHeight = Math.max(0, bounds.height() - HEADER_HEIGHT - CONTENT_PADDING - 3);
    int contentHeight = measureContent(view, contentWidth);
    controller.setViewportMetrics(contentHeight, viewportHeight);
    snapshot = controller.snapshot().orElse(snapshot);

    if (viewportHeight > 0) {
      graphics.enableScissor(
          bounds.x() + 3, contentY, bounds.right() - 3, contentY + viewportHeight);
      renderContent(
          graphics,
          view,
          contentX,
          contentY - snapshot.scroll(),
          contentWidth,
          contentY,
          contentY + viewportHeight);
      graphics.disableScissor();
    }
  }

  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    OverlayBounds bounds = lastBounds;
    if (button != 0 || bounds == null || !bounds.contains(mouseX, mouseY)) {
      return false;
    }
    Optional<OverlaySnapshot> snapshot = controller.snapshot();
    if (snapshot.isEmpty()) {
      return false;
    }
    if (closeButton(bounds).contains(mouseX, mouseY)) {
      controller.close();
      interaction = Interaction.NONE;
      return true;
    }
    if ((snapshot.get().view().pinnable() || snapshot.get().pinned())
        && pinButton(bounds).contains(mouseX, mouseY)) {
      controller.togglePin();
      interaction = Interaction.NONE;
      return true;
    }
    if (resizeButton(bounds).contains(mouseX, mouseY)) {
      interaction = Interaction.RESIZE;
      return true;
    }
    if (mouseY < bounds.y() + HEADER_HEIGHT) {
      interaction = Interaction.DRAG;
      dragOffsetX = (int) mouseX - bounds.x();
      dragOffsetY = (int) mouseY - bounds.y();
      return true;
    }
    return false;
  }

  public boolean mouseDragged(double mouseX, double mouseY, int button) {
    OverlayBounds bounds = lastBounds;
    if (button != 0 || bounds == null || interaction == Interaction.NONE) {
      return false;
    }
    if (interaction == Interaction.DRAG) {
      controller.moveTo(
          (int) mouseX - dragOffsetX,
          (int) mouseY - dragOffsetY,
          minecraft.getWindow().getGuiScaledWidth(),
          minecraft.getWindow().getGuiScaledHeight());
    } else {
      controller.resizeTo(
          (int) mouseX - bounds.x(),
          (int) mouseY - bounds.y(),
          minecraft.getWindow().getGuiScaledWidth(),
          minecraft.getWindow().getGuiScaledHeight());
    }
    return true;
  }

  public boolean mouseReleased(double mouseX, double mouseY, int button) {
    if (button != 0 || interaction == Interaction.NONE) {
      return false;
    }
    interaction = Interaction.NONE;
    controller.flushPreferences();
    return true;
  }

  /** Ends an interrupted gesture, for example when the interaction screen closes. */
  public void cancelInteraction() {
    interaction = Interaction.NONE;
    controller.flushPreferences();
  }

  public boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
    OverlayBounds bounds = lastBounds;
    if (bounds == null || !bounds.contains(mouseX, mouseY) || verticalAmount == 0) {
      return false;
    }
    controller.scrollBy(verticalAmount > 0 ? -18 : 18);
    return true;
  }

  private void refreshItemCache(StructuredView view) {
    if (!view.viewId().equals(cachedViewId) || view.revision() != cachedRevision) {
      itemCache.clear();
      cachedViewId = view.viewId();
      cachedRevision = view.revision();
      cachedTextLines = List.of();
      cachedTextWidth = -1;
    }
  }

  private void renderHeaderControls(
      GuiGraphics graphics, OverlayBounds bounds, OverlaySnapshot snapshot) {
    int mouseX = scaledMouseX();
    int mouseY = scaledMouseY();
    OverlayBounds close = closeButton(bounds);
    graphics.drawCenteredString(
        minecraft.font,
        Component.literal("\u00d7"),
        close.x() + close.width() / 2,
        close.y() + 4,
        PRIMARY_TEXT);
    if (close.contains(mouseX, mouseY)) {
      graphics.setTooltipForNextFrame(
          minecraft.font, Component.literal("Close view"), mouseX, mouseY);
    }

    if (snapshot.view().pinnable() || snapshot.pinned()) {
      OverlayBounds pin = pinButton(bounds);
      graphics.drawCenteredString(
          minecraft.font,
          Component.literal(snapshot.pinned() ? "\u25c6" : "\u25c7"),
          pin.x() + pin.width() / 2,
          pin.y() + 4,
          snapshot.pinned() ? PINNED : SECONDARY_TEXT);
      if (pin.contains(mouseX, mouseY)) {
        graphics.setTooltipForNextFrame(
            minecraft.font,
            Component.literal(snapshot.pinned() ? "Unpin view" : "Pin view"),
            mouseX,
            mouseY);
      }
    }
  }

  private static void renderResizeHandle(GuiGraphics graphics, OverlayBounds bounds) {
    int right = bounds.right() - 3;
    int bottom = bounds.bottom() - 3;
    graphics.fill(right - 2, bottom - 2, right, bottom, BORDER);
    graphics.fill(right - 6, bottom - 2, right - 4, bottom, BORDER);
    graphics.fill(right - 2, bottom - 6, right, bottom - 4, BORDER);
  }

  private int measureContent(StructuredView view, int width) {
    return switch (view.content()) {
      case TextView text ->
          Math.max(
              minecraft.font.lineHeight,
              textLines(text, width).size() * (minecraft.font.lineHeight + 2));
      case ItemStackView ignored -> 52;
      case ItemListView list -> list.items().size() * 22;
      case RecipeView ignored -> 112;
    };
  }

  private void renderContent(
      GuiGraphics graphics,
      StructuredView view,
      int x,
      int y,
      int width,
      int clipTop,
      int clipBottom) {
    switch (view.content()) {
      case TextView text -> renderText(graphics, text, x, y, width, clipTop, clipBottom);
      case ItemStackView item ->
          renderItemStackView(graphics, item, x, y, width, clipTop, clipBottom);
      case ItemListView list -> renderItemList(graphics, list, x, y, width, clipTop, clipBottom);
      case RecipeView recipe -> renderRecipe(graphics, recipe, x, y, width, clipTop, clipBottom);
    }
  }

  private void renderText(
      GuiGraphics graphics,
      TextView content,
      int x,
      int y,
      int width,
      int clipTop,
      int clipBottom) {
    List<FormattedCharSequence> lines = textLines(content, width);
    int lineHeight = minecraft.font.lineHeight + 2;
    for (int index = 0; index < lines.size(); index++) {
      int lineY = y + index * lineHeight;
      if (lineY + lineHeight >= clipTop && lineY < clipBottom) {
        graphics.drawString(minecraft.font, lines.get(index), x, lineY, PRIMARY_TEXT, false);
      }
    }
  }

  private void renderItemStackView(
      GuiGraphics graphics,
      ItemStackView item,
      int x,
      int y,
      int width,
      int clipTop,
      int clipBottom) {
    ResolvedItemStack resolved = resolve(item);
    renderItem(graphics, resolved, x, y, clipTop, clipBottom);
    String name = displayName(resolved);
    int labelWidth = Math.max(1, width - 24);
    graphics.drawString(
        minecraft.font,
        minecraft.font.plainSubstrByWidth(name, labelWidth),
        x + 24,
        y,
        resolved.available() ? PRIMARY_TEXT : MISSING,
        false);
    graphics.drawString(
        minecraft.font,
        "x" + item.count(),
        x + 24,
        y + minecraft.font.lineHeight + 3,
        SECONDARY_TEXT,
        false);
    graphics.drawString(
        minecraft.font,
        minecraft.font.plainSubstrByWidth(item.itemId(), labelWidth),
        x + 24,
        y + (minecraft.font.lineHeight + 3) * 2,
        SECONDARY_TEXT,
        false);
  }

  private void renderItemList(
      GuiGraphics graphics,
      ItemListView list,
      int x,
      int y,
      int width,
      int clipTop,
      int clipBottom) {
    for (int index = 0; index < list.items().size(); index++) {
      int rowY = y + index * 22;
      if (rowY + 20 < clipTop || rowY >= clipBottom) {
        continue;
      }
      ItemStackView item = list.items().get(index);
      ResolvedItemStack resolved = resolve(item);
      renderItem(graphics, resolved, x, rowY, clipTop, clipBottom);
      int labelWidth = Math.max(1, width - 26 - minecraft.font.width("x" + item.count()));
      graphics.drawString(
          minecraft.font,
          minecraft.font.plainSubstrByWidth(displayName(resolved), labelWidth),
          x + 22,
          rowY + 1,
          resolved.available() ? PRIMARY_TEXT : MISSING,
          false);
      String count = "x" + item.count();
      graphics.drawString(
          minecraft.font,
          count,
          x + width - minecraft.font.width(count),
          rowY + 1,
          SECONDARY_TEXT,
          false);
      graphics.drawString(
          minecraft.font,
          minecraft.font.plainSubstrByWidth(item.itemId(), Math.max(1, width - 22)),
          x + 22,
          rowY + 11,
          SECONDARY_TEXT,
          false);
    }
  }

  private void renderRecipe(
      GuiGraphics graphics,
      RecipeView recipeView,
      int x,
      int y,
      int width,
      int clipTop,
      int clipBottom) {
    Recipe recipe = recipeView.recipes().get(recipeView.selectedRecipe());
    String index = (recipeView.selectedRecipe() + 1) + "/" + recipeView.recipes().size();
    graphics.drawString(
        minecraft.font,
        minecraft.font.plainSubstrByWidth(
            recipe.recipeId(), Math.max(1, width - minecraft.font.width(index) - 8)),
        x,
        y,
        PRIMARY_TEXT,
        false);
    graphics.drawString(
        minecraft.font, index, x + width - minecraft.font.width(index), y, SECONDARY_TEXT, false);
    String metadata =
        recipe.recipeType().name().toLowerCase(Locale.ROOT)
            + "  "
            + recipe.source().kind().name().toLowerCase(Locale.ROOT);
    graphics.drawString(
        minecraft.font,
        minecraft.font.plainSubstrByWidth(metadata, width),
        x,
        y + 12,
        SECONDARY_TEXT,
        false);

    int gridX = x;
    int gridY = y + 28;
    for (int gridYIndex = 0; gridYIndex < 3; gridYIndex++) {
      for (int gridXIndex = 0; gridXIndex < 3; gridXIndex++) {
        int cellX = gridX + gridXIndex * 20;
        int cellY = gridY + gridYIndex * 20;
        graphics.fill(cellX, cellY, cellX + 18, cellY + 18, SLOT_BACKGROUND);
        graphics.renderOutline(cellX, cellY, 18, 18, BORDER);
      }
    }
    for (IngredientSlot ingredient : recipe.layout().ingredients()) {
      ItemStackView item = ingredient.ingredient().alternatives().getFirst();
      renderItem(
          graphics,
          resolve(item),
          gridX + ingredient.x() * 20 + 1,
          gridY + ingredient.y() * 20 + 1,
          clipTop,
          clipBottom);
    }

    int arrowX = gridX + 64;
    graphics.drawCenteredString(minecraft.font, ">", arrowX + 6, gridY + 25, SECONDARY_TEXT);
    int resultX = gridX + 78;
    graphics.fill(resultX, gridY + 20, resultX + 20, gridY + 40, SLOT_BACKGROUND);
    graphics.renderOutline(resultX, gridY + 20, 20, 20, ACCENT);
    renderItem(graphics, resolve(recipe.result()), resultX + 2, gridY + 22, clipTop, clipBottom);
    String resultCount = "x" + recipe.result().count();
    graphics.drawString(minecraft.font, resultCount, resultX + 24, gridY + 25, PRIMARY_TEXT, false);

    recipe
        .processing()
        .ifPresent(
            processing -> {
              String detail =
                  processing.timeTicks()
                      + " ticks  +"
                      + formatExperience(processing.experience())
                      + " XP";
              graphics.drawString(
                  minecraft.font,
                  minecraft.font.plainSubstrByWidth(detail, width),
                  x,
                  gridY + 66,
                  SECONDARY_TEXT,
                  false);
            });
  }

  private void renderItem(
      GuiGraphics graphics, ResolvedItemStack resolved, int x, int y, int clipTop, int clipBottom) {
    if (y + 16 < clipTop || y >= clipBottom) {
      return;
    }
    if (resolved.available()) {
      graphics.renderItem(resolved.minecraftStack(), x, y);
      if (resolved.source().count() > 1) {
        graphics.renderItemDecorations(
            minecraft.font,
            resolved.minecraftStack(),
            x,
            y,
            compactCount(resolved.source().count()));
      }
    } else {
      graphics.fill(x, y, x + 16, y + 16, SLOT_BACKGROUND);
      graphics.renderOutline(x, y, 16, 16, MISSING);
      graphics.drawCenteredString(minecraft.font, "?", x + 8, y + 4, MISSING);
    }

    int mouseX = scaledMouseX();
    int mouseY = scaledMouseY();
    if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
      if (resolved.available()) {
        graphics.setTooltipForNextFrame(minecraft.font, resolved.minecraftStack(), mouseX, mouseY);
      } else {
        graphics.setTooltipForNextFrame(
            minecraft.font,
            Component.literal("Missing item: " + resolved.source().itemId()),
            mouseX,
            mouseY);
      }
    }
  }

  private ResolvedItemStack resolve(ItemStackView item) {
    return itemCache.computeIfAbsent(item, itemResolver::resolve);
  }

  private List<FormattedCharSequence> textLines(TextView text, int width) {
    if (cachedTextWidth != width || cachedTextLines.isEmpty()) {
      cachedTextLines = List.copyOf(minecraft.font.split(Component.literal(text.text()), width));
      cachedTextWidth = width;
    }
    return cachedTextLines;
  }

  private static String displayName(ResolvedItemStack resolved) {
    return resolved.available()
        ? resolved.minecraftStack().getHoverName().getString()
        : "Missing item";
  }

  private static String compactCount(int count) {
    if (count < 1000) {
      return Integer.toString(count);
    }
    if (count < 1_000_000) {
      return (count / 1000) + "k";
    }
    return "999k";
  }

  private static String formatExperience(double experience) {
    if (experience == Math.rint(experience)) {
      return Long.toString((long) experience);
    }
    return String.format(Locale.ROOT, "%.2f", experience)
        .replaceAll("0+$", "")
        .replaceAll("\\.$", "");
  }

  private int scaledMouseX() {
    return (int) minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
  }

  private int scaledMouseY() {
    return (int) minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());
  }

  private static OverlayBounds closeButton(OverlayBounds bounds) {
    return new OverlayBounds(
        bounds.right() - BUTTON_SIZE - 3, bounds.y() + 3, BUTTON_SIZE, BUTTON_SIZE);
  }

  private static OverlayBounds pinButton(OverlayBounds bounds) {
    return new OverlayBounds(
        bounds.right() - BUTTON_SIZE * 2 - 5, bounds.y() + 3, BUTTON_SIZE, BUTTON_SIZE);
  }

  private static OverlayBounds resizeButton(OverlayBounds bounds) {
    return new OverlayBounds(
        bounds.right() - RESIZE_HANDLE,
        bounds.bottom() - RESIZE_HANDLE,
        RESIZE_HANDLE,
        RESIZE_HANDLE);
  }

  private enum Interaction {
    NONE,
    DRAG,
    RESIZE
  }
}
