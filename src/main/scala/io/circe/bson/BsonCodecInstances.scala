package io.circe.bson

import cats.Traverse
import cats.syntax.traverse._
import cats.instances.either._
import cats.instances.vector._
import io.circe.Json.JObject
import io.circe.{ Json, JsonNumber, JsonObject }
import reactivemongo.api.bson._
import reactivemongo.api.bson.exceptions.HandlerException
import scala.util.{ Failure, Success }

trait BsonCodecInstances {
  private[this] def readerFailure(value: BSONValue): HandlerException =
    HandlerException(
      value.getClass.toString,
      new RuntimeException(s"Cannot convert $value: ${value.getClass} to io.circe.Json with io.circe.bson")
    )

  final def bsonToJson(bson: BSONValue): Either[Throwable, Json] = bson match {
    case BSONBoolean(value) => Right(Json.fromBoolean(value))
    case BSONString(value)  => Right(Json.fromString(value))
    case BSONDouble(value) =>
      Json.fromDouble(value) match {
        case Some(json) => Right(json)
        case None       => Left(readerFailure(bson))
      }
    case BSONLong(value)    => Right(Json.fromLong(value))
    case BSONInteger(value) => Right(Json.fromInt(value))
    case dec: BSONDecimal =>
      if (dec == BSONDecimal.NegativeZero) {
        Json.fromDouble(-0.0) match {
          case Some(json) => Right(json)
          case None       => Left(readerFailure(bson))
        }
      } else {
        BSONDecimal.toBigDecimal(dec) match {
          case Success(value) => Right(Json.fromBigDecimal(value))
          case Failure(error) => Left(error)
        }
      }
    case BSONArray(values) => values.map(bsonToJson).toVector.sequence.map(Json.fromValues)
    case BSONDocument(values) =>
      values.toVector.map { case BSONElement(key, value) =>
        bsonToJson(value).map(key -> _)
      }.sequence.map(Json.fromFields)
    case BSONDateTime(value)        => Right(Json.obj("$date" -> Json.fromLong(value)))
    case BSONTimestamp(value)       => Right(Json.fromLong(value))
    case BSONNull                   => Right(Json.Null)
    case BSONUndefined              => Right(Json.Null)
    case BSONSymbol(value)          => Right(Json.fromString(value))
    case BSONJavaScript(value)      => Right(Json.fromString(value))
    case BSONJavaScriptWS(key, doc) => bsonToJson(doc).map(j => Json.obj(key -> j))
    case BSONMaxKey                 => Left(readerFailure(bson))
    case BSONMinKey                 => Left(readerFailure(bson))
    case id: BSONObjectID =>
      BSONObjectID.parse(id.stringify) match {
        case Success(value) => Right(Json.fromString(value.stringify))
        case Failure(error) => Left(error)
      }
    case BSONBinary(_)   => Left(readerFailure(bson))
    case BSONRegex(_, _) => Left(readerFailure(bson))
  }

  private[this] lazy val jsonFolder: Json.Folder[Either[Throwable, BSONValue]] =
    new Json.Folder[Either[Throwable, BSONValue]] { self =>
      final val onNull: Either[Throwable, BSONValue] = Right(BSONNull)
      final def onBoolean(value: Boolean): Either[Throwable, BSONValue] = Right(BSONBoolean(value))
      final def onNumber(value: JsonNumber): Either[Throwable, BSONValue] = {
        val asDouble = value.toDouble

        if (java.lang.Double.compare(asDouble, -0.0) == 0) {
          Right(BSONDecimal.NegativeZero)
        } else
          value.toLong match {
            case Some(n) => Right(BSONLong(n))
            case None =>
              value.toBigDecimal match {
                case Some(n) =>
                  BSONDecimal.fromBigDecimal(n) match {
                    case Success(dec)   => Right(dec)
                    case Failure(error) => Left(error)
                  }
                case None =>
                  BSONDecimal.parse(value.toString) match {
                    case Success(dec)   => Right(dec)
                    case Failure(error) => Left(error)
                  }
              }
          }
      }
      final def onString(value: String): Either[Throwable, BSONValue] = Right(BSONString(value))
      final def onArray(value: Vector[Json]): Either[Throwable, BSONValue] =
        Traverse[Vector]
          .traverse(value) { json =>
            json.foldWith(self)
          }
          .map(BSONArray(_))
      final def onObject(value: JsonObject): Either[Throwable, BSONValue] =
        Traverse[Vector]
          .traverse(value.toVector) {
            case (key, json: JObject) if json.value.contains("$date") =>
              json.value("$date").flatMap(_.asNumber).flatMap(_.toLong).map(key -> BSONDateTime(_)) match {
                case Some(bdt) => Right(bdt)
                case None =>
                  Left(
                    new RuntimeException(
                      "Unable to convert JsonObject with $date to BSONDateTime, for key: %s".format(key)
                    )
                  )
              }
            case (key, json) => json.foldWith(self).map(key -> _)
          }
          .map(BSONDocument(_))
    }

  final def jsonToBson(json: Json): Either[Throwable, BSONValue] = json.foldWith(jsonFolder)

  implicit final lazy val jsonBsonReader: BSONReader[Json] = bsonToJson(_).toTry

  implicit final lazy val jsonBsonWriter: BSONWriter[Json] = jsonToBson(_).toTry
}
