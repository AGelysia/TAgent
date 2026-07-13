package dev.minecraftagent.client.litematica;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A local, already verified schematic artifact; network payloads must never provide its path. */
public record LitematicaPreviewRequest(
    UUID previewId,
    Path managedFile,
    long managedFileBytes,
    FileTime lastModifiedTime,
    Optional<String> fileKey,
    String contentSha256,
    String displayName,
    int originX,
    int originY,
    int originZ) {
  public LitematicaPreviewRequest {
    Objects.requireNonNull(previewId, "previewId");
    Objects.requireNonNull(managedFile, "managedFile");
    if (managedFileBytes < 1) {
      throw new IllegalArgumentException("managedFileBytes must be positive");
    }
    Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
    Objects.requireNonNull(fileKey, "fileKey");
    Objects.requireNonNull(contentSha256, "contentSha256");
    Objects.requireNonNull(displayName, "displayName");
  }

  LitematicaPreviewRequest atOrigin(int x, int y, int z) {
    return new LitematicaPreviewRequest(
        previewId,
        managedFile,
        managedFileBytes,
        lastModifiedTime,
        fileKey,
        contentSha256,
        displayName,
        x,
        y,
        z);
  }
}
