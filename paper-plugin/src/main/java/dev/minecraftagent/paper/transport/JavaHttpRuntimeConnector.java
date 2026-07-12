package dev.minecraftagent.paper.transport;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class JavaHttpRuntimeConnector implements RuntimeConnector {
  static final int MAX_HANDSHAKE_BYTES = 16 * 1024;

  private final HttpClient httpClient;
  private final HandshakeCodec codec;

  public JavaHttpRuntimeConnector(Executor executor) {
    this(
        HttpClient.newBuilder()
            .executor(executor)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        Clock.systemUTC());
  }

  JavaHttpRuntimeConnector(HttpClient httpClient, Clock clock) {
    this.httpClient = Objects.requireNonNull(httpClient);
    this.codec = new HandshakeCodec(new SecureRandom(), () -> clock.instant(), new ReplayCache());
  }

  @Override
  public CompletionStage<AuthenticatedRuntimeConnection> connect(
      RuntimeConnectionSettings settings) {
    var listener = new HandshakeListener();
    var hello = codec.createPaperHello(settings);
    var socketReference = new AtomicReference<WebSocket>();

    var socketFuture =
        httpClient
            .newWebSocketBuilder()
            .connectTimeout(settings.connectTimeout())
            .buildAsync(settings.endpoint(), listener)
            .orTimeout(settings.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .handle(
                (socket, error) -> {
                  if (error == null) {
                    return socket;
                  }
                  if (unwrap(error) instanceof TimeoutException) {
                    throw new CompletionException(
                        new RuntimeConnectionFailure("RUNTIME_CONNECT_TIMEOUT", "runtime-connect"));
                  }
                  throw new CompletionException(
                      new RuntimeConnectionFailure("RUNTIME_UNREACHABLE", "runtime-connect"));
                });

    var result =
        socketFuture
            .thenCompose(
                socket -> {
                  socketReference.set(socket);
                  listener.attach(socket);
                  var response =
                      listener
                          .firstMessage()
                          .orTimeout(settings.handshakeTimeout().toMillis(), TimeUnit.MILLISECONDS);
                  return socket
                      .sendText(hello.text(), true)
                      .orTimeout(settings.handshakeTimeout().toMillis(), TimeUnit.MILLISECONDS)
                      .thenCompose(ignored -> response);
                })
            .thenApply(
                response -> {
                  codec.validateRuntimeHello(response, hello, settings);
                  var socket = socketReference.get();
                  if (socket == null || listener.closed().isDone()) {
                    throw new RuntimeConnectionFailure("RUNTIME_UNREACHABLE", "runtime-connect");
                  }
                  listener.markAuthenticated();
                  return (AuthenticatedRuntimeConnection)
                      new JavaHttpAuthenticatedConnection(socket, listener);
                });

    return result.handle(
        (connection, error) -> {
          if (error == null) {
            return connection;
          }
          var socket = socketReference.get();
          if (socket != null) {
            socket.abort();
          }
          throw mapFailure(error);
        });
  }

  private static CompletionException mapFailure(Throwable error) {
    var cause = unwrap(error);
    if (cause instanceof RuntimeConnectionFailure failure) {
      return new CompletionException(failure);
    }
    if (cause instanceof TimeoutException) {
      return new CompletionException(
          new RuntimeConnectionFailure("HANDSHAKE_TIMEOUT", "runtime-connect"));
    }
    return new CompletionException(
        new RuntimeConnectionFailure("RUNTIME_UNREACHABLE", "runtime-connect"));
  }

  private static Throwable unwrap(Throwable error) {
    var current = error;
    while ((current instanceof CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static String strictUtf8(CharSequence text) {
    try {
      var encoder =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      var encoded = encoder.encode(CharBuffer.wrap(text));
      if (encoded.remaining() > MAX_HANDSHAKE_BYTES) {
        throw new RuntimeConnectionFailure("HANDSHAKE_MESSAGE_TOO_LARGE", "authentication");
      }
      var bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (CharacterCodingException error) {
      throw new RuntimeConnectionFailure("HANDSHAKE_MESSAGE_INVALID", "authentication");
    }
  }

  private static final class HandshakeListener implements WebSocket.Listener {
    private final CompletableFuture<String> firstMessage = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final StringBuilder fragments = new StringBuilder();
    private final AtomicBoolean receivedMessage = new AtomicBoolean();
    private volatile WebSocket socket;
    private volatile boolean authenticated;

    void attach(WebSocket socket) {
      this.socket = socket;
      socket.request(1);
    }

    CompletableFuture<String> firstMessage() {
      return firstMessage;
    }

    CompletableFuture<Void> closed() {
      return closed;
    }

    void markAuthenticated() {
      authenticated = true;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      socket = webSocket;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      if (receivedMessage.get() || fragments.length() + data.length() > MAX_HANDSHAKE_BYTES) {
        fail(
            new RuntimeConnectionFailure(
                receivedMessage.get() ? "HANDSHAKE_MESSAGE_INVALID" : "HANDSHAKE_MESSAGE_TOO_LARGE",
                "authentication"));
        webSocket.abort();
        return null;
      }
      fragments.append(data);
      if (last) {
        receivedMessage.set(true);
        try {
          firstMessage.complete(strictUtf8(fragments));
        } catch (RuntimeConnectionFailure failure) {
          fail(failure);
          webSocket.abort();
          return null;
        }
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      fail(new RuntimeConnectionFailure("HANDSHAKE_MESSAGE_INVALID", "authentication"));
      webSocket.abort();
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      if (!authenticated) {
        firstMessage.completeExceptionally(closeFailure(statusCode, reason));
      }
      closed.complete(null);
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      fail(new RuntimeConnectionFailure("RUNTIME_UNREACHABLE", "runtime-connect"));
      closed.complete(null);
    }

    private void fail(RuntimeConnectionFailure failure) {
      firstMessage.completeExceptionally(failure);
    }

    private static RuntimeConnectionFailure closeFailure(int statusCode, String reason) {
      if (statusCode != 1008) {
        return new RuntimeConnectionFailure("RUNTIME_UNREACHABLE", "runtime-connect");
      }
      return switch (reason) {
        case "AUTHENTICATION_FAILED" ->
            new RuntimeConnectionFailure("TOKEN_AUTH_FAILED", "authentication");
        case "PROTOCOL_INCOMPATIBLE" ->
            new RuntimeConnectionFailure("PROTOCOL_INCOMPATIBLE", "protocol");
        case "HANDSHAKE_REPLAYED" ->
            new RuntimeConnectionFailure("HANDSHAKE_REPLAYED", "authentication");
        case "HANDSHAKE_STALE" -> new RuntimeConnectionFailure("HANDSHAKE_STALE", "authentication");
        case "HANDSHAKE_TIMEOUT" ->
            new RuntimeConnectionFailure("HANDSHAKE_TIMEOUT", "runtime-connect");
        case "HANDSHAKE_INVALID", "UNSUPPORTED_MESSAGE_TYPE" ->
            new RuntimeConnectionFailure("HANDSHAKE_MESSAGE_INVALID", "authentication");
        default -> new RuntimeConnectionFailure("RUNTIME_UNREACHABLE", "runtime-connect");
      };
    }

    void abort() {
      var current = socket;
      if (current != null) {
        current.abort();
      }
      closed.complete(null);
    }
  }

  private static final class JavaHttpAuthenticatedConnection
      implements AuthenticatedRuntimeConnection {
    private final WebSocket socket;
    private final HandshakeListener listener;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private JavaHttpAuthenticatedConnection(WebSocket socket, HandshakeListener listener) {
      this.socket = socket;
      this.listener = listener;
      listener.closed().thenRun(() -> open.set(false));
    }

    @Override
    public boolean isOpen() {
      return open.get() && !listener.closed().isDone();
    }

    @Override
    public CompletionStage<Void> whenClosed() {
      return listener.closed();
    }

    @Override
    public void close() {
      if (open.compareAndSet(true, false)) {
        listener.abort();
        socket.abort();
      }
    }
  }
}
