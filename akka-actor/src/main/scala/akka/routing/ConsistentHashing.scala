/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import scala.collection.immutable
import akka.actor.ActorContext
import akka.actor.Props
import akka.dispatch.Dispatchers
import com.typesafe.config.Config
import akka.actor.SupervisorStrategy
import akka.japi.Util.immutableSeq
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.ActorSystem
import java.util.concurrent.atomic.AtomicReference
import akka.actor.ActorRef
import akka.serialization.SerializationExtension
import scala.util.control.NonFatal
import akka.event.Logging
import akka.actor.ActorPath

object ConsistentHashingRouter {

  /**
   * If you don't define the `hashMapping` when
   * constructing the [[akka.routing.ConsistentHashingRouter]]
   * the messages need to implement this interface to define what
   * data to use for the consistent hash key. Note that it's not
   * the hash, but the data to be hashed.
   *
   * If returning an `Array[Byte]` or String it will be used as is,
   * otherwise the configured [[akka.serialization.Serializer]]
   * will be applied to the returned data.
   *
   * If messages can't implement this interface themselves,
   * it's possible to wrap the messages in
   * [[akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope]],
   * or use [[akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope]]
   */
  trait ConsistentHashable {
    def consistentHashKey: Any
  }

  /**
   * If you don't define the `hashMapping` when
   * constructing the [[akka.routing.ConsistentHashingRouter]]
   * and messages can't implement [[akka.routing.ConsistentHashingRouter.ConsistentHashable]]
   * themselves they can we wrapped by this envelope instead. The
   * router will only send the wrapped message to the destination,
   * i.e. the envelope will be stripped off.
   */
  @SerialVersionUID(1L)
  final case class ConsistentHashableEnvelope(message: Any, hashKey: Any)
    extends ConsistentHashable with RouterEnvelope {
    override def consistentHashKey: Any = hashKey
  }

  /**
   * Partial function from message to the data to
   * use for the consistent hash key. Note that it's not
   * the hash that is to be returned, but the data to be hashed.
   *
   * If returning an `Array[Byte]` or String it will be used as is,
   * otherwise the configured [[akka.serialization.Serializer]]
   * will be applied to the returned data.
   */
  type ConsistentHashMapping = PartialFunction[Any, Any]

  @SerialVersionUID(1L)
  object emptyConsistentHashMapping extends ConsistentHashMapping {
    def isDefinedAt(x: Any) = false
    def apply(x: Any) = throw new UnsupportedOperationException("Empty ConsistentHashMapping apply()")
  }

  /**
   * JAVA API
   * Mapping from message to the data to use for the consistent hash key.
   * Note that it's not the hash that is to be returned, but the data to be
   * hashed.
   *
   * May return `null` to indicate that the message is not handled by
   * this mapping.
   *
   * If returning an `Array[Byte]` or String it will be used as is,
   * otherwise the configured [[akka.serialization.Serializer]]
   * will be applied to the returned data.
   */
  trait ConsistentHashMapper {
    def hashKey(message: Any): Any
  }

  /**
   * INTERNAL API
   */
  private[akka] def hashMappingAdapter(mapper: ConsistentHashMapper): ConsistentHashMapping = {
    case message if (mapper.hashKey(message).asInstanceOf[AnyRef] ne null) ⇒
      mapper.hashKey(message)
  }

  /**
   * Creates a new ConsistentHashingRouter, routing to the specified routees
   */
  @deprecated("Use ConsistentHashingGroup", "2.3")
  def apply(routees: immutable.Iterable[ActorRef]): ConsistentHashingRouter =
    new ConsistentHashingRouter(routees = routees map (_.path.toString))

  /**
   * Java API to create router with the supplied 'routees' actors.
   */
  @deprecated("Use ConsistentHashingGroup", "2.3")
  def create(routees: java.lang.Iterable[ActorRef]): ConsistentHashingRouter = apply(immutableSeq(routees))

}

object ConsistentHashingRoutingLogic {
  /**
   * Address to use for the selfAddress parameter
   */
  def defaultAddress(system: ActorSystem): Address =
    system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
}

/**
 * Uses consistent hashing to select a routee based on the sent message.
 *
 * There is 3 ways to define what data to use for the consistent hash key.
 *
 * 1. You can define `hashMapping` / `withHashMapper`
 * of the router to map incoming messages to their consistent hash key.
 * This makes the decision transparent for the sender.
 *
 * 2. The messages may implement [[akka.routing.ConsistentHashingRouter.ConsistentHashable]].
 * The key is part of the message and it's convenient to define it together
 * with the message definition.
 *
 * 3. The messages can be be wrapped in a [[akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope]]
 * to define what data to use for the consistent hash key. The sender knows
 * the key to use.
 *
 * These ways to define the consistent hash key can be use together and at
 * the same time for one router. The `hashMapping` is tried first.
 *
 * @param virtualNodesFactor number of virtual nodes per node, used in [[akka.routing.ConsistentHash]]
 *
 * @param hashMapping partial function from message to the data to
 *   use for the consistent hash key
 *
 * @param system the actor system hosting this router
 *
 */
@SerialVersionUID(1L)
final case class ConsistentHashingRoutingLogic(
  system: ActorSystem,
  virtualNodesFactor: Int = 0,
  hashMapping: ConsistentHashingRouter.ConsistentHashMapping = ConsistentHashingRouter.emptyConsistentHashMapping)
  extends RoutingLogic {

  import ConsistentHashingRouter._

  /**
   * Java API
   * @param system the actor system hosting this router
   */
  def this(system: ActorSystem) =
    this(system, virtualNodesFactor = 0, hashMapping = ConsistentHashingRouter.emptyConsistentHashMapping)

  private val selfAddress = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
  val vnodes =
    if (virtualNodesFactor == 0) system.settings.DefaultVirtualNodesFactor
    else virtualNodesFactor

  private lazy val log = Logging(system, getClass)

  /**
   * Setting the number of virtual nodes per node, used in [[akka.routing.ConsistentHash]]
   */
  def withVirtualNodesFactor(vnodes: Int): ConsistentHashingRoutingLogic = copy(virtualNodesFactor = vnodes)

  /**
   * Java API: Setting the mapping from message to the data to use for the consistent hash key.
   */
  def withHashMapper(mapper: ConsistentHashingRouter.ConsistentHashMapper): ConsistentHashingRoutingLogic =
    copy(hashMapping = ConsistentHashingRouter.hashMappingAdapter(mapper))

  // tuple of routees and the ConsistentHash, updated together in updateConsistentHash
  private val consistentHashRef = new AtomicReference[(immutable.IndexedSeq[Routee], ConsistentHash[ConsistentRoutee])]((null, null))

  override def select(message: Any, routees: immutable.IndexedSeq[Routee]): Routee =
    if (routees.isEmpty) NoRoutee
    else {

      // update consistentHash when routees has changed
      // changes to routees are rare and when no changes this is a quick operation
      def updateConsistentHash(): ConsistentHash[ConsistentRoutee] = {
        val oldConsistentHashTuple = consistentHashRef.get
        val (oldRoutees, oldConsistentHash) = oldConsistentHashTuple

        if (routees ne oldRoutees) {
          // when other instance, same content, no need to re-hash, but try to set routees
          val consistentHash =
            if (routees == oldRoutees) oldConsistentHash
            else ConsistentHash(routees.map(ConsistentRoutee(_, selfAddress)), vnodes) // re-hash
          // ignore, don't update, in case of CAS failure
          consistentHashRef.compareAndSet(oldConsistentHashTuple, (routees, consistentHash))
          consistentHash
        } else oldConsistentHash
      }

      def target(hashData: Any): Routee = try {
        val currentConsistenHash = updateConsistentHash()
        if (currentConsistenHash.isEmpty) NoRoutee
        else hashData match {
          case bytes: Array[Byte] ⇒ currentConsistenHash.nodeFor(bytes).routee
          case str: String        ⇒ currentConsistenHash.nodeFor(str).routee
          case x: AnyRef          ⇒ currentConsistenHash.nodeFor(SerializationExtension(system).serialize(x).get).routee
        }
      } catch {
        case NonFatal(e) ⇒
          // serialization failed
          log.warning("Couldn't route message with consistent hash key [{}] due to [{}]", hashData, e.getMessage)
          NoRoutee
      }

      message match {
        case _ if hashMapping.isDefinedAt(message) ⇒ target(hashMapping(message))
        case hashable: ConsistentHashable          ⇒ target(hashable.consistentHashKey)
        case other ⇒
          log.warning("Message [{}] must be handled by hashMapping, or implement [{}] or be wrapped in [{}]",
            message.getClass.getName, classOf[ConsistentHashable].getName,
            classOf[ConsistentHashableEnvelope].getName)
          NoRoutee
      }
    }

}

/**
 * A router pool that uses consistent hashing to select a routee based on the
 * sent message. The selection is described in [[akka.routing.ConsistentHashingRoutingLogic]].
 *
 * The configuration parameter trumps the constructor arguments. This means that
 * if you provide `nrOfInstances` during instantiation they will be ignored if
 * the router is defined in the configuration file for the actor being used.
 *
 * <h1>Supervision Setup</h1>
 *
 * Any routees that are created by a router will be created as the router's children.
 * The router is therefore also the children's supervisor.
 *
 * The supervision strategy of the router actor can be configured with
 * [[#withSupervisorStrategy]]. If no strategy is provided, routers default to
 * a strategy of “always escalate”. This means that errors are passed up to the
 * router's supervisor for handling.
 *
 * The router's supervisor will treat the error as an error with the router itself.
 * Therefore a directive to stop or restart will cause the router itself to stop or
 * restart. The router, in turn, will cause its children to stop and restart.
 *
 * @param nrOfInstances initial number of routees in the pool
 *
 * @param resizer optional resizer that dynamically adjust the pool size
 *
 * @param virtualNodesFactor number of virtual nodes per node, used in [[akka.routing.ConsistentHash]]
 *
 * @param hashMapping partial function from message to the data to
 *   use for the consistent hash key
 *
 * @param supervisorStrategy strategy for supervising the routees, see 'Supervision Setup'
 *
 * @param routerDispatcher dispatcher to use for the router head actor, which handles
 *   supervision, death watch and router management messages
 */
@SerialVersionUID(1L)
final case class ConsistentHashingPool(
  override val nrOfInstances: Int, override val resizer: Option[Resizer] = None,
  val virtualNodesFactor: Int = 0,
  val hashMapping: ConsistentHashingRouter.ConsistentHashMapping = ConsistentHashingRouter.emptyConsistentHashMapping,
  override val supervisorStrategy: SupervisorStrategy = Pool.defaultSupervisorStrategy,
  override val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
  override val usePoolDispatcher: Boolean = false)
  extends Pool with PoolOverrideUnsetConfig[ConsistentHashingPool] {

  def this(config: Config) =
    this(
      nrOfInstances = config.getInt("nr-of-instances"),
      resizer = DefaultResizer.fromConfig(config),
      usePoolDispatcher = config.hasPath("pool-dispatcher"))

  /**
   * Java API
   * @param nr initial number of routees in the pool
   */
  def this(nr: Int) = this(nrOfInstances = nr)

  override def createRouter(system: ActorSystem): Router =
    new Router(ConsistentHashingRoutingLogic(system, virtualNodesFactor, hashMapping))

  /**
   * Setting the supervisor strategy to be used for the “head” Router actor.
   */
  def withSupervisorStrategy(strategy: SupervisorStrategy): ConsistentHashingPool = copy(supervisorStrategy = strategy)

  /**
   * Setting the resizer to be used.
   */
  def withResizer(resizer: Resizer): ConsistentHashingPool = copy(resizer = Some(resizer))

  /**
   * Setting the dispatcher to be used for the router head actor,  which handles
   * supervision, death watch and router management messages.
   */
  def withDispatcher(dispatcherId: String): ConsistentHashingPool = copy(routerDispatcher = dispatcherId)

  /**
   * Setting the number of virtual nodes per node, used in [[akka.routing.ConsistentHash]]
   */
  def withVirtualNodesFactor(vnodes: Int): ConsistentHashingPool = copy(virtualNodesFactor = vnodes)

  /**
   * Java API: Setting the mapping from message to the data to use for the consistent hash key.
   */
  def withHashMapper(mapper: ConsistentHashingRouter.ConsistentHashMapper): ConsistentHashingPool =
    copy(hashMapping = ConsistentHashingRouter.hashMappingAdapter(mapper))

  /**
   * Uses the resizer and/or the supervisor strategy of the given Routerconfig
   * if this RouterConfig doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   * Uses the the `hashMapping` defined in code, since that can't be defined in configuration.
   */
  override def withFallback(other: RouterConfig): RouterConfig = other match {
    case _: FromConfig | _: NoRouter          ⇒ this.overrideUnsetConfig(other)
    case otherRouter: ConsistentHashingPool   ⇒ (copy(hashMapping = otherRouter.hashMapping)).overrideUnsetConfig(other)
    case otherRouter: ConsistentHashingRouter ⇒ (copy(hashMapping = otherRouter.hashMapping)).overrideUnsetConfig(other)
    case _                                    ⇒ throw new IllegalArgumentException("Expected ConsistentHashingPool, got [%s]".format(other))
  }

}

/**
 * A router group that uses consistent hashing to select a routee based on the
 * sent message. The selection is described in [[akka.routing.ConsistentHashingRoutingLogic]].
 *
 * The configuration parameter trumps the constructor arguments. This means that
 * if you provide `paths` during instantiation they will be ignored if
 * the router is defined in the configuration file for the actor being used.
 *
 * @param paths string representation of the actor paths of the routees, messages are
 *   sent with [[akka.actor.ActorSelection]] to these paths
 *
 * @param virtualNodesFactor number of virtual nodes per node, used in [[akka.routing.ConsistentHash]]
 *
 * @param hashMapping partial function from message to the data to
 *   use for the consistent hash key
 *
 * @param routerDispatcher dispatcher to use for the router head actor, which handles
 *   router management messages
 */
@SerialVersionUID(1L)
final case class ConsistentHashingGroup(
  override val paths: immutable.Iterable[String],
  val virtualNodesFactor: Int = 0,
  val hashMapping: ConsistentHashingRouter.ConsistentHashMapping = ConsistentHashingRouter.emptyConsistentHashMapping,
  override val routerDispatcher: String = Dispatchers.DefaultDispatcherId)
  extends Group {

  def this(config: Config) =
    this(paths = immutableSeq(config.getStringList("routees.paths")))

  /**
   * Java API
   * @param routeePaths string representation of the actor paths of the routees, messages are
   *   sent with [[akka.actor.ActorSelection]] to these paths
   */
  def this(routeePaths: java.lang.Iterable[String]) = this(paths = immutableSeq(routeePaths))

  override def createRouter(system: ActorSystem): Router =
    new Router(ConsistentHashingRoutingLogic(system, virtualNodesFactor, hashMapping))

  /**
   * Setting the dispatcher to be used for the router head actor, which handles
   * router management messages
   */
  def withDispatcher(dispatcherId: String): ConsistentHashingGroup = copy(routerDispatcher = dispatcherId)

  /**
   * Setting the number of virtual nodes per node, used in [[akka.routing.ConsistentHash]]
   */
  def withVirtualNodesFactor(vnodes: Int): ConsistentHashingGroup = copy(virtualNodesFactor = vnodes)

  /**
   * Java API: Setting the mapping from message to the data to use for the consistent hash key.
   */
  def withHashMapper(mapper: ConsistentHashingRouter.ConsistentHashMapper): ConsistentHashingGroup =
    copy(hashMapping = ConsistentHashingRouter.hashMappingAdapter(mapper))

  /**
   * Uses the the `hashMapping` defined in code, since that can't be defined in configuration.
   */
  override def withFallback(other: RouterConfig): RouterConfig = other match {
    case _: FromConfig | _: NoRouter         ⇒ super.withFallback(other)
    case otherRouter: ConsistentHashingGroup ⇒ copy(hashMapping = otherRouter.hashMapping)
    case _                                   ⇒ throw new IllegalArgumentException("Expected ConsistentHashingGroup, got [%s]".format(other))
  }

}

/**
 * INTERNAL API
 * Important to use ActorRef with full address, with host and port, in the hash ring,
 * so that same ring is produced on different nodes.
 * The ConsistentHash uses toString of the ring nodes, and the ActorRef itself
 * isn't a good representation, because LocalActorRef doesn't include the
 * host and port.
 */
private[akka] final case class ConsistentRoutee(routee: Routee, selfAddress: Address) {

  override def toString: String = routee match {
    case ActorRefRoutee(ref)       ⇒ toStringWithfullAddress(ref.path)
    case ActorSelectionRoutee(sel) ⇒ toStringWithfullAddress(sel.anchorPath) + sel.pathString
    case other                     ⇒ other.toString
  }

  private def toStringWithfullAddress(path: ActorPath): String = {
    path.address match {
      case Address(_, _, None, None) ⇒ path.toStringWithAddress(selfAddress)
      case a                         ⇒ path.toString
    }
  }
}

/**
 * A Router that uses consistent hashing to select a connection based on the
 * sent message.
 *
 * There is 3 ways to define what data to use for the consistent hash key.
 *
 * 1. You can define `hashMapping` / `withHashMapper`
 * of the router to map incoming messages to their consistent hash key.
 * This makes the decision transparent for the sender.
 *
 * 2. The messages may implement [[akka.routing.ConsistentHashingRouter.ConsistentHashable]].
 * The key is part of the message and it's convenient to define it together
 * with the message definition.
 *
 * 3. The messages can be be wrapped in a [[akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope]]
 * to define what data to use for the consistent hash key. The sender knows
 * the key to use.
 *
 * These ways to define the consistent hash key can be use together and at
 * the same time for one router. The `hashMapping` is tried first.
 *
 * Please note that providing both 'nrOfInstances' and 'routees' does not make logical
 * sense as this means that the router should both create new actors and use the 'routees'
 * actor(s). In this case the 'nrOfInstances' will be ignored and the 'routees' will be used.
 * <br>
 * <b>The</b> configuration parameter trumps the constructor arguments. This means that
 * if you provide either 'nrOfInstances' or 'routees' during instantiation they will
 * be ignored if the router is defined in the configuration file for the actor being used.
 *
 * <h1>Supervision Setup</h1>
 *
 * Any routees that are created by a router will be created as the router's children.
 * The router is therefore also the children's supervisor.
 *
 * The supervision strategy of the router actor can be configured with
 * [[#withSupervisorStrategy]]. If no strategy is provided, routers default to
 * a strategy of “always escalate”. This means that errors are passed up to the
 * router's supervisor for handling.
 *
 * The router's supervisor will treat the error as an error with the router itself.
 * Therefore a directive to stop or restart will cause the router itself to stop or
 * restart. The router, in turn, will cause its children to stop and restart.
 *
 * @param routees string representation of the actor paths of the routees that will be looked up
 *   using `actorFor` in [[akka.actor.ActorRefProvider]]
 * @param virtualNodesFactor number of virtual nodes per node, used in [[akka.routing.ConsistentHash]]
 * @param hashMapping partial function from message to the data to
 *   use for the consistent hash key
 */
@SerialVersionUID(1L)
@deprecated("Use ConsistentHashingPool or ConsistentHashingGroup", "2.3")
final case class ConsistentHashingRouter(
  nrOfInstances: Int = 0, routees: immutable.Iterable[String] = Nil, override val resizer: Option[Resizer] = None,
  val routerDispatcher: String = Dispatchers.DefaultDispatcherId,
  val supervisorStrategy: SupervisorStrategy = Pool.defaultSupervisorStrategy,
  val virtualNodesFactor: Int = 0,
  val hashMapping: ConsistentHashingRouter.ConsistentHashMapping = ConsistentHashingRouter.emptyConsistentHashMapping)
  extends DeprecatedRouterConfig with PoolOverrideUnsetConfig[ConsistentHashingRouter] {

  /**
   * Java API: Constructor that sets nrOfInstances to be created.
   */
  def this(nr: Int) = this(nrOfInstances = nr)

  /**
   * Java API: Constructor that sets the routees to be used.
   *
   * @param routeePaths string representation of the actor paths of the routees that will be looked up
   *   using `actorFor` in [[akka.actor.ActorRefProvider]]
   */
  def this(routeePaths: java.lang.Iterable[String]) = this(routees = immutableSeq(routeePaths))

  /**
   * Java API: Constructor that sets the resizer to be used.
   */
  def this(resizer: Resizer) = this(resizer = Some(resizer))

  override def paths: immutable.Iterable[String] = routees

  /**
   * Java API for setting routerDispatcher
   */
  def withDispatcher(dispatcherId: String): ConsistentHashingRouter = copy(routerDispatcher = dispatcherId)

  /**
   * Java API for setting the supervisor strategy to be used for the “head”
   * Router actor.
   */
  def withSupervisorStrategy(strategy: SupervisorStrategy): ConsistentHashingRouter = copy(supervisorStrategy = strategy)

  /**
   * Java API for setting the resizer to be used.
   */
  def withResizer(resizer: Resizer): ConsistentHashingRouter = copy(resizer = Some(resizer))

  /**
   * Java API for setting the number of virtual nodes per node, used in [[akka.routing.ConsistentHash]]
   */
  def withVirtualNodesFactor(vnodes: Int): ConsistentHashingRouter = copy(virtualNodesFactor = vnodes)

  /**
   * Java API for setting the mapping from message to the data to use for the consistent hash key.
   */
  def withHashMapper(mapping: ConsistentHashingRouter.ConsistentHashMapper) =
    copy(hashMapping = ConsistentHashingRouter.hashMappingAdapter(mapping))

  /**
   * Uses the resizer and/or the supervisor strategy of the given Routerconfig
   * if this RouterConfig doesn't have one, i.e. the resizer defined in code is used if
   * resizer was not defined in config.
   * Uses the the `hashMapping` defined in code, since that can't be defined in configuration.
   */
  override def withFallback(other: RouterConfig): RouterConfig = other match {
    case _: FromConfig | _: NoRouter          ⇒ this.overrideUnsetConfig(other)
    case otherRouter: ConsistentHashingRouter ⇒ (copy(hashMapping = otherRouter.hashMapping)).overrideUnsetConfig(other)
    case _                                    ⇒ throw new IllegalArgumentException("Expected ConsistentHashingRouter, got [%s]".format(other))
  }

  override def createRouter(system: ActorSystem): Router =
    new Router(ConsistentHashingRoutingLogic(system, virtualNodesFactor, hashMapping))
}

/**
 * INTERNAL API
 * Important to use ActorRef with full address, with host and port, in the hash ring,
 * so that same ring is produced on different nodes.
 * The ConsistentHash uses toString of the ring nodes, and the ActorRef itself
 * isn't a good representation, because LocalActorRef doesn't include the
 * host and port.
 */
@deprecated("Replaced by ConsistentRoutee", "2.3")
private[akka] final case class ConsistentActorRef(actorRef: ActorRef, selfAddress: Address) {
  override def toString: String = {
    actorRef.path.address match {
      case Address(_, _, None, None) ⇒ actorRef.path.toStringWithAddress(selfAddress)
      case a                         ⇒ actorRef.path.toString
    }
  }
}