package cn.regionfs.network

import java.io.InputStream
import java.nio.ByteBuffer
import java.util
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ArrayBlockingQueue, CountDownLatch}

import cn.regionfs.util.ByteBufferUtils._
import cn.regionfs.util.Profiler._
import cn.regionfs.util.{Logging, StreamUtils}
import io.netty.buffer.{ByteBuf, ByteBufInputStream, Unpooled}
import org.apache.spark.network.TransportContext
import org.apache.spark.network.buffer.{ManagedBuffer, NettyManagedBuffer}
import org.apache.spark.network.client._
import org.apache.spark.network.server.{NoOpRpcHandler, RpcHandler, StreamManager, TransportServer}
import org.apache.spark.network.util.{MapConfigProvider, TransportConf}
import org.apache.zookeeper.server.ByteBufferInputStream

import scala.collection.{JavaConversions, mutable}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * Created by bluejoe on 2020/2/17.
  */
trait StreamingConstants {
  val MARK_REQUEST_RAW_BUFFER: Byte = 1
  val MARK_REQUEST_MESSAGE: Byte = 2
  val MARK_REQUEST_OPEN_STREAM: Byte = 3
  val MARK_REQUEST_CLOSE_STREAM: Byte = 4

  val END_OF_STREAM = new Object()
}

trait ReceiveContext {
  def reply[T](response: T);
}


trait ChunkedStream {
  def hasNext(): Boolean;

  def writeNextChunk(buf: ByteBuf)

  def close(): Unit
}

trait ChunkedMessageStream[T] extends ChunkedStream {
  override def hasNext(): Boolean;

  def nextChunk(): Iterable[T]

  override def close(): Unit

  override def writeNextChunk(buf: ByteBuf) = {
    buf.writeObject(nextChunk())
  }
}

trait StreamingRpcHandler {
  def receive(request: Any, ctx: ReceiveContext): Unit;

  def receiveBuffer(request: ByteBuffer, ctx: ReceiveContext): Unit = {
    throw new UnsupportedOperationException();
  }

  def openStream(request: Any): ManagedBuffer = {
    throw new UnsupportedOperationException();
  }

  def openChunkedStream(request: Any): ChunkedStream = {
    throw new UnsupportedOperationException();
  }
}

object StreamingServer extends Logging with StreamingConstants {
  //WEIRLD: this makes next Upooled.buffer() call run fast
  Unpooled.buffer(1)

  def create(module: String, srh: StreamingRpcHandler, port: Int = -1, host: String = null): StreamingServer = {
    val configProvider = new MapConfigProvider(JavaConversions.mapAsJavaMap(Map()))
    val conf: TransportConf = new TransportConf(module, configProvider)
    val streamIdGen = new AtomicLong(System.currentTimeMillis());
    val streams = mutable.Map[Long, ChunkedStream]();

    val handler: RpcHandler = new RpcHandler() {
      //mark=message(0)
      //1: raw buffer
      //2: rpc message
      //3: open stream request
      //4: close stream request
      override def receive(client: TransportClient, message: ByteBuffer, callback: RpcResponseCallback) {
        try {
          val ctx = new ReceiveContext {
            override def reply[T](response: T) = {
              replyBuffer { x: ByteBuf =>
                x.writeObject(response)
              }
            }

            def replyBuffer(response: (ByteBuf) => Unit) = {
              val buf = Unpooled.buffer(1024);
              response(buf);
              callback.onSuccess(buf.nioBuffer())
            }
          };

          message.get() match {
            case MARK_REQUEST_RAW_BUFFER => {
              srh.receiveBuffer(message, ctx)
            }
            case MARK_REQUEST_MESSAGE => {
              srh.receive(message.readObject(), ctx)
            }
            case MARK_REQUEST_OPEN_STREAM => {
              val streamId: Long = streamIdGen.getAndIncrement();
              val stream = srh.openChunkedStream(message.readObject())

              ctx.replyBuffer((buf: ByteBuf) => {
                _writeNextChunk(buf, streamId, 0, stream)
              })

              if (stream.hasNext()) {
                streams(streamId) = stream
              }
            }
            case MARK_REQUEST_CLOSE_STREAM => {
              val streamId = message.getLong();
              streams(streamId).close
              streams -= streamId
            }
          }
        }
        catch {
          case e: Throwable => callback.onFailure(e)
        }
      }

      private def _writeNextChunk(buf: ByteBuf, streamId: Long, chunkIndex: Int, stream: ChunkedStream) {
        buf.writeLong(streamId).writeInt(0).writeByte(1)

        if (stream.hasNext()) {
          stream.writeNextChunk(buf)
        }

        if (!stream.hasNext()) {
          buf.setByte(8 + 4, 0)
          stream.close()
        }
      }

      val streamManager = new StreamManager() {
        override def getChunk(streamId: Long, chunkIndex: Int): ManagedBuffer = {
          if (logger.isTraceEnabled)
            logger.trace(s"get chunk: streamId=$streamId, chunkIndex=$chunkIndex")

          //1-2ms
          timing(false) {
            val buf = Unpooled.buffer(1024)
            val stream = streams(streamId)
            _writeNextChunk(buf, streamId, chunkIndex, stream)
            new NettyManagedBuffer(buf)
          }
        }

        override def openStream(streamId: String): ManagedBuffer = {
          val request = StreamUtils.deserializeObject(StreamUtils.base64.decode(streamId))
          srh.openStream(request);
        }
      }

      override def getStreamManager: StreamManager = streamManager
    }

    val context: TransportContext = new TransportContext(conf, handler)
    new StreamingServer(context.createServer(host, port, new util.ArrayList()))
  }
}

class StreamingServer(server: TransportServer) {
  def close() = server.close()
}

object StreamingClient extends Logging {
  //WEIRLD: this makes next Upooled.buffer() call run fast
  Unpooled.buffer(1)

  val clientFactoryMap = mutable.Map[String, TransportClientFactory]();
  val executionContext: ExecutionContext = ExecutionContext.global

  def getClientFactory(module: String) = {
    clientFactoryMap.getOrElseUpdate(module, {
      val configProvider = new MapConfigProvider(JavaConversions.mapAsJavaMap(Map()))
      val conf: TransportConf = new TransportConf(module, configProvider)
      val context: TransportContext = new TransportContext(conf, new NoOpRpcHandler())
      context.createClientFactory
    }
    )
  }

  def create(module: String, remoteHost: String, remotePort: Int): StreamingClient = {
    new StreamingClient(getClientFactory(module).createClient(remoteHost, remotePort))
  }
}

class StreamingClient(client: TransportClient) extends Logging with StreamingConstants {
  def close() = client.close()

  class MyRpcResponseCallback[T](consumeResponse: (ByteBuffer) => T) extends RpcResponseCallback {
    val latch = new CountDownLatch(1);

    var res: Any = null
    var err: Throwable = null

    override def onFailure(e: Throwable): Unit = {
      err = e
      latch.countDown();
    }

    override def onSuccess(response: ByteBuffer): Unit = {
      try {
        res = consumeResponse(response)
      }
      catch {
        case e: Throwable => err = e
      }

      latch.countDown();
    }

    def await(): T = {
      latch.await()
      if (err != null)
        throw err;

      res.asInstanceOf[T]
    }
  }

  private def _sendAndReceive[T](produceRequest: (ByteBuf) => Unit, consumeResponse: (ByteBuffer) => T)(implicit m: Manifest[T]): Future[T] = {
    val buf = Unpooled.buffer(1024)
    produceRequest(buf)
    val callback = new MyRpcResponseCallback[T](consumeResponse);
    client.sendRpc(buf.nioBuffer, callback)
    implicit val ec: ExecutionContext = StreamingClient.executionContext
    Future {
      callback.await()
    }
  }

  def send[T](produceRequest: (ByteBuf) => Unit)(implicit m: Manifest[T]): Future[T] = {
    _sendAndReceive({ buf =>
      buf.writeByte(MARK_REQUEST_RAW_BUFFER)
      produceRequest(buf)
    }, _.readObject[T]())
  }

  def ask[T](request: Any)(implicit m: Manifest[T]): Future[T] = {
    _sendAndReceive({ buf =>
      buf.writeByte(MARK_REQUEST_MESSAGE)
      buf.writeObject(request)
    }, _.readObject[T]())
  }

  def getInputStream(request: Any): InputStream = {
    _getInputStream(StreamUtils.base64.encodeAsString(
      StreamUtils.serializeObject(request)))
  }

  private def _getInputStream(streamId: String): InputStream = {
    val queue = new ArrayBlockingQueue[AnyRef](1);

    client.stream(streamId, new StreamCallback {
      override def onData(streamId: String, buf: ByteBuffer): Unit = {
        queue.put(Unpooled.copiedBuffer(buf));
      }

      override def onComplete(streamId: String): Unit = {
        queue.put(END_OF_STREAM)
      }

      override def onFailure(streamId: String, cause: Throwable): Unit = {
        throw cause;
      }
    })

    StreamUtils.concatChunks {
      val buffer = queue.take()
      if (buffer == END_OF_STREAM)
        None
      else {
        Some(new ByteBufInputStream(buffer.asInstanceOf[ByteBuf]))
      }
    }
  }

  case class ChunkResponse[T](streamId: Long, chunkIndex: Int, hasNext: Boolean, chunk: T) {

  }

  class MyChunkReceivedCallback[T](consumeResponse: (ByteBuffer) => T) extends ChunkReceivedCallback {
    val latch = new CountDownLatch(1);

    var res: ChunkResponse[T] = _
    var err: Throwable = null

    override def onFailure(chunkIndex: Int, e: Throwable): Unit = {
      err = e;
      latch.countDown();
    }

    override def onSuccess(chunkIndex: Int, buffer: ManagedBuffer): Unit = {
      try {
        val buf = buffer.nioByteBuffer()
        res = ChunkResponse[T](buf.getLong, buf.getInt, buf.get() != 0, consumeResponse(buf));
      }
      catch {
        case e:Throwable =>
          err = e;
      }

      latch.countDown();
    }

    def await(): ChunkResponse[T] = {
      latch.await()
      if (err != null)
        throw err;

      res
    }
  }

  def getChunkedInputStream(request: Any): InputStream = {
    //12ms
    val iter: Iterator[InputStream] = timing(false) {
      _getChunkedStream[InputStream](request, (buf: ByteBuffer) =>
        new ByteBufferInputStream(buf)).iterator
    }

    //1ms
    timing(false) {
      StreamUtils.concatChunks {
        if (iter.hasNext) {
          Some(iter.next)
        }
        else {
          None
        }
      }
    }
  }

  private def _buildStream[T](streamId: Long, chunkIndex: Int, consumeResponse: (ByteBuffer) => T): Stream[T] = {
    if (logger.isTraceEnabled)
      logger.trace(s"build stream: streamId=$streamId, chunkIndex=$chunkIndex")

    val callback = new MyChunkReceivedCallback[T](consumeResponse);
    val ChunkResponse(_, _, hasMoreChunks, t) = timing(false) {
      client.fetchChunk(streamId, chunkIndex, callback)
      callback.await()
    }

    Stream.cons(t,
      if (hasMoreChunks) {
        _buildStream(streamId, chunkIndex, consumeResponse)
      }
      else {
        Stream.empty
      })
  }

  private def _readChunkedStream[T](buf: ByteBuffer, consumeResponse: (ByteBuffer) => T): ChunkResponse[T] = {
    ChunkResponse(buf.getLong(),
      buf.getInt(),
      buf.get() != 0,
      consumeResponse(buf)
    )
  }

  def _getChunkedStream[T](request: Any, consumeResponse: (ByteBuffer) => T)(implicit m: Manifest[T]): Stream[T] = {
    //send start stream request
    //2ms
    val ChunkResponse(streamId, _, hasMoreChunks, chunk) =
      Await.result(_sendAndReceive[ChunkResponse[T]](
        (buf: ByteBuf) => {
          buf.writeByte(MARK_REQUEST_OPEN_STREAM)
          buf.writeObject(request)
        }, (buf: ByteBuffer) => {
          _readChunkedStream[T](buf, consumeResponse)
        }), Duration.Inf)

    Stream.cons(chunk,
      if (hasMoreChunks) {
        _buildStream(streamId, 1, consumeResponse)
      }
      else {
        Stream.empty
      })
  }
}