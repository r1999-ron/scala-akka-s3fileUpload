import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.Directives._
import akka.stream.Materializer
import com.typesafe.config.ConfigFactory
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{PutObjectRequest, PutObjectResponse}
import spray.json.DefaultJsonProtocol._
import java.nio.file.{Files, Paths}
import scala.concurrent.Future
import scala.util.{Failure, Success}

object FileUploadTask extends App {

  implicit val system: ActorSystem = ActorSystem("file-upload-service")
  implicit val materializer: Materializer = Materializer(system)
  implicit val executionContext = system.dispatcher

  val awsAccessKeyId = sys.env.getOrElse("AWS_ACCESS_KEY_ID", "")
  val awsSecretAccessKey = sys.env.getOrElse("AWS_SECRET_ACCESS_KEY", "")

  if (awsAccessKeyId.isEmpty || awsSecretAccessKey.isEmpty) {
    println("AWS access key ID or secret access key is not set. Please export them as environment variables.")
    System.exit(1)
  }

  val awsCreds = AwsBasicCredentials.create(awsAccessKeyId, awsSecretAccessKey)

  val config = ConfigFactory.load()
  val bucketName = config.getString("s3.bucket-name")
  val region = config.getString("s3.region")

  val s3Client = S3Client.builder()
    .region(Region.of(region))
    .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
    .build()

  // Define the JSON payload
  case class FileNamePayload(fileName: String)
  object FileNameJsonProtocol {
    implicit val fileNameFormat = jsonFormat1(FileNamePayload)
  }

  import FileNameJsonProtocol._

  val route =
    path("upload") {
      post {
        entity(as[FileNamePayload]) { payload =>
          val filePath = Paths.get(payload.fileName)

          if (Files.exists(filePath)) {
            val fileName = filePath.getFileName.toString

            val futureResponse: Future[PutObjectResponse] = Future {
              val putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .build()

              val requestBody = software.amazon.awssdk.core.sync.RequestBody.fromFile(filePath.toFile)
              s3Client.putObject(putObjectRequest, requestBody)
            }

            onComplete(futureResponse) {
              case Success(response) =>
                complete(s"File uploaded successfully with response: ${response.eTag()}")
              case Failure(ex) =>
                complete(s"File upload failed: ${ex.getMessage}")
            }
          } else {
            complete(s"File not found: ${payload.fileName}")
          }
        }
      }
    }

  Http().newServerAt("localhost", 8081).bind(route)
}