package slack

import akka.actor.{Actor, ActorLogging, ActorRef, Status}
import slack.SlackWebProtocol.{User, UserList}
import akka.pattern.pipe

import scala.collection.mutable
import scala.concurrent.Future

object UserService {
  sealed trait UserIdentifier
  sealed trait PartialUserIdentifier extends UserIdentifier
  case class UserName(name:String) extends PartialUserIdentifier
  case class UserId(id:String) extends PartialUserIdentifier
  case class All(id:String, name:String, email:Option[String]) extends UserIdentifier
}

class UserService()(implicit t:SlackWebAPI.Token) extends Actor with ActorLogging {
  import UserService._
  implicit val ctx = context.dispatcher
  implicit val sys = context.system

  val users: Map[String, String] => Future[UserList] = SlackWebAPI.createPipeline[UserList]("users.list")

  users(Map()).pipeTo(self)

  def process(partialUserID:PartialUserIdentifier, userList:List[User], requester:ActorRef):Unit = {
    val result =
      (partialUserID  match {
        case UserName(name) =>
          log.info(s"matching username: $name")
          val maybeUser = userList.find(_.name == name)
          log.info(s"maybe user: $maybeUser")
          maybeUser
        case UserId(id) =>
          log.info(s"matching user id")
          val maybeUser = userList.find(_.id == id)
          log.info(s"maybe user: $maybeUser")
          maybeUser
      }) match {
        case Some(user) => All(user.id, user.name, user.profile.email)
        case None => Status.Failure(new Exception(s"couldn't find user given evidence: $partialUserID"))
      }

    log.info(s"mapped: $partialUserID -> $result")

    requester ! result
  }

  def processing(l:List[User]):Receive = { log.info("state -> processing"); {
    case partialUserID:PartialUserIdentifier =>
      log.info(s"Processing partial user id")
      process(partialUserID, l, sender())
  }}

  def queueing:Receive = { log.info("state -> queueing")
    val queue = mutable.Queue[(ActorRef, PartialUserIdentifier)]()

    {
      case partialUserID:PartialUserIdentifier => queue.enqueue((sender(), partialUserID))
      case UserList(userList) =>
        log.info(s"Received user list: $userList")
        queue.foreach {
          case (requester, name) =>
            process(name, userList, requester)
        }
        context.become(processing(userList))
    }
  }

  def receive = queueing
}