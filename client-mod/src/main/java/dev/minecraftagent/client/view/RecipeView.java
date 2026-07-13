package dev.minecraftagent.client.view;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RecipeView(
    String schemaVersion, Query query, int selectedRecipe, List<Recipe> recipes)
    implements ViewContent {

  public RecipeView {
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    Objects.requireNonNull(query, "query");
    recipes = List.copyOf(Objects.requireNonNull(recipes, "recipes"));
  }

  public record Query(QueryMode mode, String itemId) {
    public Query {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(itemId, "itemId");
    }
  }

  public enum QueryMode {
    LOOKUP,
    USES
  }

  public record Recipe(
      String recipeId,
      RecipeType recipeType,
      Source source,
      ItemStackView result,
      Layout layout,
      List<RemainingItem> remainingItems,
      Optional<Processing> processing) {

    public Recipe {
      Objects.requireNonNull(recipeId, "recipeId");
      Objects.requireNonNull(recipeType, "recipeType");
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(result, "result");
      Objects.requireNonNull(layout, "layout");
      remainingItems = List.copyOf(Objects.requireNonNull(remainingItems, "remainingItems"));
      processing = Objects.requireNonNull(processing, "processing");
    }
  }

  public enum RecipeType {
    SHAPED,
    SHAPELESS,
    SMELTING,
    BLASTING,
    SMOKING,
    CAMPFIRE_COOKING,
    STONECUTTING,
    SMITHING_TRANSFORM,
    SMITHING_TRIM,
    CUSTOM
  }

  public record Source(SourceKind kind, Optional<String> providerId) {
    public Source {
      Objects.requireNonNull(kind, "kind");
      providerId = Objects.requireNonNull(providerId, "providerId");
    }
  }

  public enum SourceKind {
    SERVER_REGISTRY,
    PLUGIN_PROVIDER,
    SERVER_DOCS,
    WEB_DOCUMENTATION,
    MODEL_KNOWLEDGE
  }

  public record Layout(int width, int height, List<IngredientSlot> ingredients) {
    public Layout {
      ingredients = List.copyOf(Objects.requireNonNull(ingredients, "ingredients"));
    }
  }

  public record IngredientSlot(int slot, int x, int y, IngredientChoice ingredient) {
    public IngredientSlot {
      Objects.requireNonNull(ingredient, "ingredient");
    }
  }

  public record IngredientChoice(
      ChoiceType choiceType, Optional<String> tagId, List<ItemStackView> alternatives) {
    public IngredientChoice {
      Objects.requireNonNull(choiceType, "choiceType");
      tagId = Objects.requireNonNull(tagId, "tagId");
      alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives"));
    }
  }

  public enum ChoiceType {
    MATERIAL,
    EXACT,
    TAG
  }

  public record RemainingItem(int slot, ItemStackView item) {
    public RemainingItem {
      Objects.requireNonNull(item, "item");
    }
  }

  public record Processing(int timeTicks, double experience) {}
}
