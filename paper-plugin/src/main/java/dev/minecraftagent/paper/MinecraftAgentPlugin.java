package dev.minecraftagent.paper;

import dev.minecraftagent.paper.audit.FileProposalAuditLog;
import dev.minecraftagent.paper.capability.PaperInstalledPluginInventory;
import dev.minecraftagent.paper.capability.load.CapabilityPackLoader;
import dev.minecraftagent.paper.capability.model.CapabilityDiagnostic;
import dev.minecraftagent.paper.capability.registry.CapabilityRegistry;
import dev.minecraftagent.paper.client.ClientConnectionRegistry;
import dev.minecraftagent.paper.client.ClientPayloadCodec;
import dev.minecraftagent.paper.client.ClientStateCoordinator;
import dev.minecraftagent.paper.client.ClientTransferManager;
import dev.minecraftagent.paper.client.ClientUiCommandGateway;
import dev.minecraftagent.paper.client.ClientViewPublisher;
import dev.minecraftagent.paper.client.ClientViewSchemaRegistry;
import dev.minecraftagent.paper.client.ClientViewSelector;
import dev.minecraftagent.paper.client.PaperClientChannel;
import dev.minecraftagent.paper.command.AdminToggleAuthorizer;
import dev.minecraftagent.paper.command.AgentCommand;
import dev.minecraftagent.paper.command.AgentControl;
import dev.minecraftagent.paper.command.AgentUiControl;
import dev.minecraftagent.paper.command.PaperCommandRegistration;
import dev.minecraftagent.paper.command.ProposalResponseGateway;
import dev.minecraftagent.paper.lifecycle.AdminPolicy;
import dev.minecraftagent.paper.lifecycle.AgentHealth;
import dev.minecraftagent.paper.lifecycle.AgentStatus;
import dev.minecraftagent.paper.lifecycle.CoreReadiness;
import dev.minecraftagent.paper.lifecycle.OfflineCleanup;
import dev.minecraftagent.paper.lifecycle.OfflineReason;
import dev.minecraftagent.paper.lifecycle.OperationalGate;
import dev.minecraftagent.paper.lifecycle.PaperStartupCoordinator;
import dev.minecraftagent.paper.proposal.InMemoryProposalRepository;
import dev.minecraftagent.paper.proposal.ProposalAuthorizer;
import dev.minecraftagent.paper.proposal.ProposalConfirmationResult;
import dev.minecraftagent.paper.proposal.ProposalService;
import dev.minecraftagent.paper.request.AgentPlayerListener;
import dev.minecraftagent.paper.request.AgentRequestService;
import dev.minecraftagent.paper.startup.LocalStartupChecks;
import dev.minecraftagent.paper.startup.StartupFailure;
import dev.minecraftagent.paper.startup.StartupWarning;
import dev.minecraftagent.paper.state.FileDesiredModeStore;
import dev.minecraftagent.paper.tool.BukkitReadToolExecutor;
import dev.minecraftagent.paper.tool.ReadToolRegistry;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
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
  private final AtomicReference<AgentRequestService> requestServiceReference =
      new AtomicReference<>();
  private final AtomicReference<PaperClientChannel> clientChannelReference =
      new AtomicReference<>();
  private final AtomicReference<ProposalService> proposalServiceReference = new AtomicReference<>();
  private final AtomicReference<ProposalAuthorizer.Policy> proposalPolicyReference =
      new AtomicReference<>(lockedProposalPolicy());
  private final CapabilityRegistry capabilityRegistry = new CapabilityRegistry();

  @Override
  public void onEnable() {
    clientChannelReference.set(null);
    proposalServiceReference.set(null);
    proposalPolicyReference.set(lockedProposalPolicy());
    var dataDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
    var configPath = dataDirectory.resolve("config.yml");
    var minecraftVersion = getServer().getMinecraftVersion();
    var componentVersion = getPluginMeta().getVersion();
    var stateDirectoryReference = new AtomicReference<Path>();
    var desiredModeStoreReference = new AtomicReference<FileDesiredModeStore>();
    var worker =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              var thread = new Thread(runnable, "minecraft-agent-paper-io");
              return thread;
            });
    var localChecks = new LocalStartupChecks();
    var pluginInventory =
        new PaperInstalledPluginInventory(
            java.util.Arrays.asList(getServer().getPluginManager().getPlugins()));
    getServer().getPluginManager().registerEvents(pluginInventory, this);
    var connector = new JavaHttpRuntimeConnector(worker);
    var operationalGate = new OperationalGate();
    var proposalAuthorizer =
        new ProposalAuthorizer(
            proposalPolicyReference::get,
            new ProposalAuthorizer.LivePlayerPolicy() {
              @Override
              public boolean isOnline(UUID playerUuid) {
                var player = getServer().getPlayer(playerUuid);
                return player != null && player.isOnline();
              }

              @Override
              public boolean isOperator(UUID playerUuid) {
                var player = getServer().getPlayer(playerUuid);
                return player != null && player.isOnline() && player.isOp();
              }

              @Override
              public boolean hasPermission(UUID playerUuid, String permission) {
                var player = getServer().getPlayer(playerUuid);
                return player != null && player.isOnline() && player.hasPermission(permission);
              }
            });
    var toolRegistry = new ReadToolRegistry();
    var toolExecutor =
        new BukkitReadToolExecutor(
            getServer(), toolRegistry, task -> getServer().getScheduler().runTask(this, task));
    var clientConnections = new ClientConnectionRegistry();
    var clientTransfers = ClientTransferManager.withProductionLimits();
    var clientState = new ClientStateCoordinator(clientConnections, clientTransfers);
    var clientChannel =
        new PaperClientChannel(this, new ClientPayloadCodec(), clientState, clientTransfers);
    clientChannel.start();
    clientChannelReference.set(clientChannel);
    var clientViews =
        new ClientViewPublisher(
            new ClientViewSelector(clientConnections, ClientViewSchemaRegistry.versionOne()),
            clientTransfers);
    var clientUi = new ClientUiCommandGateway(clientConnections, clientChannel.uiControlSink());
    var requests =
        new AgentRequestService(
            operationalGate,
            Duration.ofSeconds(90),
            worker,
            task -> getServer().getScheduler().runTask(this, task),
            (playerId, message) -> {
              var player = getServer().getPlayer(playerId);
              if (player != null && player.isOnline()) {
                player.sendMessage(Component.text(message));
              }
            },
            code -> getLogger().warning("event=request_warning code=" + code),
            toolRegistry,
            toolExecutor,
            worker,
            clientState::capabilitySnapshot,
            (playerId, fallbackText, views) -> {
              var publication = clientViews.prepare(playerId, fallbackText, views, Instant.now());
              return new AgentRequestService.PreparedStructuredReply() {
                @Override
                public boolean send() {
                  var player = getServer().getPlayer(playerId);
                  return player != null
                      && player.isOnline()
                      && clientChannel.sendPublication(player, publication);
                }

                @Override
                public void discard() {
                  clientViews.discard(publication);
                }
              };
            });
    requestServiceReference.set(requests);
    getServer()
        .getPluginManager()
        .registerEvents(new AgentPlayerListener(requests, this::invalidatePlayerProposals), this);

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
            requests,
            proposalResponses(),
            (playerId, action) -> {
              var clientAction =
                  switch (action) {
                    case PIN -> ClientUiCommandGateway.Action.PIN;
                    case UNPIN -> ClientUiCommandGateway.Action.UNPIN;
                    case CLEAR -> ClientUiCommandGateway.Action.CLEAR;
                  };
              return clientUi.invoke(playerId, clientAction) == ClientUiCommandGateway.Result.SENT
                  ? AgentUiControl.Result.SENT
                  : AgentUiControl.Result.CLIENT_UNAVAILABLE;
            },
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
                  new TreeSet<>(
                      result.warnings().stream().map(warning -> warning.code().name()).toList());
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
              var proposalPolicy =
                  new ProposalAuthorizer.Policy(
                      result.config().owners(),
                      proposalWriteAccess(policy.worldWrite()),
                      proposalWriteAccess(policy.playerWrite()));
              var proposalAudit = FileProposalAuditLog.open(checkedStateDirectory);
              var proposalService =
                  new ProposalService(
                      result.config().serverId(),
                      new InMemoryProposalRepository(),
                      ignored -> java.util.Optional.empty(),
                      proposalAuthorizer,
                      ignored -> false,
                      proposalAudit,
                      operationalGate,
                      Clock.systemUTC(),
                      Duration.ofSeconds(60));
              refreshCapabilityCatalog(result, pluginInventory, warnings);
              proposalPolicyReference.set(proposalPolicy);
              proposalServiceReference.set(proposalService);
              return new CoreReadiness(
                  settings,
                  List.copyOf(warnings),
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
            },
            new OfflineCleanup(
                requests,
                (epoch, reason) -> {
                  var proposals = proposalServiceReference.get();
                  if (proposals != null) {
                    proposals.quiesce(epoch, reason);
                  }
                },
                (epoch, reason) -> {},
                (epoch, reason) -> clientChannel.clearTransientViews()),
            operationalGate,
            requests);
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
    proposalServiceReference.set(null);
    proposalPolicyReference.set(lockedProposalPolicy());
    var requests = requestServiceReference.getAndSet(null);
    if (requests != null) {
      requests.close();
    }
    var clientChannel = clientChannelReference.getAndSet(null);
    if (clientChannel != null) {
      clientChannel.close();
    }
  }

  private void logWarning(String stage, String code) {
    getLogger().warning("event=startup_warning stage=" + stage + " code=" + code);
  }

  private void refreshCapabilityCatalog(
      dev.minecraftagent.paper.startup.LocalStartupResult startup,
      PaperInstalledPluginInventory pluginInventory,
      Set<String> warnings) {
    if (startup.warnings().stream()
        .anyMatch(
            warning -> warning.code() == StartupWarning.Code.OPTIONAL_CAPABILITY_UNAVAILABLE)) {
      getLogger()
          .warning(
              "event=capability_catalog status=RETAINED generation="
                  + capabilityRegistry.snapshot().generation()
                  + " code=OPTIONAL_CAPABILITY_UNAVAILABLE");
      return;
    }

    var approvals = startup.config().capabilityApprovals();
    var loaded =
        new CapabilityPackLoader(pluginInventory, approvals::contains)
            .load(startup.config().optionalCapabilityDirectory());
    var preview = capabilityRegistry.preview(loaded);
    var publication = capabilityRegistry.publish(preview);
    if (publication.status() != CapabilityRegistry.PublishStatus.PUBLISHED) {
      warnings.add(StartupWarning.Code.CAPABILITY_CATALOG_UNAVAILABLE.name());
    } else if (loaded.drafts().stream().anyMatch(draft -> !draft.enabled())) {
      warnings.add(StartupWarning.Code.CAPABILITY_PACK_DISABLED.name());
    }

    var diffPrefix =
        publication.status() == CapabilityRegistry.PublishStatus.PUBLISHED ? "" : "proposed_";
    getLogger()
        .info(
            "event=capability_catalog status="
                + publication.status().name()
                + " generation="
                + publication.snapshot().generation()
                + " "
                + diffPrefix
                + "added="
                + formatCapabilityIds(preview.diff().added())
                + " "
                + diffPrefix
                + "removed="
                + formatCapabilityIds(preview.diff().removed())
                + " "
                + diffPrefix
                + "changed="
                + formatCapabilityIds(preview.diff().changed())
                + " "
                + diffPrefix
                + "unchanged="
                + formatCapabilityIds(preview.diff().unchanged()));

    var diagnosticCounts = new TreeMap<CapabilityDiagnostic.Code, Integer>();
    for (var diagnostic : loaded.globalDiagnostics()) {
      getLogger().warning("event=capability_catalog_diagnostic code=" + diagnostic.code().name());
    }
    for (var draft : loaded.drafts()) {
      for (var diagnostic : draft.diagnostics()) {
        diagnosticCounts.merge(diagnostic.code(), 1, Integer::sum);
      }
      if (draft.diagnostics().stream()
          .anyMatch(
              diagnostic -> diagnostic.code() == CapabilityDiagnostic.Code.APPROVAL_REQUIRED)) {
        draft
            .identity()
            .ifPresent(
                identity ->
                    getLogger()
                        .info(
                            "event=capability_approval_required id="
                                + identity.id()
                                + " version="
                                + identity.version()
                                + " sha256="
                                + identity.contentSha256()));
      }
    }
    for (var entry : diagnosticCounts.entrySet()) {
      getLogger()
          .warning(
              "event=capability_manifest_disabled code="
                  + entry.getKey().name()
                  + " count="
                  + entry.getValue());
    }
  }

  private static String formatCapabilityIds(Set<String> ids) {
    return ids.isEmpty() ? "-" : String.join(",", ids);
  }

  private ProposalResponseGateway proposalResponses() {
    return new ProposalResponseGateway() {
      @Override
      public java.util.concurrent.CompletionStage<Result> confirm(UUID playerId, UUID proposalId) {
        var proposals = proposalServiceReference.get();
        if (proposals == null) {
          return CompletableFuture.completedFuture(Result.UNAVAILABLE);
        }
        var result = proposals.confirm(proposalId, playerId);
        if (result.status() == ProposalConfirmationResult.Status.EXECUTED
            && "EXECUTED_AUDIT_INCOMPLETE".equals(result.code())) {
          getLogger().warning("event=proposal_audit_incomplete code=POST_EXECUTION_AUDIT_FAILED");
        }
        return CompletableFuture.completedFuture(mapProposalResult(result, true));
      }

      @Override
      public java.util.concurrent.CompletionStage<Result> reject(UUID playerId, UUID proposalId) {
        var proposals = proposalServiceReference.get();
        if (proposals == null) {
          return CompletableFuture.completedFuture(Result.UNAVAILABLE);
        }
        return CompletableFuture.completedFuture(
            mapProposalResult(proposals.reject(proposalId, playerId), false));
      }
    };
  }

  private void invalidatePlayerProposals(UUID playerId) {
    var proposals = proposalServiceReference.get();
    if (proposals == null) {
      return;
    }
    try {
      proposals.invalidatePlayer(playerId);
    } catch (RuntimeException error) {
      getLogger().warning("event=proposal_warning code=PLAYER_INVALIDATION_AUDIT_FAILED");
    }
  }

  private static ProposalResponseGateway.Result mapProposalResult(
      ProposalConfirmationResult result, boolean confirmation) {
    return switch (result.status()) {
      case EXECUTED -> ProposalResponseGateway.Result.CONFIRMED;
      case FAILED -> ProposalResponseGateway.Result.FAILED;
      case REJECTED ->
          !confirmation && "PLAYER_REJECTED".equals(result.code())
              ? ProposalResponseGateway.Result.REJECTED
              : ProposalResponseGateway.Result.UNAVAILABLE;
    };
  }

  private static ProposalAuthorizer.WriteAccess proposalWriteAccess(
      dev.minecraftagent.paper.startup.SecurityPolicy.AccessLevel access) {
    return switch (access) {
      case OP -> ProposalAuthorizer.WriteAccess.OP;
      case OWNER -> ProposalAuthorizer.WriteAccess.OWNER;
    };
  }

  private static ProposalAuthorizer.Policy lockedProposalPolicy() {
    return new ProposalAuthorizer.Policy(
        Set.of(), ProposalAuthorizer.WriteAccess.OWNER, ProposalAuthorizer.WriteAccess.OWNER);
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
