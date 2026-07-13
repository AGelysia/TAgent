package dev.minecraftagent.client.view;

public final class ViewDecodeException extends Exception {
  private final Code code;

  public ViewDecodeException(Code code) {
    super(code.name());
    this.code = code;
  }

  public ViewDecodeException(Code code, Throwable cause) {
    super(code.name(), cause);
    this.code = code;
  }

  public Code code() {
    return code;
  }

  public enum Code {
    PAYLOAD_TOO_LARGE,
    INVALID_UTF8,
    INVALID_JSON,
    JSON_LIMIT_EXCEEDED,
    DUPLICATE_FIELD,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    INVALID_VALUE,
    METADATA_MISMATCH,
    UNSUPPORTED_VIEW_TYPE
  }
}
