package org.ieee_passau.controllers

import java.util.Date

import akka.actor.Actor
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import org.ieee_passau.controllers.Beans._
import org.ieee_passau.evaluation.Messages.{JobFinished, NewVM, RemoveVM}
import org.ieee_passau.evaluation.{InputRegulator, VMMaster}
import org.ieee_passau.utils.AkkaHelper

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class MonitoringActor extends Actor {
  private var notification = false
  private var running = true
  implicit val timeout: Timeout = Timeout(5000 milliseconds)

  private val nodes = mutable.HashMap[String, VMStatus]()

  private val vmMaster = context.actorSelection(AkkaHelper.evalPath + classOf[VMMaster].getSimpleName)
  private val inputRegulator = context.actorSelection(AkkaHelper.evalPath + classOf[InputRegulator].getSimpleName)

  override def receive: Receive = {
    case NotificationQ => sender ! NotificationM(notification)
    case NotificationM(state) =>
      notification = state
    case NotificationT =>
      notification = !notification
      sender ! NotificationM(notification)
    case StatusQ => sender ! StatusM(running)
    case StatusM(state) =>
      running = state
      vmMaster ! StatusM(state)
      inputRegulator ! StatusM(state)
    case RunningJobsQ =>
      val source = sender
      pipe (inputRegulator ? RunningJobsQ) to source
    case RunningVMsQ =>
      val source = sender
      pipe ((vmMaster ? RunningVMsQ).flatMap {
        case list: List[(String, Int) @unchecked] => Future {
          list.map {
            vm: (String, Int) => (vm._1, vm._2, nodes.getOrElse(vm._1, VMStatus(vm._1, "", 0, 0, 0, 0, new Date)))
          }
        }
      }) to source
    case VMStatusM(state) => nodes += ((state.actorName, state))
    case NewVM(config) => vmMaster forward NewVM(config)
    case RemoveVM(name) => vmMaster forward RemoveVM(name)
    case JobFinished(job) => inputRegulator ! JobFinished(job)
  }
}
