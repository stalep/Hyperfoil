package io.hyperfoil.core.client.netty;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import io.hyperfoil.api.connection.HttpRequest;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.hyperfoil.api.connection.Connection;
import io.hyperfoil.api.connection.HttpClientPool;
import io.hyperfoil.api.connection.HttpConnection;
import io.hyperfoil.api.connection.HttpConnectionPool;
import io.hyperfoil.api.connection.HttpRequestWriter;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2EventAdapter;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.hyperfoil.api.http.HttpResponseHandlers;
import io.hyperfoil.api.session.Session;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
class Http2Connection extends Http2EventAdapter implements HttpConnection {
   private static final Logger log = LoggerFactory.getLogger(Http2Connection.class);

   private final HttpConnectionPool pool;
   private final ChannelHandlerContext context;
   private final io.netty.handler.codec.http2.Http2Connection connection;
   private final Http2ConnectionEncoder encoder;
   private final IntObjectMap<HttpRequest> streams = new IntObjectHashMap<>();
   private int numStreams;
   private long maxStreams;
   private boolean closed;

   Http2Connection(ChannelHandlerContext context,
                   io.netty.handler.codec.http2.Http2Connection connection,
                   Http2ConnectionEncoder encoder,
                   Http2ConnectionDecoder decoder,
                   HttpConnectionPool pool) {
      this.pool = pool;
      this.context = context;
      this.connection = connection;
      this.encoder = encoder;
      this.maxStreams = pool.clientPool().config().maxHttp2Streams();

      Http2EventAdapter listener = new EventAdapter();

      connection.addListener(listener);
      decoder.frameListener(listener);
   }

   @Override
   public ChannelHandlerContext context() {
      return context;
   }

   @Override
   public boolean isAvailable() {
      return numStreams < maxStreams;
   }

   @Override
   public int inFlight() {
      return numStreams;
   }

   public void incrementConnectionWindowSize(int increment) {
      try {
         io.netty.handler.codec.http2.Http2Stream stream = connection.connectionStream();
         connection.local().flowController().incrementWindowSize(stream, increment);
      } catch (Http2Exception e) {
         e.printStackTrace();
      }
   }

   @Override
   public void close() {
      cancelRequests(Connection.SELF_CLOSED_EXCEPTION);
      context.close();
   }

   @Override
   public String host() {
      return pool.clientPool().host();
   }

   public void request(HttpRequest request, BiConsumer<Session, HttpRequestWriter>[] headerAppenders, BiFunction<Session, Connection, ByteBuf> bodyGenerator) {
      numStreams++;
      HttpClientPool httpClientPool = pool.clientPool();

      ByteBuf buf = bodyGenerator != null ? bodyGenerator.apply(request.session, this) : null;

      Http2Headers headers = new DefaultHttp2Headers().method(request.method.name()).scheme(httpClientPool.scheme())
            .path(request.path).authority(httpClientPool.authority());
      headers.add(HttpHeaderNames.HOST, httpClientPool.authority());
      if (buf != null && buf.readableBytes() > 0) {
         headers.add(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(buf.readableBytes()));
      }

      if (headerAppenders != null) {
         HttpRequestWriter writer = new HttpRequestWriterImpl(headers);
         for (BiConsumer<Session, HttpRequestWriter> headerAppender : headerAppenders) {
            headerAppender.accept(request.session, writer);
         }
      }

      assert context.executor().inEventLoop();
      int id = nextStreamId();
      streams.put(id, request);
      ChannelPromise writePromise = context.newPromise();
      encoder.writeHeaders(context, id, headers, 0, buf == null, writePromise);
      if (buf != null) {
         writePromise = context.newPromise();
         encoder.writeData(context, id, buf, 0, true, writePromise);
      }
      writePromise.addListener(request);
      context.flush();
   }

   @Override
   public HttpRequest peekRequest(int streamId) {
      return streams.get(streamId);
   }

   @Override
   public void setClosed() {
      this.closed = true;
   }

   @Override
   public boolean isClosed() {
      return closed;
   }

   private int nextStreamId() {
      return connection.local().incrementAndGetNextStreamId();
   }

   @Override
   public String toString() {
      return "Http2Connection{" +
            context.channel().localAddress() + " -> " + context.channel().remoteAddress() +
            ", streams=" + streams +
            '}';
   }

   void cancelRequests(Throwable cause) {
      for (IntObjectMap.PrimitiveEntry<HttpRequest> entry : streams.entries()) {
         HttpRequest request = entry.value();
         if (!request.isCompleted()) {
            request.handlers().handleThrowable(request, cause);
            request.session.proceed();
         }
      }
   }

   private class EventAdapter extends Http2EventAdapter {

      @Override
      public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
         if (settings.maxConcurrentStreams() != null) {
            maxStreams = Math.min(pool.clientPool().config().maxHttp2Streams(), settings.maxConcurrentStreams());
         }
      }

      @Override
      public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endStream) {
         HttpRequest request = streams.get(streamId);
         if (request != null) {
            HttpResponseHandlers handlers = (HttpResponseHandlers) request.handlers();
            int code = -1;
            try {
               code = Integer.parseInt(headers.status().toString());
            } catch (NumberFormatException ignore) {
            }
            handlers.handleStatus(request, code, "");
            if (endStream) {
               endStream(streamId);
            }
         }
      }

      @Override
      public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
         int ack = super.onDataRead(ctx, streamId, data, padding, endOfStream);
         HttpRequest request = streams.get(streamId);
         if (request != null) {
            HttpResponseHandlers handlers = (HttpResponseHandlers) request.handlers();
            handlers.handleBodyPart(request, data);
            if (endOfStream) {
               endStream(streamId);
            }
         }
         return ack;
      }

      @Override
      public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
         HttpRequest request = streams.remove(streamId);
         if (request != null) {
            numStreams--;
            HttpResponseHandlers handlers = (HttpResponseHandlers) request.handlers();
            // TODO: maybe add a specific handler because we don't need to terminate other streams
            handlers.handleThrowable(request, new IOException("HTTP2 stream was reset"));
            tryReleaseToPool();
         }
      }

      private void endStream(int streamId) {
         HttpRequest request = streams.remove(streamId);
         if (request != null) {
            numStreams--;
            request.handlers().handleEnd(request);
            log.trace("Completed response on {}", this);
            tryReleaseToPool();
         }
      }
   }

   private void tryReleaseToPool() {
      // If this connection was not available we make it available
      // TODO: it would be better to check this in connection pool
      if (numStreams == maxStreams - 1) {
         pool.release(Http2Connection.this);
      }
   }

   private class HttpRequestWriterImpl implements HttpRequestWriter {
      private final Http2Headers headers;

      HttpRequestWriterImpl(Http2Headers headers) {
         this.headers = headers;
      }

      @Override
      public Connection connection() {
         return Http2Connection.this;
      }

      @Override
      public void putHeader(CharSequence header, CharSequence value) {
         headers.add(header, value);
      }
   }
}
