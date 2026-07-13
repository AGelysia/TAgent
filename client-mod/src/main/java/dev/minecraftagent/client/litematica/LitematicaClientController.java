package dev.minecraftagent.client.litematica;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Presentation-only facade for the three Litematica UI controls. It derives local paths from view
 * IDs and never accepts a server-provided path or returns an authorization signal.
 */
public final class LitematicaClientController implements AutoCloseable {
  public static final long MAX_SCHEMATIC_BYTES = 16L * 1024L * 1024L;

  private final Optional<LitematicaAdapter> adapter;
  private final Path managedRoot;
  private final long maxSchematicBytes;

  public LitematicaClientController(LitematicaCompatibility compatibility, Path managedRoot)
      throws IOException {
    this(Objects.requireNonNull(compatibility).adapter(), managedRoot, MAX_SCHEMATIC_BYTES);
  }

  public LitematicaClientController(Optional<LitematicaAdapter> adapter, Path managedRoot)
      throws IOException {
    this(adapter, managedRoot, MAX_SCHEMATIC_BYTES);
  }

  LitematicaClientController(
      Optional<LitematicaAdapter> adapter, Path managedRoot, long maxSchematicBytes)
      throws IOException {
    this.adapter = Objects.requireNonNull(adapter);
    Path configuredRoot = Objects.requireNonNull(managedRoot).toAbsolutePath().normalize();
    this.managedRoot = adapter.isPresent() ? configuredRoot.toRealPath() : configuredRoot;
    this.maxSchematicBytes = maxSchematicBytes;
    if ((adapter.isPresent() && !Files.isDirectory(this.managedRoot, LinkOption.NOFOLLOW_LINKS))
        || maxSchematicBytes < 1) {
      throw new IOException("managed Litematica root is unavailable");
    }
  }

  /**
   * Performs all bounded file IO before the prepared action reaches the Minecraft client thread.
   */
  public PreparedLoad prepareLoad(UUID viewId, String displayName) {
    Objects.requireNonNull(viewId);
    Objects.requireNonNull(displayName);
    if (adapter.isEmpty()) {
      return PreparedLoad.failed(viewId, LitematicaDisplayReport.Failure.ADAPTER_UNAVAILABLE);
    }

    Path file = managedRoot.resolve(viewId + ".litematica");
    try {
      VerifiedManagedFile verified = verifyManagedFile(file);
      return PreparedLoad.ready(
          new LitematicaPreviewRequest(
              viewId,
              verified.path(),
              verified.byteLength(),
              verified.lastModifiedTime(),
              verified.fileKey(),
              verified.contentSha256(),
              displayName,
              0,
              0,
              0));
    } catch (IOException | RuntimeException exception) {
      return PreparedLoad.failed(viewId, LitematicaDisplayReport.Failure.MANAGED_FILE_UNAVAILABLE);
    }
  }

  /** Commits one prepared load. The adapter remains responsible for enforcing its client thread. */
  public LitematicaDisplayReport load(
      PreparedLoad prepared, int originX, int originY, int originZ) {
    Objects.requireNonNull(prepared);
    if (prepared.failure != null) {
      return LitematicaDisplayReport.failed(prepared.viewId, null, prepared.failure);
    }
    if (adapter.isEmpty()) {
      return unavailable(prepared.viewId);
    }
    try {
      return adapter
          .orElseThrow()
          .loadPreview(prepared.request.atOrigin(originX, originY, originZ));
    } catch (RuntimeException | LinkageError exception) {
      return adapterFailure(prepared.viewId, prepared.request.contentSha256());
    }
  }

  public LitematicaDisplayReport remove(UUID viewId) {
    Objects.requireNonNull(viewId);
    if (adapter.isEmpty()) {
      return unavailable(viewId);
    }
    try {
      return adapter.orElseThrow().removePreview(viewId);
    } catch (RuntimeException | LinkageError exception) {
      return adapterFailure(viewId, null);
    }
  }

  public LitematicaDisplayReport openMaterialList(UUID viewId) {
    Objects.requireNonNull(viewId);
    if (adapter.isEmpty()) {
      return unavailable(viewId);
    }
    try {
      return adapter.orElseThrow().openMaterialList(viewId);
    } catch (RuntimeException | LinkageError exception) {
      return adapterFailure(viewId, null);
    }
  }

  public boolean available() {
    return adapter.isPresent();
  }

  @Override
  public void close() {
    adapter.ifPresent(LitematicaAdapter::close);
  }

  private VerifiedManagedFile verifyManagedFile(Path expectedFile) throws IOException {
    BasicFileAttributes before = attributes(expectedFile);
    if (!before.isRegularFile() || before.size() < 1 || before.size() > maxSchematicBytes) {
      throw new IOException("managed schematic is not a regular file");
    }
    Path real = expectedFile.toRealPath();
    if (!real.startsWith(managedRoot)) {
      throw new IOException("managed schematic escaped its root");
    }

    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
    long total = 0;
    try (var input = Files.newInputStream(real)) {
      var buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (Thread.currentThread().isInterrupted()) {
          throw new IOException("managed schematic verification was interrupted");
        }
        total += count;
        if (total > maxSchematicBytes) {
          throw new IOException("managed schematic exceeds its byte limit");
        }
        digest.update(buffer, 0, count);
      }
    }
    if (total < 1) {
      throw new IOException("managed schematic is empty");
    }
    BasicFileAttributes after = attributes(expectedFile);
    if (!after.isRegularFile()
        || total != after.size()
        || !sameIdentity(before, after)
        || !real.equals(expectedFile.toRealPath())) {
      throw new IOException("managed schematic changed while it was verified");
    }
    return new VerifiedManagedFile(
        real,
        total,
        after.lastModifiedTime(),
        fileKey(after),
        java.util.HexFormat.of().formatHex(digest.digest()));
  }

  private static BasicFileAttributes attributes(Path path) throws IOException {
    return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  private static boolean sameIdentity(BasicFileAttributes first, BasicFileAttributes second) {
    return first.size() == second.size()
        && first.lastModifiedTime().equals(second.lastModifiedTime())
        && fileKey(first).equals(fileKey(second));
  }

  private static Optional<String> fileKey(BasicFileAttributes attributes) {
    return Optional.ofNullable(attributes.fileKey()).map(Object::toString);
  }

  private static LitematicaDisplayReport unavailable(UUID viewId) {
    return LitematicaDisplayReport.failed(
        viewId, null, LitematicaDisplayReport.Failure.ADAPTER_UNAVAILABLE);
  }

  private static LitematicaDisplayReport adapterFailure(UUID viewId, String hash) {
    return LitematicaDisplayReport.failed(
        viewId, hash, LitematicaDisplayReport.Failure.ADAPTER_CALL_FAILED);
  }

  public static final class PreparedLoad {
    private final UUID viewId;
    private final LitematicaPreviewRequest request;
    private final LitematicaDisplayReport.Failure failure;

    private PreparedLoad(
        UUID viewId, LitematicaPreviewRequest request, LitematicaDisplayReport.Failure failure) {
      this.viewId = Objects.requireNonNull(viewId);
      this.request = request;
      this.failure = failure;
    }

    private static PreparedLoad ready(LitematicaPreviewRequest request) {
      return new PreparedLoad(request.previewId(), Objects.requireNonNull(request), null);
    }

    private static PreparedLoad failed(UUID viewId, LitematicaDisplayReport.Failure failure) {
      return new PreparedLoad(viewId, null, Objects.requireNonNull(failure));
    }
  }

  private record VerifiedManagedFile(
      Path path,
      long byteLength,
      java.nio.file.attribute.FileTime lastModifiedTime,
      Optional<String> fileKey,
      String contentSha256) {}
}
