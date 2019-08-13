package slack

import SlackWebProtocol._
import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import spray.client.pipelining._
import spray.http.{FormData, HttpRequest}
import spray.httpx.SprayJsonSupport._
import spray.json.{JsObject, JsonReader}

import scala.concurrent.{ExecutionContext, Future}

/*
 * https://api.slack.com/web#basics
 * http://spray.io/documentation/1.2.3/spray-client/#usage
 */
object SlackWebAPI extends LazyLogging {
  case class Token(apiToken:String)
  def createPipeline[T](method:String)
                       (implicit ctx:ExecutionContext, system:ActorSystem, rdr:JsonReader[T], token:Token
                       ) : Map[String,String] => Future[T] = {

    val pipeline:HttpRequest => Future[JsObject] = sendReceive ~> unmarshal[JsObject]
    val url = s"https://slack.com/api/$method"

    {
      args:Map[String,String] =>

        val data = FormData(args ++ Map("token" -> token.apiToken))
        logger.info(s"requesting: $method from $url data: $data")
        val request:HttpRequest = Post(url, data)
        val response:Future[JsObject] = pipeline(request)

        /*
         * slack always returns a json object with a boolean 'ok' field
         *  - if false: they'll populate the 'error' field
         *  - if true: the object will have the data i want
         */

        response.flatMap(json => json.convertTo[Ok] match {
          case Ok(true, None) =>
            logger.info(s"$method create pipeline true and none")
            logger.info(s"json: $json")
            Future(json.convertTo[T])
          case Ok(false, Some(msg)) =>
            logger.info(s"$method create pipeline false and msg")
            Future.failed(new Exception(msg))
        })
    }
  }
}