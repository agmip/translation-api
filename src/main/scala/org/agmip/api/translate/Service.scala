package org.agmip.api.translate

import java.io.File
import java.nio.file.{Files, Path, Paths, SimpleFileVisitor}
import java.util.{Comparator, UUID}

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
  val fileStore = Paths.get("./storage/").normalize.toAbsolutePath
  implicit val system = ActorSystem("translate-api")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  final case class Capabilities(input: List[String], output: List[String])
  final case class Job(id: String,
                       name: Option[String],
                       input: String,
                       output: String,
                       status: String)
  final case class JobSubmission(name: Option[String],
                                 input: String,
                                 output: String)
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
    val newJob =
      Job(UUID.randomUUID.toString, None, job.input, job.output, "PENDING")
    jobList = newJob :: jobList
    newJob
  }

  def findJob(jobId: String): Either[Job, Err] = {
    jobList.find(job => job.id == jobId) match {
      case Some(j) => Left(j)
      case None    => Right(Err(s"Invalid $jobId"))
    }
  }

  def updateStatus(job: Job, newStatus: String): Job = {
    val newJob = Job(job.id, job.name, job.input, job.output, newStatus)
    jobList = newJob :: jobList
    newJob
  }

  def checkStatus(jobId: String) = Future {
    findJob(jobId)
  }

  def changeStatus(jobId: String, newStatus: String): Future[Either[Job, Err]] =
    Future {
      findJob(jobId) match {
        case Left(job)  => Left(updateStatus(job, newStatus))
        case Right(err) => Right(err)
      }
    }

  def startJob(job: JobSubmission): Future[Either[Job, Err]] = {
    Future {
      (supportedCaps.input.contains(job.input),
       supportedCaps.output.contains(job.output)) match {
        case (true, true) => Left(createJob(job))
        case (true, false) =>
          Right(Err(s"${job.output} is an invalid output format"))
        case (false, true) =>
          Right(Err(s"${job.input} is an invalid input format"))
        case (false, false) =>
          Right(Err(
            s"${job.input} is an invalid input and ${job.output} is an invalid output"))
      }
    }
  }

  def moveToStore(jobId: String, source: File, fileName: String) = {
    val destinationPath = fileStore.resolve(jobId).resolve("inputs")
    Files.exists(destinationPath) match {
      case true =>
        Files.copy(source.toPath(), destinationPath.resolve(fileName))
      case false => {
        Files.createDirectories(destinationPath)
        Files.copy(source.toPath(), destinationPath.resolve(fileName))
      }
    }
  }

  def executeJob(job: Job): Unit = {
  }

  def main(args: Array[String]): Unit = {
    val route: Route =
      pathPrefix("translate") {
        concat(
          pathPrefix("1") {
            concat(
              path("caps") {
                pathEndOrSingleSlash {
                  get {
                    complete(fetchCapabilities())
                  }
                }
              },
              pathPrefix("jobs") {
                concat(
                  pathEndOrSingleSlash {
                    get {
                      complete(jobList)
                    }
                  },
                  pathPrefix("job") {
                    concat(
                      pathEndOrSingleSlash {
                        post {
                          entity(as[JobSubmission]) { job =>
                            val eitherJob: Future[Either[Job, Err]] = startJob(job)
                            onSuccess(eitherJob) {
                              case Left(id) => complete(id)
                              case Right(err) =>
                                complete(StatusCodes.UnprocessableEntity -> err)
                            }
                          }
                        }
                      },
                      pathPrefix(Segment) { id =>
                        concat(
                          pathEndOrSingleSlash {
                            concat(
                              get {
                                onSuccess(checkStatus(id)) {
                                  case Left(job) => complete(job)
                                  case Right(err) => complete(StatusCodes.NotFound -> err)
                                }
                              },
                              delete {
                                dangerouslyDeleteDirectory(fileStore.resolve(id))
                                onSuccess(changeStatus(id, "CANCELLED")) {
                                  case Left(job) => complete(job)
                                  case Right(err) => complete(StatusCodes.NotFound -> err)
                                }
                              }
                            )
                          },
                          path("add") {
                            uploadedFile("file") {
                              case (metadata, file) => {
                                moveToStore(id, file, metadata.fileName)
                                complete(s"${metadata.toString} | ${file.toString}")
                              }
                            }
                          },
                          path("submit") {
                            get {
                              onSuccess(changeStatus(id, "SUBMITTED")) {
                                case Left(job) => complete(job)
                                case Right(err) => complete(StatusCodes.NotFound -> err)
                              }
                            }
                          },
                          path("download") {
                            get {
                              complete("Downloaded completed translation")
                            }
                          }
                        )
                      }
                    )
                  }
                )
              }
            )
          })
      }

    val bindingFuture = Http().bindAndHandle(route, "0.0.0.0", 8491)
  }

  def dangerouslyDeleteDirectory(path: Path): Unit = {
    Files.walk(path).sorted(Comparator.reverseOrder()).forEach(Files.delete(_))
  }

  def cleanUp(): Unit = {
    dangerouslyDeleteDirectory(fileStore)
    system.terminate()
  }
}
