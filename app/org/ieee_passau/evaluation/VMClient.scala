package org.ieee_passau.evaluation

import java.io.PrintStream
import java.net.Socket
import java.util.Base64

import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import org.ieee_passau.evaluation.Messages._
import org.ieee_passau.models._
import org.ieee_passau.utils.MathHelper
import org.ieee_passau.utils.StringHelper._

import scala.collection.immutable.StringOps
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.BufferedSource
import scala.language.postfixOps
import scala.xml.{Elem, Text}

object VMClient {
  def props(host: String,
            port: Int): Props =
    Props(new VMClient(host, port))
}

class VMClient(host: String, port: Int)
  extends EvaluationActor {

  private val timeout = Timeout(MathHelper.makeDuration(play.Configuration.root().getString("evaluator.inputregulator.joblifetime", "90 seconds")))

  private case class ExecutionResult(var duration: Duration = 0 seconds,
                                     var memory: Float = 0,
                                     var terminationResult: Option[String] = None,
                                     var stdout: Option[String] = None,
                                     var stderr: Option[String] = None,
                                     var exitCode: Option[Int] = None,
                                     var score: Option[Int] = None,
                                     var result: Option[Result] = None,
                                     var progErr: Option[String] = None)

  private val connection = context.actorOf(TCPActor.props(host, port))

  override def toString: String = {
    "VMClient@%s[host=%s]".format(Integer.toHexString(hashCode()), host)
  }

  //noinspection RedundantBlock
  private def runJob(job: Job): EvaluatedJob = {
    // Beans to store the result of compilation and evaluation
    val compilerResult: ExecutionResult = new ExecutionResult
    val evaluationResult: ExecutionResult = new ExecutionResult

    // Result:
    /*
    <ieee-advent-calendar>
      <run>
        <backendId>b6177f18-4ed7-453e-b1c1-d9679daac2a7</backendId>
        <successful>True</successful>
        <message></message>
        <outputs>
          <compilation>
            <returnCode>0</returnCode>
            <terminationReason>success</terminationReason>
            <streams>
              <stdOut>c3RkT3V0</stdOut>
              <stdErr>c3RkRXJy</stdErr>
            </streams>
          </compilation>
          <evaluation>
            <returnCode>0</returnCode>
            <terminationReason>success</terminationReason>
            <streams>
              <stdOut>c3RkT3V0</stdOut>
              <stdErr>c3RkRXJy</stdErr>
            </streams>
            <files>
              <file id="score">b64score</file>
              <file id="check">b64check</file>
            </files>
          </evaluation>
        </outputs>
        <utilization>
          <compilation>
            <cpu>0.0</cpu>
            <memory>0.0</memory>
            <runtime>0.0</runtime>
          </compilation>
          <evaluation>
            <cpu>0.0</cpu>
            <memory>0.0</memory>
            <runtime>0.0</runtime>
          </evaluation>
        </utilization>
      </run>
    </ieee-advent-calendar>
    */

    job match {
      case BaseJob(rid, l, program, programName, stdin, expout) => {
        val lang = Languages.byLang(l).get
        // Deploy Job
        val data =
          <ieee-advent-calendar>
            <job>
              <program>
                <filename>{programName}</filename>
                <content>{base64Encode(program)}</content>
                <language>{lang.id}</language>
              </program>
              <inputs>
                <streams>
                  {if (!stdin.isEmpty) {<stream id="stdin">{base64Encode(stdin)}</stream>}}
                </streams>
                <mask>{if (!stdin.isEmpty) {"{stdin}"}}</mask>
              </inputs>
              <outputs>
              </outputs>
            </job>
          </ieee-advent-calendar>

        val resultXml = Await.result((connection ? data) (timeout).mapTo[Elem], timeout.duration)
        log.debug("%s successfully deployed sourcecode for %s".format(this, job))

        // Compile result
        compilerResult.exitCode = if ((resultXml \\ "outputs" \ "compilation" \ "returnCode").text.isEmpty) None else Some((resultXml \\ "outputs" \ "compilation" \ "returnCode").text.toInt)
        compilerResult.stdout = Some(base64Decode((resultXml \\ "outputs" \ "compilation" \ "streams" \ "stdOut").text))
        compilerResult.stderr = Some(base64Decode((resultXml \\ "outputs" \ "compilation" \ "streams" \ "stdErr").text))
        compilerResult.duration = Duration((resultXml \\ "utilization" \ "compilation" \ "runtime").text.toFloat, MILLISECONDS)
        compilerResult.memory = (resultXml \\ "utilization" \ "compilation" \ "memory").text.toFloat
        compilerResult.terminationResult = Some((resultXml \\ "outputs" \ "compilation" \ "terminationReason").text)

        log.info("%s compiled sourcecode for %s with exit code %d in %d millis".format(this, job, compilerResult.exitCode.getOrElse(-1), compilerResult.duration.toMillis))

        // Evaluation result
        evaluationResult.exitCode = if ((resultXml \\ "outputs" \ "evaluation" \ "returnCode").text.isEmpty) None else Some((resultXml \\ "outputs" \ "evaluation" \ "returnCode").text.toInt)
        evaluationResult.stdout = Some(base64Decode((resultXml \\ "outputs" \ "evaluation" \ "streams" \ "stdOut").text))
        evaluationResult.stderr = Some(base64Decode((resultXml \\ "outputs" \ "evaluation" \ "streams" \ "stdErr").text))
        evaluationResult.duration = Duration((resultXml \\ "utilization" \ "evaluation" \ "runtime").text.toFloat, MILLISECONDS)
        evaluationResult.memory = (resultXml \\ "utilization" \ "evaluation" \ "memory").text.toFloat
        evaluationResult.terminationResult = Some((resultXml \\ "outputs" \ "evaluation" \ "terminationReason").text)

        log.info("%s evaluated sourcecode for %s with exit code %d in %d millis".format(this, job, evaluationResult.exitCode.getOrElse(-1), evaluationResult.duration.toMillis))

        val result = if (evaluationResult.terminationResult.contains("time")) {
          RuntimeExceeded
        } else if (evaluationResult.terminationResult.contains("memory"))  {
          MemoryExceeded
        } else if (!compilerResult.exitCode.contains(0)) {
          CompileError
        } else if (!evaluationResult.stderr.getOrElse("").trim.isEmpty || !evaluationResult.exitCode.contains(0)) {
          ProgramError
        } else {
          // Magic newline handling
          val outLines = evaluationResult.stdout.getOrElse("").split("(\\n|\\r\\n|\\r)")
          val expLines = job.expectedOut.split("(\\n|\\r\\n|\\r)")
          //noinspection CorrespondsUnsorted
          if (!outLines.sameElements(expLines)) {
            WrongAnswer
          } else {
            Passed
          }
        }
        evaluationResult.result = Some(result)

        evaluationResult.progErr = Some(if (result == MemoryExceeded) {
          "" // Don't show stderr if memory was exceeded

        } else if (result == ProgramError
          && evaluationResult.exitCode.contains(139)
          && evaluationResult.stderr.contains("Segmentation fault")) {
          "Segmentation fault" // Show simple message for segfault

        } else if (result == ProgramError
          && evaluationResult.exitCode.contains(134)
          && evaluationResult.stderr.contains("double free or corruption")) {
          new StringOps(evaluationResult.stderr.getOrElse("")).lines.next() // Show first line of error for memory corruption

        } else if (result == ProgramError
          && evaluationResult.exitCode.contains(134)
          && evaluationResult.stderr.contains("Aborted")
          && evaluationResult.stderr.contains("after throwing an instance of")) {
          new StringOps(evaluationResult.stderr.getOrElse("")).lines.next() // Show first line for CPP exceptions

        } else {
          evaluationResult.stderr.getOrElse("") // Show stderr by default
        })
      }

      case NextStageJob(rid, stage, program, stdin, progOut, expOut, cmd, input, outputStdoutCheck, outputScore, filename, file) => {
        val lang = Languages.byLang(if (filename.endsWith("jar")) {"JAVAJAR"} else {"BINARY"}).get
        // Deploy binary file
        val data =
          <ieee-advent-calendar>
            <job>
              <program>
                <filename>{filename}</filename>
                <content>{Base64.getEncoder.encodeToString(file)}</content>
                <language>{lang.id}</language>
              </program>
              <inputs>
                <streams>
                  {if (input._1) {<stream id="stdIn">{  base64Encode(stdin)  }</stream>}}
                  {if (input._2) {<stream id="progOut">{base64Encode(progOut)}</stream>}}
                  {if (input._3) {<stream id="expOut">{ base64Encode(expOut) }</stream>}}
                  {if (input._4) {<stream id="program">{base64Encode(program)}</stream>}}
                </streams>
                <mask>{cmd}</mask>
              </inputs>
              <outputs>
                {if (outputScore)       {<file id="score">{{homedir}}/score</file>}}
                {if (outputStdoutCheck) {<file id="check">{{homedir}}/check</file>}}
              </outputs>
            </job>
          </ieee-advent-calendar>

        val resultXml = Await.result((connection ? data) (timeout).mapTo[Elem], timeout.duration)
        log.debug("%s successfully deployed binary file for %s".format(this, job))

        // Evaluation result
        evaluationResult.exitCode = if ((resultXml \\ "outputs" \ "evaluation" \ "returnCode").text.isEmpty) None else Some((resultXml \\ "outputs" \ "evaluation" \ "returnCode").text.toInt)
        evaluationResult.stdout = Some(base64Decode((resultXml \\ "outputs" \ "evaluation" \ "streams" \ "stdOut").text))

        if (evaluationResult.stdout.get.isEmpty) evaluationResult.stdout = None
        evaluationResult.duration = Duration((resultXml \\ "utilization" \ "evaluation" \ "runtime").text.toFloat, MILLISECONDS)
        (resultXml \\ "outputs" \ "evaluation" \ "files" \ "file").foreach { tag => tag.attribute("id").getOrElse(new Text("")) match {
          case Text("score") => evaluationResult.score = Some(base64Decode(tag.text).toInt)
          case Text("check") =>
            val txt = base64Decode(tag.text)
            evaluationResult.result = Some(
              if (txt == "CORRECT") Passed
              else if (txt == "WRONG") WrongAnswer
              else ProgramError)
          case _ => // ignore
        }}

        log.info("%s evaluated task for %s with exit code %d in %d millis".format(this, job, evaluationResult.exitCode.getOrElse(-1), evaluationResult.duration.toMillis))
      }
    }

    EvaluatedJob(
      job = job,
      progOut = evaluationResult.stdout,
      progErr = evaluationResult.progErr,
      progExit = evaluationResult.exitCode,
      progRuntime = Some(evaluationResult.duration.toMillis / 1000.0),
      compOut = compilerResult.stdout,
      compErr = compilerResult.stderr,
      compExit = compilerResult.exitCode,
      compRuntime = Some(compilerResult.duration.toMillis / 1000.0),
      result = evaluationResult.result,
      score = evaluationResult.score
    )
  }

  override def receive: Receive = {
    case JobM(job) =>
      log.info("%s received %s".format(this, job))

      var eJob: EvaluatedJob = null
      try {
        eJob = runJob(job)
      } catch {
        case e: Exception =>
          log.error("%s encountered an exception while processing %s: %s".format(this, job, e), e)
          context.system.eventStream.publish(JobFailure(job))
          throw e
      }

      log.info("%s finished %s".format(this, job))

      context.actorSelection("../../DBWriter") ! EvaluatedJobM(eJob)
  }
}

object TCPActor {
  def props(host: String, port: Int): Props = Props(new TCPActor(host, port))
}

class TCPActor(host: String, port: Int) extends EvaluationActor {
  override def receive: Receive = {
    case data: xml.Elem =>
      val sock = new Socket(host, port)
      val os = new PrintStream(sock.getOutputStream)
      os.print(data)
      val is = new BufferedSource(sock.getInputStream)
      val result = xml.XML.loadString(is.iter.mkString)
      if ((result \ "backendStatus" \ "successful").text == "False") {
        log.info("Error in vm %s: %s", this, base64Decode((result \ "backendStatus" \ "message").text))
      }
      sender ! result
  }
}
