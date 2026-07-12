package dev.minecraftagent.paper.startup;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

public final class StateDirectoryProbe {
  private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");
  private static final byte[] PROBE_CONTENT =
      "minecraft-agent-state-probe\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  public void verify(Path dataDirectory, Path stateDirectory) throws StartupFailure {
    var root = dataDirectory.toAbsolutePath().normalize();
    var target = stateDirectory.toAbsolutePath().normalize();
    if (target.equals(root) || !target.startsWith(root)) {
      throw unsafe();
    }

    try {
      ensureDataDirectory(root);
      createPrivatePath(root, target);
      verifyPrivatePermissions(target, PRIVATE_DIRECTORY_PERMISSIONS);
      probeWrite(target);
    } catch (StartupFailure failure) {
      throw failure;
    } catch (IOException exception) {
      throw unavailable();
    } catch (SecurityException | UnsupportedOperationException exception) {
      throw unsafe();
    }
  }

  private static void ensureDataDirectory(Path root) throws IOException, StartupFailure {
    if (!Files.exists(root, NOFOLLOW_LINKS)) {
      Files.createDirectories(
          root, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS));
    }
    var attributes = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
      throw unsafe();
    }
  }

  private static void createPrivatePath(Path root, Path target) throws IOException, StartupFailure {
    var current = root;
    for (var segment : root.relativize(target)) {
      current = current.resolve(segment);
      if (!Files.exists(current, NOFOLLOW_LINKS)) {
        try {
          Files.createDirectory(
              current, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS));
        } catch (FileAlreadyExistsException exception) {
          // Validate the path below; it may have appeared between the existence check and create.
        }
      }
      var attributes = Files.readAttributes(current, BasicFileAttributes.class, NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
        throw unsafe();
      }
      verifyPrivatePermissions(current, PRIVATE_DIRECTORY_PERMISSIONS);
    }
  }

  private static void probeWrite(Path directory) throws IOException, StartupFailure {
    var probe = directory.resolve(".startup-probe-" + UUID.randomUUID());
    var created = false;
    try {
      try (var channel =
          FileChannel.open(
              probe,
              Set.of(CREATE_NEW, WRITE),
              PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS))) {
        created = true;
        var buffer = ByteBuffer.wrap(PROBE_CONTENT);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      verifyPrivatePermissions(probe, PRIVATE_FILE_PERMISSIONS);
    } finally {
      if (created) {
        Files.deleteIfExists(probe);
      }
    }
  }

  private static void verifyPrivatePermissions(
      Path path, Set<PosixFilePermission> expectedPermissions) throws IOException, StartupFailure {
    var view = Files.getFileAttributeView(path, PosixFileAttributeView.class, NOFOLLOW_LINKS);
    if (view == null || !view.readAttributes().permissions().equals(expectedPermissions)) {
      throw unsafe();
    }
  }

  private static StartupFailure unavailable() {
    return new StartupFailure(
        StartupFailure.Code.STATE_DIRECTORY_UNAVAILABLE, StartupFailure.Stage.STATE);
  }

  private static StartupFailure unsafe() {
    return new StartupFailure(
        StartupFailure.Code.STATE_DIRECTORY_UNSAFE, StartupFailure.Stage.STATE);
  }
}
