package net.degoes.zio

import zio.clock.Clock
import zio._
import zio.console.Console.Service

object LazyLayer extends App {
  import zio.console._

  /**
   * Here we are creating two potentially lazy layers
   * lazyIntBoomLayer is a Has[Int] Layer that when it is materialized it should throw a NotImplementedError
   * lazyStringLayer is a Has[String] Layer that materializes the value "Hello World"
   * lazyEnvironment is a set of services that are meant to be used by many apps (a'la ZEenv) but not all apps
   * require all services, so they should be materialized in a lazy fashion
   */
  lazy val int = ???
  val lazyStringLayer: ULayer[Has[String]] = ZLayer.succeed[String]("Hello World")
  val lazyIntBoomLayer: ULayer[Has[UIO[Int]]] = ZLayer.succeed(ZIO.effect(int).orDie)
  val lazyEnvironment: ZLayer[Any, Nothing, Has[UIO[Int]] with Has[String]] = lazyIntBoomLayer ++ lazyStringLayer

  val greet =
    for {
      string <- ZIO.environment[Has[String]].map(_.get)
//      int <- ZIO.environment[Has[UIO[Int]]].flatMap(_.get)
      int = 0
      _ <- putStrLn(s"$string $int")
    } yield ()

  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    greet.provideSomeLayer[Console](lazyEnvironment).exitCode
}

object AccessEnvironment extends App {
  import zio.console._

  final case class Config(server: String, port: Int)

  /**
   * EXERCISE
   *
   * Using `ZIO.access`, access a `Config` type from the environment, and
   * extract the `server` field from it.
   */
  val accessServer: ZIO[Config, Nothing, String] = ZIO.access(_.server)

  /**
   * EXERCISE
   *
   * Using `ZIO.access`, access a `Config` type from the environment, and
   * extract the `port` field from it.
   */
  val accessPort: ZIO[Config, Nothing, Int] = ZIO.access(_.port)

  def run(args: List[String]) = {
    val config = Config("localhost", 7878)

    (for {
      server <- accessServer
      port   <- accessPort
      _      <- UIO(println(s"Configuration: ${server}:${port}"))
    } yield ExitCode.success).provide(config)
  }
}

object ProvideEnvironment extends App {
  import zio.console._

  final case class Config(server: String, port: Int)

  final case class DatabaseConnection() {
    def query(query: String): Task[Int] = Task(42)
  }

  val getServer: ZIO[Config, Nothing, String] =
    ZIO.access[Config](_.server)

  val useDatabaseConnection: ZIO[DatabaseConnection, Throwable, Int] =
    ZIO.accessM[DatabaseConnection](_.query("SELECT * FROM USERS"))

  /**
   * EXERCISE
   *
   * Compose both the `getServer` and `useDatabaseConnection` effects together.
   * In order to do this successfully, you will have to use `ZIO#provide` to
   * give them the environment that they need in order to run, then they can
   * be composed because their environment types will be compatible.
   */
  def run(args: List[String]) = {
    val config = Config("localhost", 7878)
    val connection = DatabaseConnection()

    (getServer.provide(config) *> useDatabaseConnection.provide(connection)).exitCode
  }
}

object CakeEnvironment extends App {
  import zio.console._
  import java.io.IOException

  type MyFx = Logging with Files

  trait Logging {
    val logging: Logging.Service
  }
  object Logging {
    trait Service {
      def log(line: String): UIO[Unit]
    }
    def log(line: String) = ZIO.accessM[Logging](_.logging.log(line))
  }
  trait Files {
    val files: Files.Service
  }
  object Files {
    trait Service {
      def read(file: String): IO[IOException, String]
    }
    def read(file: String) = ZIO.accessM[Files](_.files.read(file))
  }

  val effect: ZIO[Logging with Files, IOException, Unit] =
    for {
      file <- Files.read("build.sbt")
      _    <- Logging.log(file)
    } yield ()

  /**
   * EXERCISE
   *
   * Run `effect` by using `ZIO#provide` to give it what it needs. You will
   * have to build a value (the environment) of the required type
   * (`Files with Logging`).
   */
  def run(args: List[String]) = {
    val logWithFiles = new Logging with Files {
      override val logging: Logging.Service = new Logging.Service {
        override def log(line: String): UIO[Unit] = UIO(println(line))
      }
      override val files: Files.Service = new Files.Service {
        override def read(file: String): IO[IOException, String] =
          IO.effect(scala.io.Source.fromFile(file).mkString).refineToOrDie[IOException]
      }
    }
    effect
      .provide(logWithFiles)
      .exitCode
  }
}

/**
 * Although there are no requirements on how the ZIO environment may be used to pass context
 * around in an application, for easier composition, ZIO includes a data type called `Has`,
 * which represents a map from a type to an object that satisfies that type. Sometimes, this is
 * called a "Has Map" or more imprecisely, a "type-level map".
 */
object HasMap extends App {
  trait Logging
  object Logging extends Logging

  trait Database
  object Database extends Database

  trait Cache
  object Cache extends Cache

  val hasLogging = Has(Logging: Logging)

  val hasDatabase = Has(Database: Database)

  val hasCache = Has(Cache: Cache)

  /**
   * EXERCISE
   *
   * Using the `++` operator on `Has`, combine the three maps (`hasLogging`, `hasDatabase`, and
   * `hasCache`) into a single map that has all three objects.
   */
  val allThree: Has[Database] with Has[Cache] with Has[Logging] = hasLogging ++ hasDatabase ++ hasCache

  /**
   * EXERCISE
   *
   * Using `Has#get`, which can retrieve an object stored in the map, retrieve the logging,
   * database, and cache objects from `allThree`. Note that you will have to specify the type
   * parameter, as it cannot be inferred (the map needs to know which of the objects you want to
   * retrieve, and that can be specified only by type).
   */
  lazy val logging  = allThree.get[Logging]
  lazy val database = allThree.get[Database]
  lazy val cache    = allThree.get[Cache]

  def run(args: List[String]) =
    ZIO.unit.exitCode
}

/**
 * In ZIO, layers are essentially wrappers around constructors for services in your application.
 * Services provide functionality like persistence or logging or authentication, and they are used
 * by business logic.
 *
 * A layer is a lot like a constructor, except that a constructor can only "construct" a single
 * service. Layers can construct many services. In addition, this construction can be resourceful,
 * and even asynchronous or concurrent.
 *
 * Layers bring more power and compositionality to constructors. Although you don't have to use
 * them to benefit from ZIO environment, they can make it easier to assemble applications out of
 * modules without having to do any wiring, and with great support for testability.
 *
 * ZIO services like `Clock` and `System` are all designed to work well with layers.
 */
object LayerEnvironment extends App {
  import zio.console._
  import java.io.IOException
  import zio.blocking._

  type MyFx = Logging with Files

  type Files = Has[Files.Service]
  object Files {
    trait Service {
      def read(file: String): IO[IOException, String]
    }

    /**
     * EXERCISE
     *
     * Using `ZLayer.succeed`, create a layer that implements the `Files`
     * service.
     */
    val live: ZLayer[Blocking, Nothing, Files] = ZLayer.fromFunction[Blocking, Files.Service](blocking =>
      (file: String) => {
        ZIO.accessM[Blocking](_.get.effectBlockingIO(scala.io.Source.fromFile(file).mkString)).provide(blocking)
      }
    )

    def read(file: String) = ZIO.accessM[Files](_.get.read(file))
  }

  type Logging = Has[Logging.Service]
  object Logging {
    trait Service {
      def log(line: String): UIO[Unit]
    }

    /**
     * EXERCISE
     *
     * Using `ZLayer.fromFunction`, create a layer that requires `Console`
     * and uses the console to provide a logging service.
     */
    val live: ZLayer[Console, Nothing, Logging] = ZLayer.fromFunction((console: Console) =>
      (line: String) => zio.console.putStrLn(line).provide(console)
    )

    def log(line: String) = ZIO.accessM[Logging](_.get.log(line))
  }

  val effect =
    (for {
      file <- Files.read("build.sbt")
      _    <- Logging.log(file)
    } yield ())

  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = {

    /**
     * EXERCISE
     *
     * Run `effect` by using `ZIO#provideCustomLayer` to give it what it needs.
     * You will have to build a value (the environment) of the required type
     * (`Files with Logging`).
     */
    val env: ZLayer[Console with Blocking, Nothing, Files with Logging] =
      Files.live ++ Logging.live

    val env2: ZLayer[Any, Nothing, Files with Logging] =
      (Console.live ++ Blocking.live) >>> (Files.live ++ Logging.live)

    val env2bis: ZLayer[Console, Nothing, Files with Logging] =
      (Console.any ++ Blocking.live) >>> (Files.live ++ Logging.live)

    val env3: ZLayer[Any, Nothing, Files with Logging with Console with Logging] =
      (Console.live ++ Blocking.live) >+> (Files.live ++ Logging.live)

    val env4: ZLayer[Blocking with Console, Nothing, Files with Logging] =
      Files.live ++ Logging.live

    val env5: ZLayer[Blocking with Console, Nothing, (Files, Logging)] =
      Files.live <&> Logging.live

    val missingConsole: ZLayer[Any, Throwable, Console] =
      ZLayer.fromEffect(ZIO.effect(???))

    val prependedConsole: ZLayer[Any, Throwable, Console] =
      ZLayer.succeed(
        new Console.Service {
          override def putStrLn(line: String): UIO[Unit] = UIO.succeed(println(s"PREPENDED $line"))

          override def putStr(line: String): UIO[Unit] = ???

          override def putStrErr(line: String): UIO[Unit] = ???

          override def putStrLnErr(line: String): UIO[Unit] = ???

          override def getStrLn: IO[IOException, String] = ???
        }
      )

    val env6: ZLayer[Any, Throwable, Console] =
      prependedConsole ++ Console.live

    val env7: ZLayer[Any, Throwable, Console] =
      prependedConsole +!+ Console.live

    val env8: ZLayer[Any, Throwable, Console] =
      missingConsole <> Console.live

    val env9: ZLayer[Any, Nothing, Files with Logging] =
      ((missingConsole <> Console.live) ++ Blocking.live) >>> (Files.live ++ Logging.live)


    val x = putStrLn("HOLA").provideLayer(prependedConsole).orDie
    x *> (effect
      .provideCustomLayer(env)
      .exitCode)
    }

}
