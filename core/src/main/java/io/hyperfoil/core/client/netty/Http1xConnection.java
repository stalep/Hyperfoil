package io.hyperfoil.core.client.netty;

import io.hyperfoil.api.connection.HttpRequest;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpVersion;
import io.hyperfoil.api.connection.Connection;
import io.hyperfoil.api.connection.HttpConnection;
import io.hyperfoil.api.connection.HttpConnectionPool;
import io.hyperfoil.api.connection.HttpRequestWriter;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.hyperfoil.api.http.HttpResponseHandlers;
import io.hyperfoil.api.session.Session;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
class Http1xConnection extends ChannelDuplexHandler implements HttpConnection {
   private static Logger log = LoggerFactory.getLogger(Http1xConnection.class);
   private static boolean trace = log.isTraceEnabled();

   private final HttpConnectionPool pool;
   private final Deque<HttpRequest> inflights;
   private final BiConsumer<HttpConnection, Throwable> activationHandler;
   ChannelHandlerContext ctx;
   // we can safely use non-atomic variables since the connection should be always accessed by single thread
   private int size;
   private boolean activated;
   private boolean closed;

   Http1xConnection(HttpClientPoolImpl client, HttpConnectionPool pool, BiConsumer<HttpConnection, Throwable> handler) {
      this.pool = pool;
      this.activationHandler = handler;
      this.inflights = new ArrayDeque<>(client.http.pipeliningLimit());
   }

   @Override
   public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      this.ctx = ctx;
      if (ctx.channel().isActive()) {
         checkActivated(ctx);
      }
   }

   @Override
   public void channelActive(ChannelHandlerContext ctx) throws Exception {
      super.channelActive(ctx);
      checkActivated(ctx);
   }

   private void checkActivated(ChannelHandlerContext ctx) {
      if (!activated) {
         activated = true;
         activationHandler.accept(this, null);
      }
   }

   @Override
   public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof HttpResponse) {
         HttpResponse response = (HttpResponse) msg;
         HttpRequest request = inflights.peek();
         HttpResponseHandlers handlers = (HttpResponseHandlers) request.handlers();
         try {
            handlers.handleStatus(request, response.status().code(), response.status().reasonPhrase());
            for (Map.Entry<String, String> header : response.headers()) {
               handlers.handleHeader(request, header.getKey(), header.getValue());
            }
         } catch (Throwable t) {
            log.error("Response processing failed on {}", t, this);
            handlers.handleThrowable(request, t);
         }
      }
      if (msg instanceof HttpContent) {
         HttpRequest request = inflights.peek();
         HttpResponseHandlers handlers = (HttpResponseHandlers) request.handlers();
         try {
            handlers.handleBodyPart(request, ((HttpContent) msg).content());
         } catch (Throwable t) {
            log.error("Response processing failed on {}", t, this);
            request.session.fail(t);
         }
      }
      if (msg instanceof LastHttpContent) {
         size--;
         HttpRequest request = inflights.poll();
         try {
            request.handlers().handleEnd(request);
            if (trace) {
               log.trace("Completed response on {}", this);
            }
         } catch (Throwable t) {
            log.error("Response processing failed on {}", t, this);
            request.handlers().handleThrowable(request, t);
         }

         // If this connection was not available we make it available
         // TODO: it would be better to check this in connection pool
         if (size == pool.clientPool().config().pipeliningLimit() - 1) {
            pool.release(this);
         }
         pool.pulse();
      }
   }

   @Override
   public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      log.warn("Exception in {}", cause, this);
      cancelRequests(cause);
      ctx.close();
   }

   @Override
   public void channelInactive(ChannelHandlerContext ctx) {
      cancelRequests(Connection.CLOSED_EXCEPTION);
   }

   private void cancelRequests(Throwable cause) {
      HttpRequest request;
      while ((request = inflights.poll()) != null) {
         if (!request.isCompleted()) {
            request.handlers().handleThrowable(request, cause);
            request.session.proceed();
         }
      }
   }

   @Override
   public void request(HttpRequest request, BiConsumer<Session, HttpRequestWriter>[] headerAppenders, BiFunction<Session, Connection, ByteBuf> bodyGenerator) {
      size++;
      ByteBuf buf = bodyGenerator != null ? bodyGenerator.apply(request.session, request.connection()) : null;
      if (buf == null) {
         buf = Unpooled.EMPTY_BUFFER;
      }
      DefaultFullHttpRequest msg = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, request.method.netty, request.path, buf, false);
      msg.headers().add(HttpHeaderNames.HOST, pool.clientPool().authority());
      if (buf.readableBytes() > 0) {
         msg.headers().add(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(buf.readableBytes()));
      }
      if (headerAppenders != null) {
         // TODO: allocation, if it's not eliminated we could store a reusable object
         HttpRequestWriter writer = new HttpRequestWriterImpl(msg);
         for (BiConsumer<Session, HttpRequestWriter> headerAppender : headerAppenders) {
            headerAppender.accept(request.session, writer);
         }
      }
      assert ctx.executor().inEventLoop();
      inflights.add(request);
      ChannelPromise writePromise = ctx.newPromise();
      writePromise.addListener(request);
      ctx.writeAndFlush(msg, writePromise);
   }

   @Override
   public HttpRequest peekRequest(int streamId) {
      assert streamId == 0;
      return inflights.peek();
   }

   @Override
   public void setClosed() {
      this.closed = true;
   }

   @Override
   public boolean isClosed() {
      return closed;
   }

   @Override
   public ChannelHandlerContext context() {
      return ctx;
   }

   @Override
   public boolean isAvailable() {
      return size < pool.clientPool().config().pipeliningLimit();
   }

   @Override
   public int inFlight() {
      return size;
   }

   @Override
   public void close() {
      // We need to cancel requests manually before sending the FIN packet, otherwise the server
      // could give us an unexpected response before closing the connection with RST packet.
      cancelRequests(Connection.SELF_CLOSED_EXCEPTION);
      ctx.close();
   }

   @Override
   public String host() {
      return pool.clientPool().host();
   }

   @Override
   public String toString() {
      return "Http1xConnection{" +
            ctx.channel().localAddress() + " -> " + ctx.channel().remoteAddress() +
            ", size=" + size +
            '}';
   }

   private class HttpRequestWriterImpl implements HttpRequestWriter {
      private final DefaultFullHttpRequest msg;

      HttpRequestWriterImpl(DefaultFullHttpRequest msg) {
         this.msg = msg;
      }

      @Override
      public Connection connection() {
         return Http1xConnection.this;
      }

      @Override
      public void putHeader(CharSequence header, CharSequence value) {
         msg.headers().add(header, value);
      }
   }
}
