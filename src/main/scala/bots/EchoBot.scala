package bots

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import bots.SampleBots.kernel
import slack.ChannelService.{ChannelId, ChannelName}
import slack.IMService.IMOpened
import slack.SlackChatActor.{MessageReceived, SendMessage}
import slack.SlackWebProtocol.RTMSelf
import slack._
import slack.UserService.{UserId, UserName}

import scala.concurrent.duration._

object SampleBots extends App {

  implicit val system = ActorSystem()
  implicit val timeout = new Timeout(10, TimeUnit.SECONDS)

  implicit val token = SlackWebAPI.Token(sys.env("SLACK_TOKEN"))

  val kernel = system.actorOf(Props {
    new StandaloneBot()
  }, "kernel")

  import system.dispatcher

  system.scheduler.scheduleOnce(10 seconds, kernel, Chat(IM("retroryan"), "Hello from the Slackka AQUA BOT!"))
  system.scheduler.schedule(10 seconds, 60 seconds, kernel, Chat(Channel("aquatest"), """\echo Hello AquaBot"""))

}


sealed trait ChatType

case class IM(username: String) extends ChatType

case class Channel(name: String) extends ChatType

case class Chat(t: ChatType, message: String)

class StandaloneBot()(implicit t: SlackWebAPI.Token, to: Timeout) extends Actor with ActorLogging {

  private[this] implicit val ec = context.system.dispatcher

  private[this] val slack = context.actorOf(Props {
    new SlackChatActor()
  }, "slack")

  private[this] val ims = context.actorOf(Props {
    new IMService()
  }, "ims")
  private[this] val channels = context.actorOf(Props {
    new ChannelService()
  }, "channels")
  private[this] val users = context.actorOf(Props {
    new UserService()
  }, "users")


  def connected(myUserId: String, myUserName: String): Receive = {

    log.info("state -> connected")
    val Mention = SlackChatActor.mentionPattern(myUserId)

    {
      case Chat(IM(username), message) =>
        log.info(s"Chat: $username $message")
        (users ? UserName(username))
          .mapTo[UserService.All]
          .flatMap {
            case UserService.All(userId, _, _) =>
              log.info(s"UserService.All $userId")
              (ims ? IMService.OpenIM(userId)).mapTo[IMOpened]
          }
          .map {
            case IMOpened(_, channelId) =>
              log.info(s"IM Opened: $channelId")
              SendMessage(channelId, message)
          }
          .pipeTo(slack)
      case Chat(Channel(channelName), message) =>
        (channels ? ChannelName(channelName))
          .mapTo[ChannelService.All]
          .map { case ChannelService.All(channelId, _) => SendMessage(channelId, message) }
          .pipeTo(slack)
      case MessageReceived(ChannelId(channelId), _, Mention(message), _) if message.trim().length > 0 =>
        slack ! SendMessage(channelId, s"no, ${message.trim}")
      case MessageReceived(channel, UserId(userId), message, _) =>
        (users ? UserId(userId))
          .mapTo[UserService.All]
          .map { case UserService.All(_, userName, _) => MessageReceived(channel, UserName(userName), message, None) }
          .pipeTo(self)
    }
  }

  def receive: Receive = {
    log.info("state -> disconnected");
    {
      case RTMSelf(id, name) =>
        log.info(s"RTMSelf: $id $name")
        context.become(connected(id, name))
    }
  }
}

class EchoBot(target: ActorRef)(implicit t: SlackWebAPI.Token, to: Timeout) extends Actor with ActorLogging {
  def connected(myUserId: String, myUserName: String): Receive = {
    log.info("state -> connected")
    val Mention = SlackChatActor.mentionPattern(myUserId)

    {
      case MessageReceived(ChannelId(channelId), _, Mention(message), _) if message.trim().length > 0 =>
        target ! SendMessage(channelId, s"no, ${message.trim}")
    }
  }

  def receive: Receive = {
    log.info("state -> disconnected");
    {
      case RTMSelf(id, name) => context.become(connected(id, name))
    }
  }
}