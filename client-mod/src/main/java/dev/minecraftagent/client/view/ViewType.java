package dev.minecraftagent.client.view;

public enum ViewType {
  TEXT("text"),
  ITEM_STACK("item_stack"),
  ITEM_LIST("item_list"),
  RECIPE("recipe");

  private final String wireName;

  ViewType(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  public static ViewType fromWireName(String wireName) {
    for (ViewType value : values()) {
      if (value.wireName.equals(wireName)) {
        return value;
      }
    }
    throw new IllegalArgumentException("Unsupported view type");
  }
}
