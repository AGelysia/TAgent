package dev.minecraftagent.paper;

import dev.minecraftagent.paper.command.AdminToggleAuthorizer;
import dev.minecraftagent.paper.command.AgentCommand;
import dev.minecraftagent.paper.command.AgentControl;
import dev.minecraftagent.paper.command.PaperCommandRegistration;
import dev.minecraftagent.paper.lifecycle.AdminPolicy;
import dev.minecraftagent.paper.lifecycle.AgentHealth;
import dev.minecraftagent.paper.lifecycle.AgentStatus;
import dev.minecraftagent.paper.lifecycle.CoreReadiness;
import dev.minecraftagent.paper.lifecycle.OfflineReason;
import dev.minecraftagent.paper.lifecycle.PaperStartupCoordinator;
import dev.minecraftagent.paper.startup.LocalStartupChecks;
import dev.minecraftagent.paper.startup.StartupFailure;
import dev.minecraftagent.paper.state.FileDesiredModeStore;
import dev.minecraftagent.paper.transport.JavaHttpRuntimeConnector;
import dev.minecraftagent.paper.transport.RuntimeConnectionSettings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class MinecraftAgentPlugin extends JavaPlugin {
  private static final int MAX_DEFAULT_CONFIG_BYTES = 64 * 1024;
  private static final java.util.Set<java.nio.file.attribute.PosixFilePermission>
      PRIVATE_DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
  private static final java.util.Set<java.nio.file.attribute.PosixFilePermission>
      PRIVATE_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

  private final AtomicReference<PaperStartupCoordinator> coordinatorReference =
      new AtomicReference<>();

  @Override
  public void onEnable() {
    var dataDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
    var configPath = dataDirectory.resolve("config.yml");
    var minecraftVersion = getServer().getMinecraftVersion();
    var componentVersion = getPluginMeta().getVersion();
    var stateDirectoryReference = new AtomicReference<Path>();
    var desiredModeStoreReference = new AtomicReference<FileDesiredModeStore>();
    var worker =
        Executors.newSingleThreadExecutor(
            runnable -> {
              var thread = new Thread(runnable, "minecraft-agent-paper-startup");
              return thread;
            });
    var localChecks = new LocalStartupChecks();
    var connector = new JavaHttpRuntimeConnector(worker);

    var registration =
        new PaperCommandRegistration(
            getServer().getCommandMap(),
            getServer()::getOnlinePlayers,
            Bukkit::isPrimaryThread,
            code -> logWarning("command-registration", code));
    AgentControl control =
        new AgentControl() {
          @Override
          public void turnOff() {
            var current = coordinatorReference.get();
            if (current != null) {
              current.turnOff();
            }
          }

          @Override
          public RecoveryRequest turnOn() {
            var current = coordinatorReference.get();
            if (current == null) {
              return new RecoveryRequest(
                  RecoveryDisposition.UNAVAILABLE, CompletableFuture.completedFuture(false));
            }
            return current.turnOn();
          }
        };
    var command =
        new AgentCommand(
            this,
            () -> {
              var current = coordinatorReference.get();
              return current == null ? AgentStatus.unregistered(null) : current.diagnostics();
            },
            control,
            new AdminToggleAuthorizer(
                () -> {
                  var current = coordinatorReference.get();
                  return current == null ? AdminPolicy.locked() : current.adminPolicy();
                }),
            task -> getServer().getScheduler().runTask(this, task));

    var coordinator =
        new PaperStartupCoordinator(
            worker,
            () -> {
              installDefaultConfig(dataDirectory, configPath);
              var result =
                  localChecks.run(
                      new LocalStartupChecks.Request(
                          configPath,
                          dataDirectory,
                          System.getenv(),
                          Runtime.version().feature(),
                          minecraftVersion));
              var runtime = result.config().runtime();
              var settings =
                  new RuntimeConnectionSettings(
                      runtime.endpoint(),
                      result.config().serverId(),
                      runtime.serverToken(),
                      componentVersion,
                      runtime.connectTimeout(),
                      runtime.handshakeTimeout());
              var warnings =
                  result.warnings().stream().map(warning -> warning.code().name()).toList();
              var checkedStateDirectory = result.config().stateDirectory();
              var trustedStateDirectory = stateDirectoryReference.get();
              if (trustedStateDirectory == null) {
                stateDirectoryReference.set(checkedStateDirectory);
                desiredModeStoreReference.set(new FileDesiredModeStore(checkedStateDirectory));
              } else if (!trustedStateDirectory.equals(checkedStateDirectory)) {
                throw new StartupFailure(
                    StartupFailure.Code.STATE_DIRECTORY_UNSAFE, StartupFailure.Stage.STATE);
              }
              var desiredModeStore = desiredModeStoreReference.get();
              var desiredMode = desiredModeStore.load();
              var policy = result.config().securityPolicy();
              return new CoreReadiness(
                  settings,
                  warnings,
                  desiredMode,
                  desiredModeStore,
                  new AdminPolicy(result.config().owners(), policy.allowOpToggle()));
            },
            connector,
            task -> getServer().getScheduler().runTask(this, task),
            new PaperStartupCoordinator.CommandGate() {
              @Override
              public void register() {
                registration.register(command);
              }

              @Override
              public void unregister() {
                registration.unregister();
              }
            },
            this::isEnabled,
            new PaperStartupCoordinator.EventSink() {
              @Override
              public void failure(String stage, String code) {
                getLogger().severe("event=startup_failed stage=" + stage + " code=" + code);
              }

              @Override
              public void warning(String stage, String code) {
                logWarning(stage, code);
              }

              @Override
              public void available(AgentHealth health) {
                getLogger().info("event=startup_ready health=" + health.name());
              }

              @Override
              public void offline(OfflineReason reason) {
                getLogger().info("event=agent_offline reason=" + reason.name());
              }
            });
    coordinatorReference.set(coordinator);
    coordinator.start();
    getLogger().info("event=startup_started");
  }

  @Override
  public void onDisable() {
    var current = coordinatorReference.getAndSet(null);
    if (current != null) {
      current.close();
    }
  }

  private void logWarning(String stage, String code) {
    getLogger().warning("event=startup_warning stage=" + stage + " code=" + code);
  }

  private static void installDefaultConfig(Path dataDirectory, Path configPath)
      throws StartupFailure {
    if (Files.exists(configPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      return;
    }

    Path temporary = null;
    try {
      Files.createDirectories(dataDirectory);
      Files.setPosixFilePermissions(dataDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
      temporary = dataDirectory.resolve(".config-install-" + UUID.randomUUID());
      try (var input =
              MinecraftAgentPlugin.class.getClassLoader().getResourceAsStream("config.yml");
          var output =
              FileChannel.open(
                  temporary,
                  java.util.Set.of(
                      java.nio.file.StandardOpenOption.CREATE_NEW,
                      java.nio.file.StandardOpenOption.WRITE),
                  PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS))) {
        if (input == null) {
          throw configFailure();
        }
        var content = input.readNBytes(MAX_DEFAULT_CONFIG_BYTES + 1);
        if (content.length > MAX_DEFAULT_CONFIG_BYTES) {
          throw configFailure();
        }
        var buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) {
          output.write(buffer);
        }
        output.force(true);
      }
      moveConfig(temporary, configPath);
      temporary = null;
      Files.setPosixFilePermissions(configPath, PRIVATE_FILE_PERMISSIONS);
    } catch (StartupFailure failure) {
      throw failure;
    } catch (FileAlreadyExistsException exception) {
      // Another startup task installed the same immutable default; the loader validates it next.
    } catch (IOException | SecurityException | UnsupportedOperationException exception) {
      throw configFailure();
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // The stable startup failure remains authoritative.
        }
      }
    }
  }

  private static void moveConfig(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(source, target);
    }
  }

  private static StartupFailure configFailure() {
    return new StartupFailure(
        StartupFailure.Code.PAPER_CONFIG_INVALID, StartupFailure.Stage.CONFIG);
  }
}
