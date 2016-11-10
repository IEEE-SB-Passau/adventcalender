package org.ieee_passau.controllers

import java.util.Date

import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout
import org.ieee_passau.controllers.Beans._
import org.ieee_passau.forms.{ProblemForms, UserForms}
import org.ieee_passau.models.DateSupport._
import org.ieee_passau.models._
import org.ieee_passau.utils.{ListHelper, PermissionCheck}
import play.api.Play.current
import play.api.db.slick.Config.driver.simple._
import play.api.db.slick._
import play.api.libs.Files.TemporaryFile
import play.api.libs.concurrent.Akka
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsPath, Json, Writes}
import play.api.mvc._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.io.File

object MainController extends Controller with PermissionCheck {

  val rankingActor: ActorRef = Akka.system.actorOf(Props[RankingActor], "RankingActor")
  implicit val timeout = Timeout(5000 milliseconds)

  val NoHighlight = 0
  val Highlight = 1
  val HighlightSpecial = 2

  def problems: Action[AnyContent] = Action.async { implicit rs =>
    implicit val sessionUser = getUserFromSession(request2session)
    val suid = if (sessionUser.isDefined) sessionUser.get.id.get else -1
    val unHide = sessionUser.isDefined && sessionUser.get.hidden
    val problems = (rankingActor ? ProblemsQ(suid, unHide)).mapTo[List[ProblemInfo]]
    problems.map(list => Ok(org.ieee_passau.views.html.general.problemList(list)))
  }

  def ranking: Action[AnyContent] = Action.async { implicit rs =>
    implicit val sessionUser = getUserFromSession(request2session)
    val suid = if (sessionUser.isDefined) sessionUser.get.id.get else -1
    val unHide = sessionUser.isDefined && sessionUser.get.hidden
    val ranking = (rankingActor ? RankingQ(suid, displayHiddenUsers = unHide)).mapTo[List[(Int, String, Boolean, Int, Int, Int)]]
    ranking.map(list => Ok(org.ieee_passau.views.html.general.ranking(list)))
  }

  def contact = Action { implicit rs =>
    implicit val sessionUser = getUserFromSession(request2session)
    Ok(org.ieee_passau.views.html.static.contact())
  }

  def faq = Action { implicit rs =>
    implicit val sessionUser = getUserFromSession(request2session)
    Ok(org.ieee_passau.views.html.static.faq())
  }

  def examples = Action { implicit rs =>
    implicit val sessionUser = getUserFromSession(request2session)
    Ok(org.ieee_passau.views.html.static.examples())
  }

  def rules = Action { implicit rs =>
    implicit val sessionUser = getUserFromSession(request2session)
    Ok(org.ieee_passau.views.html.static.rules())
  }

  def calendar = DBAction { implicit rs =>
    implicit val sessionUser = getUserFromSession(request2session)

    val now = new Date()
    val problems = Problems.filter(_.readableStart <= now).filter(_.readableStop > now).sortBy(_.door.asc).list
    val posting = Postings.byId(2).first

    // show all problems for debugging:
    //val problems = Query(Problems).sortBy(_.door.asc).list;

    Ok(org.ieee_passau.views.html.general.calendar(posting, problems))
  }

  def status = DBAction { implicit rs =>
    implicit val sessionUser = getUserFromSession(request2session)

    val posting = Postings.byId(1).first

    Ok(org.ieee_passau.views.html.general.status(posting))
  }

  /**
    * Submits the provided solution and queues the corresponding test-runs.
    *
    * @param door the number of the calendar door this task is behind
    */
  def solve(door: Int): Action[MultipartFormData[TemporaryFile]] = DBAction(parse.multipartFormData) { implicit rs =>
    implicit val sessionUser = getUserFromSession(request2session)
    val now = new Date()
    val problem = Problems.byDoor(door).first
    val lastSolutions = Solutions.filter(_.userId === sessionUser.get.id.get).sortBy(_.created.desc)
    val lastLocalSolution = lastSolutions.filter(_.problemId === problem.id).sortBy(_.created.desc).firstOption
    val sid: Int = if (lastLocalSolution.isEmpty) -1 else lastLocalSolution.get.id.get
    val trs = for {
      t <- Testruns if t.solutionId === sid && t.result === (Queued: org.ieee_passau.models.Result)
    } yield t.created

    val lang = rs.body.dataParts("lang").headOption.getOrElse("")

    if (sessionUser.isEmpty) {
      Unauthorized(org.ieee_passau.views.html.errors.e403())
    } else if (!problem.solvable) {
      Unauthorized(org.ieee_passau.views.html.errors.e403())
    } else if (lastSolutions.firstOption.nonEmpty && lastSolutions.first.created.after(new Date(now.getTime - 60000)) && !sessionUser.get.admin) {
      Redirect(org.ieee_passau.controllers.routes.MainController.problemDetails(door)).flashing("danger" ->
        "Du kannst momentan keine Lösung einreichen. Es kann nur eine Lösung pro Minute eingereicht werden!")
    } else if (trs.list.nonEmpty && trs.sortBy(_.desc).list.head.after(new Date(now.getTime - 900000)) && !sessionUser.get.admin) {
      Redirect(org.ieee_passau.controllers.routes.MainController.problemDetails(door)).flashing("danger" ->
        "Du kannst momentan keine Lösung einreichen. Solange deine letzte Lösung noch nicht ausgewertet wurde, kannst du nur alle 15 Minuten eine Lösung einreichen!")
    } else if (Languages.byLang(lang).isEmpty) {
      Redirect(org.ieee_passau.controllers.routes.MainController.problemDetails(door)).flashing("danger" ->
        "Lösung konnte nicht eingereicht werden, die angegebene Sprache wird nicht akzeptiert!")
    } else {

      rs.body.file("solution").map { submission =>
        val sourceFile = submission.ref.file
        if (sourceFile.length > 262144) {
          Redirect(org.ieee_passau.controllers.routes.MainController.problemDetails(door)).flashing("danger" -> "Die eingereichte Datei ist zu groß!")
        } else {
          // When using 2 proxys, the maximal possible remote-ip length with separators is 49 chars -> rounding up to 50
          val remoteAddress = Some(rs.remoteAddress.take(50))

          val userAgent = if (rs.headers.get("User-Agent").isDefined) {
            Some(rs.headers.get("User-Agent").get.take(150))
          } else {
            None
          }

          var success = false

          try {
            val solution = (Solutions returning Solutions.map(_.id)) +=
              Solution(None, sessionUser.get.id.get, Problems.byDoor(door).firstOption.get.id.get, lang,
                File(sourceFile).slurp, submission.filename, remoteAddress, userAgent, None, now)

            val id = Problems.byDoor(door).firstOption.get.id.get
            val q = for {
              t <- Testcases if t.problemId === id
            } yield t.id
            q.list.foreach(t =>
              Testruns += Testrun(None, solution, t, None, None, None, None, None, None, None, None, Queued, None, now, Some(0), None, now)
            )

            success = true
          } catch {
            case pokemon: Throwable => // ignore
          }
          if (success) Redirect(org.ieee_passau.controllers.routes.MainController.problemDetails(door).url + "#latest").flashing("success" -> "Lösung wurde eingereicht.")
          else Redirect(org.ieee_passau.controllers.routes.MainController.problemDetails(door)).flashing("danger" -> "Lösung konnte nicht eingereicht werden. Die Datei kann nicht als Programmtext gelesen werden!")
        }
      } getOrElse {
        Redirect(org.ieee_passau.controllers.routes.MainController.problemDetails(door)).flashing("danger" -> "Lösung konnte nicht eingereicht werden!")
      }
    }
  }

  /**
    * The "Fragen und Antworten" and "Loesung einreichen" sections of a problem.
    *
    * @param door the number of the calendar door this task is behind
    */
  def problemDetails(door: Int) = DBAction { implicit rs =>
    implicit val sessionUser = getUserFromSession(request2session)

    Problems.byDoor(door).firstOption match {
      case None => NotFound(org.ieee_passau.views.html.errors.e404())
      case Some(problem) =>
        if (!problem.readable) {
          Unauthorized(org.ieee_passau.views.html.errors.e403())
        } else {
          val userId = if (sessionUser.isDefined) sessionUser.get.id.get else -1
          val langs = Languages.sortBy(_.name).list
          val uid = sessionUser match {
            case None => -1
            case Some(u) => u.id.get
          }
          val uAdmin = sessionUser match {
            case None => false
            case Some(u) => u.admin
          }

          // unanswered tickets
          var tickets = (for {
            t <- Tickets if t.problemId === problem.id && t.responseTo.?.isEmpty && (t.public === true || t.userId === uid || uAdmin)
            u <- Users if u.id === t.userId
          } yield (t, u.username)).list

          // answered tickets + answers
          tickets = tickets ++ (for {
            pt <- Tickets if pt.problemId === problem.id && (pt.public === true || pt.userId === uid || uAdmin)
            t <- Tickets if t.responseTo === pt.id
            u <- Users if u.id === t.userId
          } yield (t, u.username)).list

          val solutions = buildSolutionList(problem, userId)

          val lastAllSolution = Solutions.filter(_.userId === userId).sortBy(_.created.asc).firstOption
          // default language shown in the language selector
          val lastLang =
            if (solutions.nonEmpty) solutions.sortBy(_.solution.created).last.solution.language
            else if (sessionUser.nonEmpty && lastAllSolution.nonEmpty) lastAllSolution.get.language
            else "JAVA"

          val running = Await.result(EvaluationController.monitoringActor ? StatusQ, 100 milli).asInstanceOf[StatusM]
          val flash = if (!running.run) "warning" -> ("Die Auswertung ist im Moment angehalten, bitte habe etwas Geduld, das Team kümmert sich darum!<br>" + running.message) else "" -> ""
          Ok(org.ieee_passau.views.html.general.problemDetails(problem, langs, lastLang, solutions, tickets, ProblemForms.ticketForm, flash))
        }
    }
  }

  def getUserProblemSolutions(door: Int): Action[AnyContent] = requireLogin { user => DBAction { implicit rs =>
    implicit val sessionUser = Some(user)
    Problems.byDoor(door).firstOption match {
      case None => NotFound(org.ieee_passau.views.html.errors.e404())
      case Some(problem) =>
        val langs = Languages.list

        val responseList = buildSolutionList(problem, user.id.get)
          .map(e => (e.solution.id.get, e.state.toString, org.ieee_passau.views.html.general.solutionList(List(e), langs).toString()))
          .map(e => SolutionJSON.tupled(e))
        val json = Json.toJson(responseList)
        Ok(json)
    }
  }}

  def feedback: Action[AnyContent] = requireLogin { user => Action { implicit rs =>
    implicit val sessionUser = Some(user)
    Ok(org.ieee_passau.views.html.general.feedback(UserForms.feedbackForm))
  }}

  def submitFeedback: Action[AnyContent] = requireLogin { user => DBAction { implicit rs =>
    implicit val sessionUser = Some(user)
    UserForms.feedbackForm.bindFromRequest.fold(
      errorForm => {
        BadRequest(org.ieee_passau.views.html.general.feedback(errorForm))
      },
      fb => {
        Feedbacks += Feedback(None, sessionUser.get.id.get, fb.rating, fb.pro, fb.con, fb.freetext)
        Redirect(org.ieee_passau.controllers.routes.MainController.calendar()).flashing("success" -> "Vielen Danke für deine Rückmeldung")
      }
    )
  }}

  def buildSolutionList(problem: Problem, userId: Int)(implicit session: scala.slick.jdbc.JdbcBackend#SessionDef): List[SolutionListEntry]= {
    // submitted solutions
    val solutionsQuery = for {
      c <- Testcases if c.problemId === problem.id                          if c.visibility =!= (Hidden: Visibility)
      s <- Solutions if s.problemId === problem.id && s.userId === userId
      r <- Testruns if c.id === r.testcaseId && s.id === r.solutionId
    } yield (s, c, r)

    ListHelper.buildSolutionList(solutionsQuery.list)
  }

  implicit val SolutionJSONWrites: Writes[SolutionJSON] = (
      (JsPath \ "id").write[Int] and
      (JsPath \ "result").write[String] and
      (JsPath \ "html").write[String]
    ) (unlift(SolutionJSON.unapply))
}
