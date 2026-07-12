package dev.minecraftagent.paper.transport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.HashSet;

final class StrictJson {
  private static final int MAX_DEPTH = 16;
  private static final int MAX_VALUES = 512;

  private StrictJson() {}

  static JsonObject parseObject(String text) {
    try {
      var reader = new JsonReader(new StringReader(text));
      reader.setStrictness(Strictness.STRICT);
      var budget = new Budget();
      var element = read(reader, 0, budget);
      if (reader.peek() != JsonToken.END_DOCUMENT || !element.isJsonObject()) {
        throw invalid();
      }
      return element.getAsJsonObject();
    } catch (IOException | IllegalStateException | NumberFormatException error) {
      throw invalid();
    }
  }

  private static JsonElement read(JsonReader reader, int depth, Budget budget) throws IOException {
    if (depth > MAX_DEPTH || ++budget.values > MAX_VALUES) {
      throw invalid();
    }
    return switch (reader.peek()) {
      case BEGIN_OBJECT -> readObject(reader, depth, budget);
      case BEGIN_ARRAY -> readArray(reader, depth, budget);
      case STRING -> new JsonPrimitive(reader.nextString());
      case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
      case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
      case NULL -> {
        reader.nextNull();
        yield JsonNull.INSTANCE;
      }
      default -> throw invalid();
    };
  }

  private static JsonObject readObject(JsonReader reader, int depth, Budget budget)
      throws IOException {
    reader.beginObject();
    var object = new JsonObject();
    var names = new HashSet<String>();
    while (reader.hasNext()) {
      var name = reader.nextName();
      if (!names.add(name)) {
        throw invalid();
      }
      object.add(name, read(reader, depth + 1, budget));
    }
    reader.endObject();
    return object;
  }

  private static JsonArray readArray(JsonReader reader, int depth, Budget budget)
      throws IOException {
    reader.beginArray();
    var array = new JsonArray();
    while (reader.hasNext()) {
      array.add(read(reader, depth + 1, budget));
    }
    reader.endArray();
    return array;
  }

  private static RuntimeConnectionFailure invalid() {
    return new RuntimeConnectionFailure("HANDSHAKE_MESSAGE_INVALID", "authentication");
  }

  private static final class Budget {
    private int values;
  }
}
