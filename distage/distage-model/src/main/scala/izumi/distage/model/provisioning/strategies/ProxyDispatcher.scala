package izumi.distage.model.provisioning.strategies

import java.util.concurrent.atomic.AtomicReference

import izumi.distage.model.exceptions.{MissingRefException, ProxyAlreadyInitializedException}
import izumi.distage.model.reflection.universe.RuntimeDIUniverse.DIKey

trait ProxyDispatcher {
  def init(real: Any): Unit
}

object ProxyDispatcher {
  trait AtomicProxyDispatcher extends ProxyDispatcher {
    protected[this] val reference = new AtomicReference[AnyRef](null)

    override def init(real: Any): Unit = {
      if (!reference.compareAndSet(null, real.asInstanceOf[AnyRef])) {
        throw new ProxyAlreadyInitializedException(s"Proxy $this was already initialized with value: ${reference.get()}, but got new value: $real")
      }
    }
  }

  abstract class ByNameWrapper extends (() => Any)
  object ByNameWrapper {
    def apply(a: => Any): ByNameWrapper = () => a
  }

  final class ByNameDispatcher(val key: DIKey)
    extends ByNameWrapper
      with AtomicProxyDispatcher {
    override def apply(): Any = {
      Option(reference.get()) match {
        case Some(value) =>
          value
        case None =>
          throw new MissingRefException(s"By-Name proxy for $key is not yet initialized", Set(key), None)
      }
    }
  }
}
