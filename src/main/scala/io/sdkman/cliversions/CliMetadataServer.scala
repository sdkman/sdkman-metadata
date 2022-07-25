package io.sdkman.cliversions

import cats.effect.{Async, IO, IOApp, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.comcast.ip4s.Ipv4Address.fromString
import com.comcast.ip4s.Port.fromInt
import com.typesafe.config.ConfigFactory
import fs2.Stream
import mongo4cats.bson.Document
import mongo4cats.client.*
import mongo4cats.collection.MongoCollection
import mongo4cats.collection.operations.Filter
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.Logger

object CliMetadataServer extends CliMetadataConfig:

  def stream[F[_]: Async]: Stream[F, Nothing] = {
    for {
      client: MongoClient[F] <- Stream.resource(
        MongoClient.fromConnectionString[F](mongoConnectionString(mongoHost))
      )
      db <- Stream.eval(client.getDatabase(mongoDbName))
      versionsAlg    = VersionRegistry.impl[F](db)
      healthCheckAlg = HealthCheck.impl[F](db)

      httpApp = (
        AppRoutes.cliRoute[F](versionsAlg) <+>
          AppRoutes.healthCheckRoute[F](healthCheckAlg)
      ).orNotFound

      loggingHttpApp = Logger.httpApp(logHeaders = true, logBody = true)(httpApp)

      exitCode <- Stream.resource(
        EmberServerBuilder
          .default[F]
          .withHost(serverHost)
          .withPort(serverPort)
          .withHttpApp(loggingHttpApp)
          .build >>
          Resource.eval(Async[F].never)
      )
    } yield exitCode
  }.drain

trait CliMetadataConfig:

  private val config = ConfigFactory.load()

  val serverHost: Ipv4Address =
    Ipv4Address.fromString(config.getString("host")).getOrElse(ipv4"127.0.0.1")

  val serverPort: Port = Port.fromInt(config.getInt("port")).getOrElse(port"8080")

  private val Localhost = host"127.0.0.1"

  val mongoHost: Hostname =
    Hostname.fromString(config.getString("mongo.host")).getOrElse(Localhost)

  val mongoPort: Port = Port.fromInt(config.getInt("mongo.port")).getOrElse(port"27017")

  val mongoUsername: String = config.getString("mongo.credentials.username")

  val mongoPassword: String = config.getString("mongo.credentials.password")

  val mongoDbName: String = config.getString("mongo.database")

  def mongoConnectionString(host: Host): String =
    host match
      case Localhost =>
        s"mongodb://$mongoHost:$mongoPort/$mongoDbName"
      case _ =>
        s"mongodb://$mongoUsername:$mongoPassword@$mongoHost:$mongoPort/$mongoDbName?authMechanism=SCRAM-SHA-1"