package dev.minecraftagent.paper.client;

import java.util.Arrays;

/** Closed client feature names from the version 1.0 handshake. */
public enum ClientFeature {
  OVERLAY("overlay"),
  ITEM_ICONS("itemIcons"),
  RECIPE_VIEW("recipeView"),
  LITEMATICA_PREVIEW("litematicaPreview"),
  LITEMATICA_MATERIAL_LIST("litematicaMaterialList");

  private final String wireName;

  ClientFeature(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  public static ClientFeature fromWireName(String wireName) {
    return Arrays.stream(values())
        .filter(feature -> feature.wireName.equals(wireName))
        .findFirst()
        .orElseThrow(() -> new ClientProtocolException("CLIENT_FEATURE_UNKNOWN"));
  }
}
