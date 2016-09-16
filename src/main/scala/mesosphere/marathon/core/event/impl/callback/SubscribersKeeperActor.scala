package mesosphere.marathon.core.event.impl.callback

import akka.actor.{ Actor, ActorLogging }
import akka.pattern.pipe
import mesosphere.marathon.core.event.impl.callback.SubscribersKeeperActor._
import mesosphere.marathon.core.event.{ EventSubscribers, MarathonSubscriptionEvent, Subscribe, Unsubscribe }
import mesosphere.marathon.storage.repository.EventSubscribersRepository
import mesosphere.util.LockManager

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future }

class SubscribersKeeperActor(val store: EventSubscribersRepository) extends Actor with ActorLogging {
  private val lockManager = LockManager.create()
  private val LockName = "subscribers"
  override def receive: Receive = {

    case event @ Subscribe(_, callbackUrl, _, _) =>
      val addResult: Future[EventSubscribers] = add(callbackUrl)

      val subscription: Future[MarathonSubscriptionEvent] =
        addResult.map { subscribers =>
          if (subscribers.urls.contains(callbackUrl))
            log.info("Callback {} subscribed.", callbackUrl)
          event
        }(context.dispatcher)

      import context.dispatcher
      subscription pipeTo sender()

    case event @ Unsubscribe(_, callbackUrl, _, _) =>
      val removeResult: Future[EventSubscribers] = remove(callbackUrl)

      val subscription: Future[MarathonSubscriptionEvent] =
        removeResult.map { subscribers =>
          if (!subscribers.urls.contains(callbackUrl))
            log.info("Callback {} unsubscribed.", callbackUrl)
          event
        }(context.dispatcher)

      import context.dispatcher
      subscription pipeTo sender()

    case GetSubscribers =>
      val subscription = store.get().map(_.getOrElse(EventSubscribers()))(context.dispatcher)
      import context.dispatcher
      subscription pipeTo sender()
  }

  @SuppressWarnings(Array("all")) // async/await
  protected[this] def add(callbackUrl: String): Future[EventSubscribers] =
    lockManager.executeSequentially(LockName) {
      async { // linter:ignore UnnecessaryElseBranch
        val subscribers = await(store.get()).getOrElse(EventSubscribers())
        val updated = if (subscribers.urls.contains(callbackUrl)) {
          log.info("Existing callback {} resubscribed.", callbackUrl)
          subscribers
        } else EventSubscribers(subscribers.urls + callbackUrl)

        if (updated != subscribers) {
          await(store.store(updated))
        }
        updated
      }(ExecutionContext.global) // linter:ignore UnnecessaryElseBranch
    }(ExecutionContext.global) // blocks a thread, don't block the actor.

  @SuppressWarnings(Array("all")) // async/await
  protected[this] def remove(callbackUrl: String): Future[EventSubscribers] =
    lockManager.executeSequentially(LockName) {
      async { // linter:ignore UnnecessaryElseBranch
        val subscribers = await(store.get()).getOrElse(EventSubscribers())
        val updated = if (subscribers.urls.contains(callbackUrl)) {
          EventSubscribers(subscribers.urls - callbackUrl)
        } else {
          log.warning("Attempted to unsubscribe nonexistent callback {}", callbackUrl)
          subscribers
        }
        if (updated != subscribers) {
          await(store.store(updated))
        }
        updated
      }(ExecutionContext.global) // linter:ignore UnnecessaryElseBranch
    }(ExecutionContext.global) // blocks a thread, don't block the actor.
}

object SubscribersKeeperActor {

  case object GetSubscribers
}
