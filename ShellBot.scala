import java.util.concurrent.TimeUnit
import akka.actor._
import akka.util.Timeout
import akka.pattern.{pipe, ask}
import slack.ChannelService.ChannelId
import slack.SlackChatActor.{MessageReceived, SendMessage}
import slack.UserService.{UserName, UserId, All}
import slack._
import slack.SlackWebProtocol._

implicit val system = ActorSystem()
implicit val timeout = new Timeout(10, TimeUnit.SECONDS)
import system.dispatcher

val authorized = List("dave")

class Kernel extends Actor with ActorLogging {
  val slack = context.actorOf(Props[SlackChatActor], "slack")
  val ims = context.actorOf(Props[IMService], "ims")
  val users = context.actorOf(Props[UserService], "users")
  val throttle = context.actorOf(Props[Throttle], "throttle")

  throttle ! slack

  import util.ProcessActor2._

  def running(process:ActorRef, desiredUsername:String, desiredChannelId:String):Receive = { log.info("state -> running"); {
    case m @ MessageReceived(ChannelId(channelId), UserName(username), message, _)
      if desiredUsername == username && channelId == desiredChannelId =>

      process ! WriteLine(message)
    case StdOut(s) if s.trim.length > 0 => throttle ! SendMessage(desiredChannelId, s"`$s`")
    case StdErr(s) if s.trim.length > 0 => throttle ! SendMessage(desiredChannelId, s"_`$s`_")
    case Finished(returnCode) =>
      slack ! SendMessage(desiredChannelId, s"process exited with code: `$returnCode`")
      context.unbecome()
    case Status.Failure(ex) =>
      slack ! SendMessage(desiredChannelId, s"_process exited abnormally: `$ex`_")
      context.unbecome()
  }}

  def resolveUser:Receive = {
    case m @ MessageReceived(channelId, UserId(userId), message, _) if message.trim().length > 0 =>
      (users ? UserId(userId)).mapTo[All]
        .map { case All(_, name, _) => MessageReceived(channelId, UserName(name), message, None) }
        .pipeTo(self)
  }

  def connected(myUserId:String, myUserName:String):Receive = { log.info("state -> connected")
    val Mention = SlackChatActor.mentionPattern(myUserId)

    {
      case m @ MessageReceived(ChannelId(channelId), UserName(username), Mention(message), _) if authorized.contains(username) =>


        val process = context.actorOf(Props[util.ProcessActor2])
        context.become(running(process, username, channelId) orElse resolveUser, discardOld = false)
        process ! Run(message)
    }
  }

  def receive:Receive = { log.info("state -> disconnected"); {
    case RTMSelf(id, name) => context.become(connected(id, name) orElse resolveUser)
  }}
}

val kernel = system.actorOf(Props[Kernel], "kernel")