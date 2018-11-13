package org.ieee_passau.controllers

import java.util.Date

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import com.google.inject.Inject
import com.google.inject.name.Named
import javax.inject.Singleton
import org.ieee_passau.controllers.Beans._
import org.ieee_passau.evaluation.Messages._
import org.ieee_passau.models
import org.ieee_passau.models.DateSupport.dateMapper
import org.ieee_passau.models.Result.resultTypeMapper
import org.ieee_passau.models.{Admin, _}
import org.ieee_passau.utils.AkkaHelper
import org.ieee_passau.utils.FutureHelper.akkaTimeout
import org.ieee_passau.utils.ListHelper._
import play.api.Configuration
import play.api.data.Form
import play.api.data.Forms.{mapping, _}
import play.api.db.slick.DatabaseConfigProvider
import play.api.mvc._
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.xml.NodeSeq

@Singleton
class EvaluationController @Inject()(val dbConfigProvider: DatabaseConfigProvider,
                                     val components: MessagesControllerComponents,
                                     implicit val ec: ExecutionContext,
                                     val config: Configuration,
                                     val system: ActorSystem,
                                     @Named(AkkaHelper.monitoringActor) val monitoringActor: ActorRef,
                                     @Named(AkkaHelper.rankingActor) val rankingActor: ActorRef
                                    ) extends MasterController(dbConfigProvider, components, ec, config) {

  val pageSize: Int = config.getOptional[Int]("pagination.size").getOrElse(50)

  def index(page: Int, ordering: String): Action[AnyContent] = requirePermission(Moderator) { implicit admin => Action.async { implicit rs =>
    def sortDB(key: String, query: Query[((Rep[Int], Rep[String], Rep[String], Rep[Int], Rep[Int], Rep[Date], Rep[models.Result]), Rep[Int], Rep[Int]), ((Int, String, String, Int, Int, Date, models.Result), Int, Int), Seq]) = {
      key match {
        case "date" => query.sortBy(_._1._6.asc /*date*/)
        case "problem" => query.sortBy(q => (q._1._5 /*problem*/, q._1._3.toLowerCase /*user*/, q._1._2 /*language*/, q._1._6.asc /*date*/))
        case "user" => query.sortBy(q => (q._1._3.toLowerCase /*user*/, q._1._5 /*problem*/, q._1._2 /*language*/, q._1._6.asc /*date*/))
        case "lang" => query.sortBy(q => (q._1._2 /*language*/, q._1._3.toLowerCase /*user*/, q._1._5 /*problem*/, q._1._6.asc /*date*/))
      }
    }

    // order gets lost after materialization
    def sortList(key: String, list: List[SubmissionListEntry]) = {
      key match {
        case "date" => list.sortBy(_.date)(Ordering[Date].reverse)
        case "problem" => list.sortBy(e => (e.door, e.user.toLowerCase(), e.lang.toLowerCase(), e.date))
        case "user" => list.sortBy(e => (e.user.toLowerCase(), e.door, e.lang.toLowerCase(), e.date))
        case "lang" => list.sortBy(e => (e.lang.toLowerCase(), e.user.toLowerCase(), e.door, e.date))
      }
    }

    val solutionsQuery = sortDB(ordering, (for {
      s <- Solutions
      tr <- Testruns if tr.solutionId === s.id
      p <- Problems if p.id === s.problemId
      u <- Users if u.id === s.userId
    } yield (s.id, s.language, u.username, p.id, p.door, s.created, s.result, tr.result),
      ).groupBy(x => (x._1, x._2, x._3, x._4, x._5, x._6, x._7)).map { case (sol, tcs) =>
      (sol, tcs.length, tcs.map(y =>
        // trick the query builder, because a filter does not work here
        Case.If(y._8 === (Passed: models.Result)).Then(1).Else(0)).sum.getOrElse(0: Rep[Int]))
    }).drop((page - 1) * pageSize).take(pageSize).result

    ProblemTranslations.problemTitleListByLang(rs.lang).flatMap { transList =>
      db.run(solutionsQuery).flatMap { rawSolutions: Seq[((Int, String, String, Int, Int, Date, models.Result), Int, Int)] =>
        db.run(Solutions.length.result).map { numSolutions =>
          val solutions = sortList(ordering, rawSolutions.map {
            case ((sid, pLang, user, pid, door, created, result), allTestcases, solvedTestcases) =>
              SubmissionListEntry(sid, pLang, user, door, transList.getOrElse(pid, ""), created, solvedTestcases, allTestcases, result)
          }.toList)

          Ok(org.ieee_passau.views.html.solution.index(solutions, (numSolutions / pageSize) + 1, page, ordering))
        }
      }
    }
  }}

  def indexQueued: Action[AnyContent] = requirePermission(Admin) { implicit admin => Action.async { implicit rs =>
    val testrunsQuery = for {
      r <- Testruns if r.result === (Queued: models.Result) || r.stage.?.isDefined
      s <- r.solution
      c <- r.testcase
      p <- c.problem
      u <- s.user
    } yield (r, c, p, s, u)

    (monitoringActor ? RunningJobsQ).mapTo[List[(Job, Date)]].flatMap { jobs =>
      db.run(testrunsQuery.result).map { testruns =>
        val runningList = for {
          j <- jobs
          r <- testruns if r._1 /*testrun*/ .id.get == j._1 /*job*/ .testrunId
        } yield (r._1.id.get, r._4.id.get, r._2.position, r._1.stage.get, r._4.language, r._5.username, r._3.door, r._3.title, r._4.created, Some(j._2))
        val running = runningList.map(t => t._1)
        val list = runningList ++ (for {
          r <- testruns if !running.contains(r._1.id.get)
        } yield (r._1.id.get, r._4.id.get, r._2.position, r._1.stage.get, r._4.language, r._5.username, r._3.door, r._3.title, r._4.created, None))
        Ok(org.ieee_passau.views.html.monitoring.indexQueued(list.sortBy(_._9)(Ordering[Date]).sortBy(_._10)(Ordering[Option[Date]].reverse)))
      }
    }
  }}

  def details(id: Int): Action[AnyContent] = requirePermission(Moderator) { implicit admin => Action.async { implicit rs =>
    val solutionsQuery = for {
      s <- Solutions if s.id === id
      tr <- Testruns if tr.solutionId === s.id
      tc <- Testcases if tc.id === tr.testcaseId
    } yield (s, tc, tr)
    db.run(solutionsQuery.result).flatMap { solutionsList =>
      val sol = buildSolutionList(solutionsList).head
      val userQ = Users.filter(_.id === sol.solution.userId).result.head
      val problemQ = Problems.filter(_.id === sol.solution.problemId).result.head
      val languageQ = Languages.to[List].result
      db.run(userQ).flatMap { user =>
        db.run(problemQ).flatMap { problem =>
          db.run(languageQ).map { langs =>
            Ok(org.ieee_passau.views.html.solution.solutionDetail(sol, langs, user, problem))
          }
        }
      }
    }
  }}

  def vms: Action[AnyContent] = requirePermission(Admin){ implicit admin => Action.async { implicit rs =>
    Postings.byId(Page.status.id, rs.lang).map(_.content).flatMap { evalInfo =>
      (monitoringActor ? RunningVMsQ).mapTo[List[(String, Int, VMStatus)]] flatMap { list =>
        (monitoringActor ? StatusQ).mapTo[StatusM].map(running =>
          Ok(org.ieee_passau.views.html.monitoring.vms(running.run, evalInfo, list.sortBy(_._1))))
      }
    }
  }}

  def toggleEvalState: Action[AnyContent] = requirePermission(Admin) { implicit admin => Action { implicit rs =>
    statusForm.bindFromRequest.fold(
      _ => {
        Redirect(org.ieee_passau.controllers.routes.EvaluationController.vms())
          .flashing("warning" -> rs.messages("status.update.error"))
      },
      status => {
        monitoringActor ! StatusM(status)
        Redirect(org.ieee_passau.controllers.routes.EvaluationController.vms())
          .flashing("success" -> rs.messages("status.update.message"))
      }
    )
  }}

  def stats: Action[AnyContent] = requirePermission(Moderator) { implicit admin => Action.async { implicit rs =>
    (rankingActor ? StatsQ).mapTo[StatsM].map { message =>
      Ok(org.ieee_passau.views.html.monitoring.statistics(message))
    }
  }}

  def cancel(id: Int): Action[AnyContent] = requirePermission(Admin) { implicit admin => Action.async { implicit rs =>
    db.run(Testruns.byId(id).result.headOption) flatMap {
      case Some(job) =>
        Testruns.update(id, job.copy(result = Canceled, vm = Some("_"), evalId = None, completed = new Date, stage = None)) map { _ =>
          monitoringActor ! JobFinished(BaseJob(0, 0, "", id, job.evalId.getOrElse(""), "", "", "", ""))
          Redirect(org.ieee_passau.controllers.routes.EvaluationController.indexQueued())
            .flashing("success" -> rs.messages("jobs.control.cancel.message"))
        }
      case _ =>
        Future.successful(Redirect(org.ieee_passau.controllers.routes.EvaluationController.indexQueued())
          .flashing("warning" -> rs.messages("jobs.error.invalidjob")))
    }
  }}

  def reEval(id: Int): Action[AnyContent] = requirePermission(Admin) { implicit admin => Action.async { implicit rs =>
    db.run((for {
      r <- Testruns
      s <- r.solution if s.id === id
    } yield (r, s)).result) flatMap { testruns =>
      Future.reduceLeft(testruns.map { case (testrun, solution) =>
        Solutions.update(solution.id.get, solution.copy(score = 0, result = Queued))
        Testruns.update(testrun.id.get, testrun.copy(result = Queued, stage = Some(0), vm = None,
          progRuntime = Some(0), progMemory = Some(0), compRuntime = Some(0), compMemory = Some(0)))
      }.toList)((_, it) => it) map {_ =>
        Redirect(org.ieee_passau.controllers.routes.EvaluationController.index())
          .flashing("success" -> rs.messages("jobs.control.revaluate.message"))
      }
    }
  }}

  def registerVM: Action[NodeSeq] = requirePermission(Internal, parse.xml) { _ => Action[NodeSeq](parse.xml) { implicit rs =>

    /*
    <ieee-advent-calendar>
      <node>
        <identifier>pentagram-2</identifier>
        <host>localhost</host>
        <port>22</port>
        <utilization>
          <users>5</users>
          <cpu>6</cpu>
          <memory>
            <virtual>9.800000</virtual>
            <swap>0.000000</swap>
          </memory>
        </utilization>
      </node>
    </ieee-advent-calendar>
    */

    // Read configuration
    val m = rs.body \\ "node"
    val host = (m \ "host").text
    val port = (m \ "port").text.toInt
    val actorName = (m \ "identifier").text
    val uri =  host + ":" + port

    monitoringActor ? NewVM(Config(actorName, host, port))

    val numUser = (m \\ "users").text.toInt
    val load = (m \\ "cpu").text.toFloat
    val mem = (m \\ "virtual").text.toFloat
    val swap = (m \\ "swap").text.toFloat

    monitoringActor ! VMStatusM(VMStatus(actorName, uri, numUser, load, mem, swap, new Date()))

    Ok("")
  }}

  def removeVM(): Action[AnyContent] = requirePermission(Internal) { _ => Action { implicit rs =>
    val name = rs.body.asFormUrlEncoded.get("name").head
    monitoringActor ! RemoveVM(name)
    Ok("")
  }}

  val statusForm = Form(
    mapping(
      "state" -> text
    )((state: String) => state == "true")((status: Boolean) => Some(status.toString))
  )
}
