package klite.jdbc

import klite.*
import java.lang.System.currentTimeMillis
import java.lang.Thread.currentThread
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Suppress("UNCHECKED_CAST")
class PooledDataSource(
  val db: DataSource = ConfigDataSource(),
  val maxSize: Int = Config.dbPoolMaxSize,
  val timeout: Duration = 5.seconds,
  val leakCheckThreshold: Duration? = null
): DataSource by db, AutoCloseable {
  private val log = logger()
  private val counter = AtomicInteger()
  private val size = AtomicInteger()
  val pool = ArrayBlockingQueue<PooledConnection>(maxSize)
  val used = ConcurrentHashMap<PooledConnection, Used>(maxSize)

  data class Used(val since: Long = currentTimeMillis(), val threadName: String = currentThread().name)

  private val leakChecker = leakCheckThreshold?.let {
    thread(name = "$this:leakChecker", isDaemon = true) {
      while (!Thread.interrupted()) {
        val now = currentTimeMillis()
        used.forEach { (conn, used) ->
          val usedFor = now - used.since
          if (usedFor >= it.inWholeMilliseconds)
            log.error("Possible leaked $conn, used for ${usedFor / 1000}s, acquired by ${used.threadName}")
        }
        try { Thread.sleep(it.inWholeMilliseconds / 10) } catch (e: InterruptedException) { break }
      }
    }
  }

  override fun getConnection(username: String?, password: String?) = throw UnsupportedOperationException("Use getConnection()")
  override fun getConnection(): PooledConnection {
    var conn: PooledConnection?
    do {
      conn = pool.poll() ?: size.incrementAndGet().let { newSize ->
        if (newSize <= maxSize) try {
          PooledConnection(db.connection).also { log.info("New connection $newSize/$maxSize: $it") }
        } catch (e: Exception) {
          size.decrementAndGet(); throw e
        } else {
          size.decrementAndGet()
          pool.poll(timeout.inWholeMilliseconds, MILLISECONDS) ?: throw SQLTimeoutException("No available connection after $timeout")
        }
      }
      try { conn.checkBySetApplicationName() } catch (e: Exception) {
        log.warn("Dropping failed $conn, age ${conn.ageMs / 1000}s: $e")
        size.decrementAndGet()
        conn = null
      }
    } while (conn == null)
    used[conn] = Used()
    return conn
  }

  override fun close() {
    leakChecker?.interrupt()
    pool.removeIf { it.reallyClose(); true }
    used.keys.removeIf { it.reallyClose(); true }
  }

  inner class PooledConnection(private val conn: Connection): Connection by conn {
    val count = counter.incrementAndGet()
    val since = currentTimeMillis()
    init {
      try { setNetworkTimeout(null, timeout.inWholeMilliseconds.toInt()) }
      catch (e: Exception) { log.warn("Failed to set network timeout for $this: $e") }
    }

    val ageMs get() = currentTimeMillis() - since

    internal fun checkBySetApplicationName() { applicationName = currentThread().name }

    override fun close() = try {
      used -= this
      if (!conn.autoCommit) conn.rollback()
      pool += this
    } catch (e: SQLException) {
      log.warn("Failed to return $this: $e")
    }

    internal fun reallyClose() = try {
      log.info("Closing $this, age ${ageMs / 1000}s")
      conn.close()
    } catch (e: SQLException) {
      log.warn("Failed to close $this: $e")
    }

    override fun toString() = "Pooled#${count}:$conn"

    override fun isWrapperFor(iface: Class<*>) = conn::class.java.isAssignableFrom(iface)
    override fun <T> unwrap(iface: Class<T>): T? = if (isWrapperFor(iface)) conn as T else null
  }

  override fun isWrapperFor(iface: Class<*>) = db::class.java.isAssignableFrom(iface)
  override fun <T> unwrap(iface: Class<T>): T? = if (isWrapperFor(iface)) db as T else null
}
