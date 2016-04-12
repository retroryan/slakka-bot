import java.util.concurrent.TimeUnit
import akka.actor._
import akka.util.Timeout
import slack.ChannelService.ChannelId
import slack.SlackChatActor.{SendMessage, MessageReceived}
import slack._
import slack.SlackWebProtocol._

implicit val system = ActorSystem()
implicit val timeout = new Timeout(10, TimeUnit.SECONDS)

class Kernel extends Actor with ActorLogging {
  implicit val ctx = context.dispatcher

  val slack = context.actorOf(Props[SlackChatActor], "slack")
  val reactions = SlackWebAPI.createPipeline[ReactionAdd]("reactions.add")

  def connected(myUserId:String, myUserName:String):Receive = { log.info("state -> connected")

    val r = new java.util.Random(new java.util.Date().getTime)

    {
      case MessageReceived(ChannelId(channelId), _, message, Some(ts)) if message.contains("dump") =>
        if (r.nextDouble() > 0.5)
          reactions(Map("channel" -> channelId, "timestamp" -> ts, "name" -> "hankey"))
        else
          slack ! SendMessage(channelId, s"hehe dump")
    }
  }

  def receive:Receive = { log.info("state -> disconnected"); {
    case RTMSelf(id, name) => context.become(connected(id, name))
  }}
}

val kernel = system.actorOf(Props[Kernel], "kernel")