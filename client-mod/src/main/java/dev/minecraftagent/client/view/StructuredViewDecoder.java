package dev.minecraftagent.client.view;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.minecraftagent.client.view.ItemStackView.SafeComponents;
import dev.minecraftagent.client.view.RecipeView.ChoiceType;
import dev.minecraftagent.client.view.RecipeView.IngredientChoice;
import dev.minecraftagent.client.view.RecipeView.IngredientSlot;
import dev.minecraftagent.client.view.RecipeView.Layout;
import dev.minecraftagent.client.view.RecipeView.Processing;
import dev.minecraftagent.client.view.RecipeView.Query;
import dev.minecraftagent.client.view.RecipeView.QueryMode;
import dev.minecraftagent.client.view.RecipeView.Recipe;
import dev.minecraftagent.client.view.RecipeView.RecipeType;
import dev.minecraftagent.client.view.RecipeView.RemainingItem;
import dev.minecraftagent.client.view.RecipeView.Source;
import dev.minecraftagent.client.view.RecipeView.SourceKind;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Decodes the fixed view protocol without materializing an unbounded Gson object graph. */
public final class StructuredViewDecoder {
  public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
  public static final int MAX_JSON_DEPTH = 24;
  public static final int MAX_JSON_NODES = 8192;
  public static final int MAX_TOTAL_STRING_CHARS = 65536;
  public static final int MAX_ITEM_STACKS = 2048;

  private static final int MAX_OBJECT_FIELDS = 32;
  private static final int MAX_ARRAY_ITEMS = 2048;
  private static final int MAX_NUMBER_CHARS = 64;
  private static final Pattern NAMESPACED_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
  private static final Pattern INTEGER = Pattern.compile("-?(?:0|[1-9][0-9]*)");

  public StructuredView decode(byte[] payload) throws ViewDecodeException {
    if (payload == null || payload.length == 0) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_JSON);
    }
    if (payload.length > MAX_PAYLOAD_BYTES) {
      throw new ViewDecodeException(ViewDecodeException.Code.PAYLOAD_TOO_LARGE);
    }

    String json = decodeUtf8(payload);
    JsonNode root = parse(json);
    DecodeBudget budget = new DecodeBudget();
    return decodeView(root, budget);
  }

  /** Decodes and binds a body to the authenticated transfer descriptor. */
  public StructuredView decode(
      byte[] payload, UUID expectedViewId, UUID expectedRequestId, int expectedRevision)
      throws ViewDecodeException {
    Objects.requireNonNull(expectedViewId, "expectedViewId");
    Objects.requireNonNull(expectedRequestId, "expectedRequestId");
    StructuredView view = decode(payload);
    if (!view.viewId().equals(expectedViewId)
        || !view.requestId().equals(expectedRequestId)
        || view.revision() != expectedRevision) {
      throw new ViewDecodeException(ViewDecodeException.Code.METADATA_MISMATCH);
    }
    return view;
  }

  private static String decodeUtf8(byte[] payload) throws ViewDecodeException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(payload))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_UTF8, exception);
    }
  }

  private static JsonNode parse(String json) throws ViewDecodeException {
    ParseBudget budget = new ParseBudget();
    try (JsonReader reader = new JsonReader(new StringReader(json))) {
      reader.setStrictness(Strictness.STRICT);
      JsonNode node = readNode(reader, budget, 1);
      if (reader.peek() != JsonToken.END_DOCUMENT) {
        throw new ViewDecodeException(ViewDecodeException.Code.INVALID_JSON);
      }
      return node;
    } catch (ViewDecodeException exception) {
      throw exception;
    } catch (IOException | IllegalStateException | NumberFormatException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_JSON, exception);
    }
  }

  private static JsonNode readNode(JsonReader reader, ParseBudget budget, int depth)
      throws IOException, ViewDecodeException {
    budget.addNode(depth);
    return switch (reader.peek()) {
      case BEGIN_OBJECT -> readObject(reader, budget, depth);
      case BEGIN_ARRAY -> readArray(reader, budget, depth);
      case STRING -> {
        String value = reader.nextString();
        budget.addString(value);
        yield new JsonString(value);
      }
      case NUMBER -> {
        String value = reader.nextString();
        if (value.length() > MAX_NUMBER_CHARS) {
          throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
        }
        yield new JsonNumber(value);
      }
      case BOOLEAN -> new JsonBoolean(reader.nextBoolean());
      case NULL -> {
        reader.nextNull();
        yield JsonNull.INSTANCE;
      }
      default -> throw new ViewDecodeException(ViewDecodeException.Code.INVALID_JSON);
    };
  }

  private static JsonObject readObject(JsonReader reader, ParseBudget budget, int depth)
      throws IOException, ViewDecodeException {
    reader.beginObject();
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    while (reader.hasNext()) {
      if (fields.size() >= MAX_OBJECT_FIELDS) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
      String name = reader.nextName();
      budget.addString(name);
      if (fields.containsKey(name)) {
        throw new ViewDecodeException(ViewDecodeException.Code.DUPLICATE_FIELD);
      }
      fields.put(name, readNode(reader, budget, depth + 1));
    }
    reader.endObject();
    return new JsonObject(Map.copyOf(fields));
  }

  private static JsonArray readArray(JsonReader reader, ParseBudget budget, int depth)
      throws IOException, ViewDecodeException {
    reader.beginArray();
    List<JsonNode> values = new ArrayList<>();
    while (reader.hasNext()) {
      if (values.size() >= MAX_ARRAY_ITEMS) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
      values.add(readNode(reader, budget, depth + 1));
    }
    reader.endArray();
    return new JsonArray(List.copyOf(values));
  }

  private static StructuredView decodeView(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        closedObject(
            node,
            Set.of(
                "viewSchemaVersion",
                "viewId",
                "requestId",
                "viewType",
                "revision",
                "title",
                "fallbackText",
                "pinnable",
                "content"),
            Set.of(
                "viewSchemaVersion",
                "viewId",
                "requestId",
                "viewType",
                "revision",
                "title",
                "fallbackText",
                "pinnable",
                "content"));
    String schemaVersion = string(object, "viewSchemaVersion", 3, 3, false);
    if (!"1.0".equals(schemaVersion)) {
      invalidValue();
    }
    UUID viewId = uuid(string(object, "viewId", 36, 36, false));
    UUID requestId = uuid(string(object, "requestId", 36, 36, false));
    String wireType = string(object, "viewType", 1, 32, false);
    ViewType viewType;
    try {
      viewType = ViewType.fromWireName(wireType);
    } catch (IllegalArgumentException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.UNSUPPORTED_VIEW_TYPE, exception);
    }
    int revision = integer(object.fields().get("revision"), 1, Integer.MAX_VALUE);
    String title = string(object, "title", 1, 128, false);
    String fallback = string(object, "fallbackText", 1, 8192, true);
    boolean pinnable = bool(object.fields().get("pinnable"));
    ViewContent content =
        switch (viewType) {
          case TEXT -> decodeText(object.fields().get("content"));
          case ITEM_STACK -> decodeItemStack(object.fields().get("content"), budget);
          case ITEM_LIST -> decodeItemList(object.fields().get("content"), budget);
          case RECIPE -> decodeRecipeView(object.fields().get("content"), budget);
        };
    return new StructuredView(
        schemaVersion, viewId, requestId, viewType, revision, title, fallback, pinnable, content);
  }

  private static TextView decodeText(JsonNode node) throws ViewDecodeException {
    JsonObject object = closedObject(node, Set.of("text"), Set.of("text"));
    return new TextView(string(object, "text", 1, 32768, true));
  }

  private static ItemListView decodeItemList(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object = closedObject(node, Set.of("items"), Set.of("items"));
    List<JsonNode> values = array(object.fields().get("items"), 1, 128);
    List<ItemStackView> items = new ArrayList<>(values.size());
    for (JsonNode value : values) {
      items.add(decodeItemStack(value, budget));
    }
    return new ItemListView(items);
  }

  private static ItemStackView decodeItemStack(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    budget.addItemStack();
    JsonObject object =
        closedObject(
            node, Set.of("itemId", "count", "components"), Set.of("itemId", "count", "components"));
    String itemId = namespacedId(string(object, "itemId", 3, 256, false));
    int count = integer(object.fields().get("count"), 1, 999999);
    return new ItemStackView(itemId, count, decodeComponents(object.fields().get("components")));
  }

  private static SafeComponents decodeComponents(JsonNode node) throws ViewDecodeException {
    JsonObject object =
        closedObject(
            node,
            Set.of(
                "customName", "lore", "damage", "maxDamage", "customModelData", "enchantmentGlint"),
            Set.of());
    Optional<String> customName = optionalString(object, "customName", 0, 512, false);
    List<String> lore = new ArrayList<>();
    if (object.fields().containsKey("lore")) {
      for (JsonNode line : array(object.fields().get("lore"), 0, 32)) {
        lore.add(visibleString(line, 0, 512, false));
      }
    }
    Optional<Integer> damage = optionalInteger(object, "damage", 0, Integer.MAX_VALUE);
    Optional<Integer> maxDamage = optionalInteger(object, "maxDamage", 1, Integer.MAX_VALUE);
    if (damage.isPresent() && maxDamage.isPresent() && damage.get() > maxDamage.get()) {
      invalidValue();
    }
    Optional<Integer> customModelData =
        optionalInteger(object, "customModelData", 0, Integer.MAX_VALUE);
    Optional<Boolean> enchantmentGlint = optionalBoolean(object, "enchantmentGlint");
    return new SafeComponents(
        customName, lore, damage, maxDamage, customModelData, enchantmentGlint);
  }

  private static RecipeView decodeRecipeView(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        closedObject(
            node,
            Set.of("schemaVersion", "query", "selectedRecipe", "recipes"),
            Set.of("schemaVersion", "query", "selectedRecipe", "recipes"));
    String schemaVersion = string(object, "schemaVersion", 3, 3, false);
    if (!"1.0".equals(schemaVersion)) {
      invalidValue();
    }
    Query query = decodeQuery(object.fields().get("query"));
    int selectedRecipe = integer(object.fields().get("selectedRecipe"), 0, 127);
    List<JsonNode> values = array(object.fields().get("recipes"), 1, 128);
    if (selectedRecipe >= values.size()) {
      invalidValue();
    }
    List<Recipe> recipes = new ArrayList<>(values.size());
    for (JsonNode value : values) {
      recipes.add(decodeRecipe(value, budget));
    }
    return new RecipeView(schemaVersion, query, selectedRecipe, recipes);
  }

  private static Query decodeQuery(JsonNode node) throws ViewDecodeException {
    JsonObject object = closedObject(node, Set.of("mode", "itemId"), Set.of("mode", "itemId"));
    QueryMode mode = enumValue(string(object, "mode", 1, 16, false), QueryMode.class);
    return new Query(mode, namespacedId(string(object, "itemId", 3, 256, false)));
  }

  private static Recipe decodeRecipe(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        closedObject(
            node,
            Set.of(
                "recipeId",
                "recipeType",
                "source",
                "result",
                "layout",
                "remainingItems",
                "processing"),
            Set.of("recipeId", "recipeType", "source", "result", "layout", "remainingItems"));
    String recipeId = namespacedId(string(object, "recipeId", 3, 256, false));
    RecipeType recipeType = enumValue(string(object, "recipeType", 1, 32, false), RecipeType.class);
    Source source = decodeSource(object.fields().get("source"));
    ItemStackView result = decodeItemStack(object.fields().get("result"), budget);
    Layout layout = decodeLayout(object.fields().get("layout"), budget);
    List<RemainingItem> remainingItems =
        decodeRemainingItems(object.fields().get("remainingItems"), budget);
    Optional<Processing> processing = Optional.empty();
    if (object.fields().containsKey("processing")) {
      processing = Optional.of(decodeProcessing(object.fields().get("processing")));
    }
    if (isCooking(recipeType) && processing.isEmpty()) {
      throw new ViewDecodeException(ViewDecodeException.Code.MISSING_FIELD);
    }
    return new Recipe(recipeId, recipeType, source, result, layout, remainingItems, processing);
  }

  private static Source decodeSource(JsonNode node) throws ViewDecodeException {
    JsonObject object =
        closedObject(node, Set.of("kind", "providerId"), Set.of("kind", "providerId"));
    SourceKind kind = enumValue(string(object, "kind", 1, 32, false), SourceKind.class);
    JsonNode provider = object.fields().get("providerId");
    Optional<String> providerId;
    if (provider == JsonNull.INSTANCE) {
      providerId = Optional.empty();
    } else {
      providerId = Optional.of(namespacedId(visibleString(provider, 3, 256, false)));
    }
    return new Source(kind, providerId);
  }

  private static Layout decodeLayout(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        closedObject(
            node,
            Set.of("width", "height", "ingredients"),
            Set.of("width", "height", "ingredients"));
    int width = integer(object.fields().get("width"), 1, 3);
    int height = integer(object.fields().get("height"), 1, 3);
    List<JsonNode> values = array(object.fields().get("ingredients"), 1, 9);
    List<IngredientSlot> ingredients = new ArrayList<>(values.size());
    Set<Integer> slots = new HashSet<>();
    Set<Integer> positions = new HashSet<>();
    for (JsonNode value : values) {
      IngredientSlot ingredient = decodeIngredientSlot(value, budget);
      if (ingredient.x() >= width
          || ingredient.y() >= height
          || ingredient.slot() != ingredient.y() * width + ingredient.x()) {
        invalidValue();
      }
      if (!slots.add(ingredient.slot()) || !positions.add(ingredient.y() * 3 + ingredient.x())) {
        invalidValue();
      }
      ingredients.add(ingredient);
    }
    return new Layout(width, height, ingredients);
  }

  private static IngredientSlot decodeIngredientSlot(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        closedObject(
            node, Set.of("slot", "x", "y", "ingredient"), Set.of("slot", "x", "y", "ingredient"));
    return new IngredientSlot(
        integer(object.fields().get("slot"), 0, 8),
        integer(object.fields().get("x"), 0, 2),
        integer(object.fields().get("y"), 0, 2),
        decodeIngredientChoice(object.fields().get("ingredient"), budget));
  }

  private static IngredientChoice decodeIngredientChoice(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    JsonObject object =
        closedObject(
            node,
            Set.of("choiceType", "tagId", "alternatives"),
            Set.of("choiceType", "alternatives"));
    ChoiceType choiceType = enumValue(string(object, "choiceType", 1, 16, false), ChoiceType.class);
    Optional<String> tagId = Optional.empty();
    if (object.fields().containsKey("tagId")) {
      tagId = Optional.of(namespacedId(string(object, "tagId", 3, 256, false)));
    }
    if ((choiceType == ChoiceType.TAG) != tagId.isPresent()) {
      invalidValue();
    }
    List<JsonNode> values = array(object.fields().get("alternatives"), 1, 64);
    List<ItemStackView> alternatives = new ArrayList<>(values.size());
    for (JsonNode value : values) {
      alternatives.add(decodeItemStack(value, budget));
    }
    return new IngredientChoice(choiceType, tagId, alternatives);
  }

  private static List<RemainingItem> decodeRemainingItems(JsonNode node, DecodeBudget budget)
      throws ViewDecodeException {
    List<JsonNode> values = array(node, 0, 9);
    List<RemainingItem> result = new ArrayList<>(values.size());
    Set<Integer> slots = new HashSet<>();
    for (JsonNode value : values) {
      JsonObject object = closedObject(value, Set.of("slot", "item"), Set.of("slot", "item"));
      int slot = integer(object.fields().get("slot"), 0, 8);
      if (!slots.add(slot)) {
        invalidValue();
      }
      result.add(new RemainingItem(slot, decodeItemStack(object.fields().get("item"), budget)));
    }
    return List.copyOf(result);
  }

  private static Processing decodeProcessing(JsonNode node) throws ViewDecodeException {
    JsonObject object =
        closedObject(node, Set.of("timeTicks", "experience"), Set.of("timeTicks", "experience"));
    return new Processing(
        integer(object.fields().get("timeTicks"), 1, 120000),
        decimal(object.fields().get("experience"), BigDecimal.ZERO, new BigDecimal("1000000")));
  }

  private static boolean isCooking(RecipeType type) {
    return type == RecipeType.SMELTING
        || type == RecipeType.BLASTING
        || type == RecipeType.SMOKING
        || type == RecipeType.CAMPFIRE_COOKING;
  }

  private static JsonObject closedObject(JsonNode node, Set<String> allowed, Set<String> required)
      throws ViewDecodeException {
    if (!(node instanceof JsonObject object)) {
      invalidValue();
      throw new AssertionError();
    }
    for (String field : object.fields().keySet()) {
      if (!allowed.contains(field)) {
        throw new ViewDecodeException(ViewDecodeException.Code.UNKNOWN_FIELD);
      }
    }
    for (String field : required) {
      if (!object.fields().containsKey(field)) {
        throw new ViewDecodeException(ViewDecodeException.Code.MISSING_FIELD);
      }
    }
    return object;
  }

  private static String string(
      JsonObject object, String field, int minimum, int maximum, boolean allowLineBreaks)
      throws ViewDecodeException {
    return visibleString(object.fields().get(field), minimum, maximum, allowLineBreaks);
  }

  private static Optional<String> optionalString(
      JsonObject object, String field, int minimum, int maximum, boolean allowLineBreaks)
      throws ViewDecodeException {
    if (!object.fields().containsKey(field)) {
      return Optional.empty();
    }
    return Optional.of(string(object, field, minimum, maximum, allowLineBreaks));
  }

  private static String visibleString(
      JsonNode node, int minimum, int maximum, boolean allowLineBreaks) throws ViewDecodeException {
    if (!(node instanceof JsonString jsonString)) {
      invalidValue();
      throw new AssertionError();
    }
    String value = jsonString.value();
    int length = value.codePointCount(0, value.length());
    if (length < minimum || length > maximum) {
      invalidValue();
    }
    for (int index = 0; index < value.length(); ) {
      int codePoint = value.codePointAt(index);
      if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
        invalidValue();
      }
      if (((codePoint == '\n' || codePoint == '\t') && allowLineBreaks)
          || codePoint >= 0x20 && !Character.isISOControl(codePoint)) {
        if (isBidirectionalControl(codePoint)) {
          invalidValue();
        }
      } else {
        invalidValue();
      }
      index += Character.charCount(codePoint);
    }
    return value;
  }

  private static boolean isBidirectionalControl(int codePoint) {
    return codePoint == 0x061c
        || codePoint == 0x200e
        || codePoint == 0x200f
        || codePoint >= 0x202a && codePoint <= 0x202e
        || codePoint >= 0x2066 && codePoint <= 0x2069;
  }

  private static UUID uuid(String value) throws ViewDecodeException {
    try {
      UUID uuid = UUID.fromString(value);
      if (!uuid.toString().equals(value)) {
        invalidValue();
      }
      return uuid;
    } catch (IllegalArgumentException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE, exception);
    }
  }

  private static String namespacedId(String value) throws ViewDecodeException {
    if (!NAMESPACED_ID.matcher(value).matches()) {
      invalidValue();
    }
    return value;
  }

  private static List<JsonNode> array(JsonNode node, int minimum, int maximum)
      throws ViewDecodeException {
    if (!(node instanceof JsonArray array)) {
      invalidValue();
      throw new AssertionError();
    }
    if (array.values().size() < minimum || array.values().size() > maximum) {
      invalidValue();
    }
    return array.values();
  }

  private static boolean bool(JsonNode node) throws ViewDecodeException {
    if (!(node instanceof JsonBoolean value)) {
      invalidValue();
      throw new AssertionError();
    }
    return value.value();
  }

  private static Optional<Boolean> optionalBoolean(JsonObject object, String field)
      throws ViewDecodeException {
    if (!object.fields().containsKey(field)) {
      return Optional.empty();
    }
    return Optional.of(bool(object.fields().get(field)));
  }

  private static int integer(JsonNode node, int minimum, int maximum) throws ViewDecodeException {
    if (!(node instanceof JsonNumber value) || !INTEGER.matcher(value.value()).matches()) {
      invalidValue();
      throw new AssertionError();
    }
    try {
      long number = Long.parseLong(value.value());
      if (number < minimum || number > maximum) {
        invalidValue();
      }
      return (int) number;
    } catch (NumberFormatException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE, exception);
    }
  }

  private static Optional<Integer> optionalInteger(
      JsonObject object, String field, int minimum, int maximum) throws ViewDecodeException {
    if (!object.fields().containsKey(field)) {
      return Optional.empty();
    }
    return Optional.of(integer(object.fields().get(field), minimum, maximum));
  }

  private static double decimal(JsonNode node, BigDecimal minimum, BigDecimal maximum)
      throws ViewDecodeException {
    if (!(node instanceof JsonNumber value)) {
      invalidValue();
      throw new AssertionError();
    }
    try {
      BigDecimal number = new BigDecimal(value.value());
      if (number.compareTo(minimum) < 0 || number.compareTo(maximum) > 0) {
        invalidValue();
      }
      double result = number.doubleValue();
      if (!Double.isFinite(result)) {
        invalidValue();
      }
      return result;
    } catch (NumberFormatException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE, exception);
    }
  }

  private static <E extends Enum<E>> E enumValue(String wireValue, Class<E> type)
      throws ViewDecodeException {
    try {
      return Enum.valueOf(type, wireValue.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE, exception);
    }
  }

  private static void invalidValue() throws ViewDecodeException {
    throw new ViewDecodeException(ViewDecodeException.Code.INVALID_VALUE);
  }

  private sealed interface JsonNode
      permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {}

  private record JsonObject(Map<String, JsonNode> fields) implements JsonNode {}

  private record JsonArray(List<JsonNode> values) implements JsonNode {}

  private record JsonString(String value) implements JsonNode {}

  private record JsonNumber(String value) implements JsonNode {}

  private record JsonBoolean(boolean value) implements JsonNode {}

  private enum JsonNull implements JsonNode {
    INSTANCE
  }

  private static final class ParseBudget {
    private int nodes;
    private int stringChars;

    private void addNode(int depth) throws ViewDecodeException {
      if (depth > MAX_JSON_DEPTH || ++nodes > MAX_JSON_NODES) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
    }

    private void addString(String value) throws ViewDecodeException {
      if ((long) stringChars + value.length() > MAX_TOTAL_STRING_CHARS) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
      stringChars += value.length();
    }
  }

  private static final class DecodeBudget {
    private int itemStacks;

    private void addItemStack() throws ViewDecodeException {
      if (++itemStacks > MAX_ITEM_STACKS) {
        throw new ViewDecodeException(ViewDecodeException.Code.JSON_LIMIT_EXCEEDED);
      }
    }
  }
}
