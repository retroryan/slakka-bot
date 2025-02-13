package slack

import java.net.URI

import akka.actor._
import akka.pattern.pipe
import akka.actor.SupervisorStrategy.Stop
import spray.json.{CollectionFormats, DefaultJsonProtocol, JsValue, RootJsonFormat}
import slack.ChannelService.{ChannelId, ChannelIdentifier}
import slack.SlackWebProtocol.{RTMSelf, RTMStart}
import slack.UserService.{UserId, UserIdentifier}
import util.WebSocketClient
import util.WebSocketClient.{Disconnected, Received}

import scala.util.matching.Regex

object SlackRTProtocol extends DefaultJsonProtocol with CollectionFormats {
  case class Base(`type`:Option[String])
  implicit val baseFormat: RootJsonFormat[Base] = jsonFormat1(Base)

  case class Message(`type`:String, channel:String, text:Option[String], user:Option[String], ts:Option[String])
  implicit val messageFormat: RootJsonFormat[Message] = jsonFormat5(Message)
}

object SlackChatActor {
  case class SendMessage(channel:String, message:String)
  case class MessageReceived(channel:ChannelIdentifier, from:UserIdentifier, message:String, ts:Option[String])
  def mentionPattern(userId:String): Regex = s""".*[<][@]$userId[>][: ]+(.+)""".r
  def mention(userId:String) = s"<@$userId>"
  case class Start(target:ActorRef)
}

object MessageMatcher {
  import SlackRTProtocol._
  import SlackChatActor._
  def unapply(json:JsValue):Option[MessageReceived] = {
    json.convertTo[Base] match {
      case Base(Some("message")) =>
        json.convertTo[Message] match {
          case Message(_, c, Some(msg), Some(u), t) => Some(MessageReceived(ChannelId(c), UserId(u), msg, t))
          case _ => None
        }
      case _ => None
    }
  }
}

class SlackChatActor(autostart:Boolean = true)(implicit t:SlackWebAPI.Token) extends Actor with ActorLogging {
  private[this] implicit val sys = context.system
  private[this] implicit val ec = sys.dispatcher
  import SlackChatActor._
  import SlackRTProtocol._

  override val supervisorStrategy: SupervisorStrategy = OneForOneStrategy() { case _ => Stop }

  def connected(slackClient:ActorRef, target:ActorRef):Receive = { log.info("state -> connected"); {
    case Received(MessageMatcher(m)) => target ! m
    case Disconnected() => context.become(disconnected(target))
    case SendMessage(c, m) => slackClient ! Message("message", c, Some(m), None, None).toJson
    case Terminated(who) =>
      log.warning(s"slack client disconnected: $who")
      context.become(disconnected(target))
  }}

  def disconnected(target:ActorRef):Receive = { log.info("state -> disconnected")
    log.info(s"connecting web socket to slack")
    val rtmStart = SlackWebAPI.createPipeline[RTMStart]("rtm.start")
    rtmStart(Map()).pipeTo(self)

    {
      case RTMStart(url, RTMSelf(id, name)) =>
        log.info(s"RTMStart $url $id $name")
        val slackClient = context.actorOf(Props[WebSocketClient], "wsclient")
        slackClient ! new URI(url)
        target ! RTMSelf(id, name)
        context.watch(slackClient)
        context.become(connected(slackClient, target))
      case Status.Failure(ex) => throw new Exception("error starting up - have you specified your slack token?", ex)
    }
  }

  def waiting:Receive = { log.info("state -> waiting");
    {
      case Start(target) => context.become(disconnected(target))
    }
  }

  def receive:Receive = if (autostart) disconnected(context.parent) else waiting
}