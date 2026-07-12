package dev.minecraftagent.paper.startup;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public final class PaperStartupConfig {
  public static final class RuntimeSettings {
    private final URI endpoint;
    private final String serverToken;
    private final Duration connectTimeout;
    private final Duration handshakeTimeout;

    public RuntimeSettings(
        URI endpoint, String serverToken, Duration connectTimeout, Duration handshakeTimeout) {
      this.endpoint = Objects.requireNonNull(endpoint);
      this.serverToken = Objects.requireNonNull(serverToken);
      this.connectTimeout = Objects.requireNonNull(connectTimeout);
      this.handshakeTimeout = Objects.requireNonNull(handshakeTimeout);
    }

    public URI endpoint() {
      return endpoint;
    }

    public String serverToken() {
      return serverToken;
    }

    public Duration connectTimeout() {
      return connectTimeout;
    }

    public Duration handshakeTimeout() {
      return handshakeTimeout;
    }

    @Override
    public String toString() {
      return "RuntimeSettings[endpoint=loopback, serverToken=<redacted>, connectTimeout="
          + connectTimeout
          + ", handshakeTimeout="
          + handshakeTimeout
          + "]";
    }
  }

  private final String serverId;
  private final RuntimeSettings runtime;
  private final Path stateDirectory;
  private final SecurityPolicy securityPolicy;
  private final Path optionalCapabilityDirectory;

  public PaperStartupConfig(
      String serverId,
      RuntimeSettings runtime,
      Path stateDirectory,
      SecurityPolicy securityPolicy,
      Path optionalCapabilityDirectory) {
    this.serverId = Objects.requireNonNull(serverId);
    this.runtime = Objects.requireNonNull(runtime);
    this.stateDirectory = Objects.requireNonNull(stateDirectory);
    this.securityPolicy = Objects.requireNonNull(securityPolicy);
    this.optionalCapabilityDirectory = Objects.requireNonNull(optionalCapabilityDirectory);
  }

  public String serverId() {
    return serverId;
  }

  public RuntimeSettings runtime() {
    return runtime;
  }

  public Path stateDirectory() {
    return stateDirectory;
  }

  public SecurityPolicy securityPolicy() {
    return securityPolicy;
  }

  public Path optionalCapabilityDirectory() {
    return optionalCapabilityDirectory;
  }

  @Override
  public String toString() {
    return "PaperStartupConfig[serverId="
        + serverId
        + ", runtime="
        + runtime
        + ", stateDirectory=<redacted>, securityPolicy="
        + securityPolicy
        + ", optionalCapabilityDirectory=<redacted>]";
  }
}
