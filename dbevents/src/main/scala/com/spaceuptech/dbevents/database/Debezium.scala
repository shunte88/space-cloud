package com.spaceuptech.dbevents.database

import java.util.concurrent.Executors

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop, Signal}
import com.spaceuptech.dbevents.DatabaseSource
import com.spaceuptech.dbevents.spacecloud._
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt
import scala.sys.process._
import scala.util.{Failure, Success}

class Debezium(context: ActorContext[Database.Command], timers: TimerScheduler[Database.Command], projectId: String, broker: ActorRef[EventsSink.Command]) extends AbstractBehavior[Database.Command](context) {

  import Database._

  // Lets get the connection string first
  implicit val system: ActorSystem[Nothing] = context.system
  implicit val executionContext: ExecutionContextExecutor = system.executionContext
  private val executor = Executors.newSingleThreadExecutor

  // The status variables
  private var name: String = ""
  private var connString: String = ""
  private var status: Option[DebeziumStatus] = None
  private var source: DatabaseSource = _


  // Start task for status check
  timers.startTimerAtFixedRate(CheckEngineStatus(), 30.second)

  override def onMessage(msg: Command): Behavior[Command] = {
    // No need to handle any messages
    msg match {
      case CheckEngineStatus() =>
        status match {
          case Some(value) =>
            // Try starting the debezium engine again only if it wasn't running already
            if (value.future.isDone || value.future.isCancelled) {
              // Just making sure its closed first
              value.engine.close()
              value.future.cancel(true)

              println(s"Debezium engine $name is closed. Restarting...")
              status = Some(Utils.startDebeziumEngine(source, executor, context.self))
            }

          // No need to do anything if status isn't defined
          case None =>
        }

        this

      case UpdateEngineConfig(config) =>
        updateEngineConfig(config)
        this

      case ProcessEngineConfig(conn, config) =>
        processEngineConfig(conn, config)
        this

      case record: ChangeRecord =>
        broker ! EventsSink.EmitEvent(record)
        this

      case Stop() =>
        println(s"Got close command for debezium watcher - $name")
        Behaviors.stopped
    }
  }

  private def updateEngineConfig(config: DatabaseConfig): Unit = {
    getConnString(projectId, config.conn) onComplete {
      case Success(conn) =>
        context.self ! ProcessEngineConfig(conn, config)
      case Failure(ex) =>
        println(s"Unable to get connection string for debezium engine ($name) - ${ex.getMessage}")
    }
  }

  private def processEngineConfig(conn: String, config: DatabaseConfig): Unit = {
    println(s"Reloading db config for db '${config.dbAlias}'")

    // Simply return if there are no changes to the connection string
    if (conn == connString) return

    // Store the connection string for future reference
    this.connString = conn

    // Kill the previous debezium engine
    stopOperations()

    this.source = generateDatabaseSource(projectId, this.connString, config)
    this.name = Utils.generateConnectorName(this.source)

    println(s"Staring debezium engine ${this.name}")

    // Start the debezium engine
    this.status = Some(Utils.startDebeziumEngine(this.source, this.executor, context.self))
  }

  private def generateDatabaseSource(projectId: String, conn: String, db: DatabaseConfig): DatabaseSource = {
    // Generate ast from the database conn string
    val jsonString = s"conn-string-parser parse --db-type ${db.`type`} '$conn'".!!

    // Parse the ast into config object
    implicit val formats: DefaultFormats.type = org.json4s.DefaultFormats
    var config = parse(jsonString).extract[Map[String, String]]

    // Make necessary adjustments
    db.`type` match {
      case "mysql" => config += "db" -> db.name
      case "postgres" => config += "schema" -> db.name
      case _ =>
    }

    DatabaseSource(projectId, db.dbAlias, db.`type`, config)
  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case PostStop =>
      // Shutdown the timer
      timers.cancelAll()

      // Stop the engine
      stopOperations()

      // Shut down the main executor
      executor.shutdownNow()
      this
  }

  private def stopOperations(): Unit = {
    status match {
      case Some(value) =>
        // Shut down the debezium engine
        if (!value.future.isCancelled && !value.future.isDone) {
          println(s"Closing debezium engine - $name")
          value.engine.close()
          value.future.cancel(true)
          println(s"Closed debezium engine - $name")
        }

      // No need to do anything if status isn't defined
      case None =>
    }
  }
}
