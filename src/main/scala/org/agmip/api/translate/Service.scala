package org.agmip.api.translate

import java.util.UUID

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import spray.json.DefaultJsonProtocol._

import scala.io.StdIn

import scala.concurrent.Future


object Service {
  implicit val system = ActorSystem("translate-api")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  final case class Capabilities(input: List[String], output: List[String])
  final case class Job(id: String, name: Option[String], input: String, output: String, status: String)
  final case class JobSubmission(name: Option[String], input: String, output: String)
  final case class JobId(id: String)
  final case class Err(error: String)
  implicit val capabilitiesFormat = jsonFormat2(Capabilities)
  implicit val jobSubmissionFormat = jsonFormat3(JobSubmission)
  implicit val jobIdFormat = jsonFormat1(JobId)
  implicit val errFormat = jsonFormat1(Err)
  implicit val jobFormat = jsonFormat5(Job)

  val supportedCaps = Capabilities("ACEB" :: Nil, "DSSAT" :: Nil)
  var jobList: List[Job] = Nil

  def fetchCapabilities(): Future[Capabilities] = {
    Future { supportedCaps }
  }

  def createJob(job: JobSubmission): Job = {
    val newJob = Job(UUID.randomUUID.toString, None, job.input, job.output, "PENDING")
    jobList = newJob :: jobList
    newJob
  }

  def startJob(job: JobSubmission): Future[Either[Job,Err]] = {
    Future { (supportedCaps.input.contains(job.input),
      supportedCaps.output.contains(job.output)) match {
        case (true, true)   => Left(createJob(job))
        case (true, false)  => Right(Err(s"${job.output} is an invalid output format"))
        case (false, true)  => Right(Err(s"${job.input} is an invalid input format"))
        case (false, false) => Right(Err(s"${job.input} is an invalid input and ${job.output} is an invalid output"))
      } 
    }
  }

  def checkStatus(jobId: String): Future[Either[Job, Err]] = Future {
      jobList.find(job => job.id == jobId) match {
        case Some(j) => Left(j)
        case None => Right(Err(s"$jobId not found"))
      }
    }

  def main(args: Array[String]): Unit = {
    val route: Route =
      get {
        path("caps") { 
          complete(fetchCapabilities())
        }
      } ~
      path("jobs") {
        get {
          complete(jobList)
        }
      } ~
      pathPrefix("job") {
        pathEnd {
          post {
            entity (as[JobSubmission]) { job =>
              val eitherJob: Future[Either[Job, Err]] = startJob(job)
              onSuccess(eitherJob) {
                case Left(id)   => complete(id)
                case Right(err) => complete(StatusCodes.UnprocessableEntity -> err)
              }
            }
          }
        } ~
        pathPrefix(Segment) { id =>
          pathEnd {
            get {
              onSuccess(checkStatus(id)) {
                case Left(job) => complete(job)
                case Right(err) => complete(StatusCodes.NotFound -> err)
              }
            } ~
            delete {
              complete("Cancelled the job and removed all the files")
            }
          } ~
          path("add") {
            post {
              complete("Added file to translate")
            }
          } ~
          path("submit") {
            get {
              complete("Submitted to queue")
            }
          } ~
          path("download") {
            get {
              complete("Downloaded completed translation")
            }
          }
        }
      }
      
    val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

    println("Server online at http://localhost:8080/\nPress RETURN to stop...")
      StdIn.readLine()
      bindingFuture
        .flatMap(_.unbind())
        .onComplete(_ => system.terminate())
  }
}
