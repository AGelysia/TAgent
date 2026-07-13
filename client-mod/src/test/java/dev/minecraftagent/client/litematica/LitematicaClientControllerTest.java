package dev.minecraftagent.client.litematica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LitematicaClientControllerTest {
  private static final UUID VIEW_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");

  @TempDir Path temporaryDirectory;

  @Test
  void derivesTheOnlyAllowedFileAndHashesItBeforeLoading() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var content = "local schematic".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(root.resolve(VIEW_ID + ".litematica"), content);
    var adapter = new CapturingAdapter();
    var controller = new LitematicaClientController(Optional.of(adapter), root, 1024);

    var prepared = controller.prepareLoad(VIEW_ID, "Preview");
    assertEquals(0, adapter.loadCalls);
    var report = controller.load(prepared, 4, 70, -2);

    assertEquals(LitematicaDisplayReport.State.LOADED, report.state());
    assertEquals(root.resolve(VIEW_ID + ".litematica"), adapter.loaded.managedFile());
    assertEquals(content.length, adapter.loaded.managedFileBytes());
    assertEquals(sha256(content), adapter.loaded.contentSha256());
    assertEquals(4, adapter.loaded.originX());
    assertEquals(70, adapter.loaded.originY());
    assertEquals(-2, adapter.loaded.originZ());

    assertEquals(LitematicaDisplayReport.State.REMOVED, controller.remove(VIEW_ID).state());
    assertEquals(
        LitematicaDisplayReport.State.MATERIAL_LIST_OPEN,
        controller.openMaterialList(VIEW_ID).state());
    assertEquals(1, adapter.removeCalls);
    assertEquals(1, adapter.materialCalls);
  }

  @Test
  void absentAdapterIsStableUnavailableAndCarriesNoAuthority() throws IOException {
    var root = temporaryDirectory.resolve("not-created-for-unavailable-adapter");
    var controller = new LitematicaClientController(Optional.empty(), root, 8);

    assertFalse(controller.available());
    assertUnavailable(controller.load(controller.prepareLoad(VIEW_ID, "Preview"), 0, 0, 0));
    assertUnavailable(controller.remove(VIEW_ID));
    assertUnavailable(controller.openMaterialList(VIEW_ID));
  }

  @Test
  void rejectsOversizedEmptyAndSymlinkedManagedFiles() throws Exception {
    var root = Files.createDirectory(temporaryDirectory.resolve("managed"));
    var adapter = new CapturingAdapter();
    var controller = new LitematicaClientController(Optional.of(adapter), root, 8);
    Path expected = root.resolve(VIEW_ID + ".litematica");

    Files.write(expected, new byte[9]);
    assertFileUnavailable(controller.load(controller.prepareLoad(VIEW_ID, "Preview"), 0, 0, 0));

    Files.write(expected, new byte[0]);
    assertFileUnavailable(controller.load(controller.prepareLoad(VIEW_ID, "Preview"), 0, 0, 0));

    Files.delete(expected);
    Path outside = Files.write(temporaryDirectory.resolve("outside.litematica"), new byte[] {1});
    try {
      Files.createSymbolicLink(expected, outside);
      assertFileUnavailable(controller.load(controller.prepareLoad(VIEW_ID, "Preview"), 0, 0, 0));
    } catch (UnsupportedOperationException exception) {
      assertTrue(Files.notExists(expected));
    }
    assertEquals(0, adapter.loadCalls);
  }

  private static void assertUnavailable(LitematicaDisplayReport report) {
    assertEquals(LitematicaDisplayReport.State.FAILED, report.state());
    assertEquals(
        LitematicaDisplayReport.Failure.ADAPTER_UNAVAILABLE, report.failure().orElseThrow());
  }

  private static void assertFileUnavailable(LitematicaDisplayReport report) {
    assertEquals(LitematicaDisplayReport.State.FAILED, report.state());
    assertEquals(
        LitematicaDisplayReport.Failure.MANAGED_FILE_UNAVAILABLE, report.failure().orElseThrow());
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private static final class CapturingAdapter implements LitematicaAdapter {
    private LitematicaPreviewRequest loaded;
    private int loadCalls;
    private int removeCalls;
    private int materialCalls;

    @Override
    public LitematicaSupportMatrix.Entry supportedCombination() {
      return LitematicaSupportMatrix.supported().getFirst();
    }

    @Override
    public LitematicaDisplayReport loadPreview(LitematicaPreviewRequest request) {
      loaded = request;
      loadCalls++;
      return LitematicaDisplayReport.success(
          request.previewId(), request.contentSha256(), LitematicaDisplayReport.State.LOADED);
    }

    @Override
    public LitematicaDisplayReport removePreview(UUID previewId) {
      removeCalls++;
      return LitematicaDisplayReport.success(
          previewId, null, LitematicaDisplayReport.State.REMOVED);
    }

    @Override
    public LitematicaDisplayReport openMaterialList(UUID previewId) {
      materialCalls++;
      return LitematicaDisplayReport.success(
          previewId, null, LitematicaDisplayReport.State.MATERIAL_LIST_OPEN);
    }

    @Override
    public int loadedPreviewCount() {
      return loaded == null ? 0 : 1;
    }

    @Override
    public void close() {}
  }
}
