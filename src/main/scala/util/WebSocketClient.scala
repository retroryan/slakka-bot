package util

import javax.websocket.MessageHandler.Whole

import spray.json.{JsValue, JsonParser}

//import javax.websocket.MessageHandler.Partial
import java.net.URI
import javax.websocket._

import akka.actor.{Actor, ActorLogging, ActorRef}
import org.glassfish.tyrus.client.ClientManager

object WebSocketClient {
  sealed trait Signal
  case class Received(message:JsValue) extends Signal
  //  case class ReceivedPartial(message:String, last:Boolean) extends Signal
  case class Disconnected() extends Signal
}

/*
 * http://stackoverflow.com/questions/26452903/javax-websocket-client-simple-example
 * https://tyrus.java.net/documentation/1.12/user-guide.html#d0e78
 */
class WebSocketClient extends Actor with ActorLogging {
  import WebSocketClient._

  def connect(server:URI, client:ActorRef) {
    val endpoint = new Endpoint {
      override def onOpen(session: Session, config: EndpointConfig) {

        log.info(s"config: $config")
        session.addMessageHandler(new Whole[String] {
          override def onMessage(message: String) {
            log.info(s"received message: $message")
            val parsed = JsonParser(message)
            client ! Received(parsed)
            client ! parsed
          }
        })
/* i don't think Slack does this */
//        session.addMessageHandler(new Partial[String] {
//          override def onMessage(message: String, last:Boolean) {
//            log.debug(s"received message ${if (last) "fragment" else "ending"}: $message")
//            client ! ReceivedPartial(message, last)
//          }
//        })
        context.become(connected(session))
      }
      override def onClose(session:Session, reason:CloseReason) = {
        log.warning(s"endpoint closed: $reason")
        context.become(disconnected)
        client ! Disconnected()
      }
      override def onError(session:Session, error:Throwable) = {
        log.error(s"error", error)
        throw error
      }
    }
    val config = ClientEndpointConfig.Builder.create().build()
    val c = ClientManager.createClient()
    c.connectToServer(endpoint, config, server)
  }

  def connected(session:Session):Receive = { log.info("state -> connected") ; {
    case message:JsValue =>
      log.debug(s"sending message: $message")
      session.getAsyncRemote.sendText(message.compactPrint)
  }}

  def disconnected:Receive = { log.info("state -> disconnected"); {
    case server:URI =>
      log.info(s"connecting to: $server")
      connect(server, context.parent)
  }}

  override def receive: Receive = disconnected
}