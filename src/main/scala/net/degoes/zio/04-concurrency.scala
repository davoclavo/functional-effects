package net.degoes.zio

import zio._
import zio.clock.Clock

import scala.annotation.tailrec
import scala.collection.AbstractSeq

object QueueRace extends App {
  import zio._
  import zio.console._

  val queueCount = 10
  val queueIterator: Seq[Int] = (1 to queueCount)

  def takeAndPrint(queue: Queue[Int]): URIO[Console, Int] =
    queue.take.tap(num => printWithFiber("In race", num))

  def printWithFiber(status: String, num: Int): URIO[Console, Unit] =
    putStrLn(s"[$status] Took $num")

  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    (for {
      queues: Seq[Queue[Int]] <- ZIO.foreachPar(queueIterator){ num =>
        // Create a queue and offer a single number
        Queue.unbounded[Int].tap(_.offer(num))
      }
      nums: Seq[Int] <- ZIO.foreachPar(queueIterator){_ =>
        // Take
        ZIO.raceAll(takeAndPrint(queues.head), queues.tail.map(takeAndPrint)).tap(num => printWithFiber("Race done", num))
      }
      // It never prints this as it just hangs because all queues are empty
      // TODO Fix that
      _ <- putStrLn(nums.toString)
    } yield ()).exitCode
}

object ForkJoin extends App {
  import zio.console._

  val lazyIntLayer: ULayer[Has[Int]] = ZLayer.suspend(ZLayer.succeed[Int](???))
  val lazyStringLayer: ULayer[Has[String]] = ZLayer.suspend(ZLayer.succeed[String]("Hola"))
  val lazyLayer: ZLayer[Any, Nothing, Has[Int] with Has[String]] = lazyIntLayer ++ lazyStringLayer

  val printer: ZIO[Has[String] with Console with Clock, Nothing, Long] =
    putStrLn(".").repeat(Schedule.recurs(10))

  /**
   * EXERCISE
   *
   * Using `ZIO#fork`, fork the `printer` into a separate fiber, and then
   * print out a message, "Forked", then join the fiber using `Fiber#join`,
   * and finally, print out a message "Joined".
   */
  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    (for {
      fiber: Fiber.Runtime[Nothing, Long] <- printer.provideSomeLayer[Console with Clock](lazyLayer).fork
      _                                   <- putStrLn("Forked")
      _                                   <- fiber.join
      _                                   <- putStrLn("Joined")
    } yield ()).exitCode
}

object ForkInterrupt extends App {
  import zio.console._
  import zio.duration._

  val infinitePrinter: ZIO[Console, Nothing, Nothing] =
    putStrLn(".").forever

  /**
   * EXERCISE
   *
   * Using `ZIO#fork`, fork the `printer` into a separate fiber, and then
   * print out a message, "Forked", then using `ZIO.sleep`, sleep for 100
   * milliseconds, then interrupt the fiber using `Fiber#interrupt`, and
   * finally, print out a message "Interrupted".
   */
  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    (for {
      fiber <- infinitePrinter.fork
      _     <- putStrLn("Forked")
      _     <- ZIO.sleep(100.millis)
      _     <- fiber.interrupt
      _     <- putStrLn("Interrupted")
    } yield ()).exitCode
}

object ParallelFib extends App {
  import zio.console._

  /**
   * EXERCISE
   *
   * Rewrite this implementation to compute nth fibonacci number in parallel.
   */
  def fib(n: Int): UIO[BigInt] = {
    val base: Seq[Fiber.Synthetic[Nothing, BigInt]] = Seq(Fiber.done(Exit.succeed(0)), Fiber.done(Exit.succeed(1)))
    val fibers: ZIO[Any, Nothing, Seq[Fiber.Synthetic[Nothing, BigInt]]] =
      ZIO.iterate(base)(_.size <= n) { fibers =>
        val newFiber: Fiber.Synthetic[Nothing, BigInt] =
          fibers(fibers.size - 2).zipWith(fibers(fibers.size - 1))(_ + _).map { num =>
            println(s"Generated $num")
            num
          }
        val newFibers: Seq[Fiber.Synthetic[Nothing, BigInt]] = fibers :+ newFiber
        ZIO.succeed(newFibers)
      }
    fibers.flatMap(_.last.join)
  }

  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    for {
      n <- (putStrLn(
            "What number of the fibonacci sequence should we calculate?"
          ) *> getStrLn.mapEffect(_.toInt)).eventually
      f <- fib(n)
      _ <- putStrLn(s"fib(${n}) = ${f}")
    } yield ExitCode.success
}

object AlarmAppImproved extends App {
  import zio.console._
  import zio.duration._
  import java.io.IOException
  import java.util.concurrent.TimeUnit

  lazy val getAlarmDuration: ZIO[Console, IOException, Duration] = {
    def parseDuration(input: String): IO[NumberFormatException, Duration] =
      ZIO
        .effect(
          Duration((input.toDouble * 1000.0).toLong, TimeUnit.MILLISECONDS)
        )
        .refineToOrDie[NumberFormatException]

    val fallback = putStrLn("You didn't enter a number of seconds!") *> getAlarmDuration

    for {
      _        <- putStrLn("Please enter the number of seconds to sleep: ")
      input    <- getStrLn
      duration <- parseDuration(input) orElse fallback
    } yield duration
  }

  /**
   * EXERCISE
   *
   * Create a program that asks the user for a number of seconds to sleep,
   * sleeps the specified number of seconds using ZIO.sleep(d), concurrently
   * prints a dot every second that the alarm is sleeping for, and then
   * prints out a wakeup alarm message, like "Time to wakeup!!!".
   */
  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    (for {
      seconds <- getAlarmDuration
      fiber   <- (putStrLn(".") *> ZIO.sleep(1.second)).forever.fork
      _       <- ZIO.sleep(seconds)
      _       <- fiber.interrupt
      _       <- putStrLn("Time to wake up!!!")
    } yield ()).exitCode
}

/**
 * Effects can be forked to run in separate fibers. Sharing information between fibers can be done
 * using the `Ref` data type, which is like a concurrent version of a Scala `var`.
 */
object ComputePi extends App {
  import zio.random._
  import zio.console._
  import zio.clock._
  import zio.duration._
  import zio.stm._

  /**
   * Some state to keep track of all points inside a circle,
   * and total number of points.
   */
  final case class PiState(
    inside: Ref[Long],
    total: Ref[Long]
  )

  /**
   * A function to estimate pi.
   */
  def estimatePi(inside: Long, total: Long): Double =
    (inside.toDouble / total.toDouble) * 4.0

  /**
   * A helper function that determines if a point lies in
   * a circle of 1 radius.
   */
  def insideCircle(x: Double, y: Double): Boolean =
    Math.sqrt(x * x + y * y) <= 1.0

  /**
   * An effect that computes a random (x, y) point.
   */
  val randomPoint: ZIO[Random, Nothing, (Double, Double)] =
    nextDouble zip nextDouble

  /**
   * EXERCISE
   *
   * Build a multi-fiber program that estimates the value of `pi`. Print out
   * ongoing estimates continuously until the estimation is complete.
   */
  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    (for {
      insides: Ref[Long] <- Ref.make(0L)
      total: Ref[Long]   <- Ref.make(0L)
      generator <- randomPoint.flatMap {
                    case (x, y) =>
                      ZIO.when(insideCircle(x, y))(insides.update(_ + 1)) *>
                        total.update(_ + 1)
                  }.forever.fork
      eff = insides.get.zipWith(total.get)(estimatePi)
      _ <- eff
            .tap(estimation => putStrLn(s"Estimation: $estimation"))
            .repeatUntil(estimation => Math.abs(estimation - 3.1416) <= 0.001)
      _ <- generator.interrupt
    } yield ()).exitCode
}

object ParallelZip extends App {
  import zio.console._

  def fib(n: Int): UIO[Int] =
    if (n <= 1) UIO(n)
    else
      UIO.effectSuspendTotal {
        (fib(n - 1) zipWith fib(n - 2))(_ + _)
      }

  /**
   * EXERCISE
   *
   * Compute fib(10) and fib(13) in parallel using `ZIO#zipPar`, and display
   * the result.
   */
  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    (fib(10) zipPar fib(13)).flatMap(t => putStrLn(t.toString)).exitCode
}

object StmSwap extends App {
  import zio.console._
  import zio.stm._

  /**
   * EXERCISE
   *
   * Demonstrate the following code does not reliably swap two values in the
   * presence of concurrency.
   */
  def exampleRef: UIO[Int] = {
    def swap[A](ref1: Ref[A], ref2: Ref[A]): UIO[Unit] =
      for {
        v1 <- ref1.get
        v2 <- ref2.get
        _  <- ref2.set(v1)
        _  <- ref1.set(v2)
      } yield ()

    for {
      ref1   <- Ref.make(100)
      ref2   <- Ref.make(0)
      fiber1 <- swap(ref1, ref2).repeatN(100).fork
      fiber2 <- swap(ref2, ref1).repeatN(100).fork
      _      <- (fiber1 zip fiber2).join
      value  <- (ref1.get zipWith ref2.get)(_ + _)
    } yield value
  }

  /**
   * EXERCISE
   *
   * Using `STM`, implement a safe version of the swap function.
   */
  def exampleStm: UIO[Int] = {
    def swap[A](ref1: TRef[A], ref2: TRef[A]): UIO[Unit] =
      (for {
        v1 <- ref1.get
        v2 <- ref2.get
        _ <- ref1.set(v2)
        _ <- ref2.set(v1)
      } yield ()).commit

    for {
      ref1   <- TRef.makeCommit(100)
      ref2   <- TRef.make(0).commit
      fiber1 <- swap(ref1, ref2).repeatN(100).fork
      fiber2 <- swap(ref2, ref1).repeatN(100).fork
      _      <- (fiber1 zip fiber2).join
      value  <- (ref1.get zipWith ref2.get)(_ + _).commit
    } yield value
  }

  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    exampleRef.map(_.toString).flatMap(putStrLn(_)).exitCode
}

object StmLock extends App {
  import zio.console._
  import zio.stm._

  /**
   * EXERCISE
   *
   * Using STM, implement a simple binary lock by implementing the creation,
   * acquisition, and release methods.
   */
  class Lock private (tref: TRef[Boolean]) {
    def acquire: UIO[Unit] = (for {
      //      free <- tref.get
      //      _ <- if(free) ZSTM.unit else ZSTM.retry

      //      free <- tref.get
      //      _ <- ZSTM.when(!free)(ZSTM.retry)

      _ <- tref.get.retryUntil(identity)

      _ <- tref.set(false)
    } yield ()).commit

    def release: UIO[Unit] = (for {
      _ <- tref.get.retryWhile(identity)
      _ <- tref.set(true)
    } yield ()).commit
  }
  object Lock {
    def make: UIO[Lock] =
      TRef.makeCommit(true).map(tref => new Lock(tref))
  }

  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    for {
      lock <- Lock.make
      fiber1 <- lock.acquire
                 .bracket_(lock.release)(
                   putStrLn("Bob: I have the lock!").repeat(Schedule.recurs(10))
                 ).fork
      fiber2 <- lock.acquire
                 .bracket_(lock.release)(
                   putStrLn("Sarah: I have the lock!").repeat(Schedule.recurs(10))
                 ).fork
      _ <- (fiber1 zip fiber2).join
    } yield ExitCode.success
}

object StmQueue extends App {
  import zio.console._
  import zio.stm._
  import scala.collection.immutable.{ Queue => ScalaQueue }
  import zio.duration.durationInt

  /**
   * EXERCISE
   *
   * Using STM, implement a async queue with double back-pressuring.
   */
  class Queue[A] private (capacity: Int, queue: TRef[ScalaQueue[A]]) {
    def take: UIO[A]           = (for {
      scalaQueue: ScalaQueue[A] <- queue.get.retryUntil(_.size > 0)
      element <- queue.modify(_.dequeue)
    } yield element).commit

    def offer(a: A): UIO[Unit] = (for {
      scalaQueue: ScalaQueue[A] <- queue.get.retryUntil(_.size < capacity)
      _ <- queue.set(scalaQueue.enqueue(a))
    } yield ()
    ).commit
  }
  object Queue {
    def bounded[A](capacity: Int): UIO[Queue[A]] =
      TRef.makeCommit(ScalaQueue.empty[A]).map { tref =>
        new Queue(capacity, tref)
      }
  }

  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    for {
      queue <- Queue.bounded[Int](10)
      _     <- putStrLn("Starting program")
      _     <- ZIO.foreach(0 to 100)(i =>  putStrLn(s"Enqueuing $i") *> queue.offer(i) <* putStrLn(s"Enqueued $i")).fork
      _     <- ZIO.sleep(3.seconds)
      _     <- ZIO.foreach(0 to 100)(_ => queue.take.flatMap(i => putStrLn(s"Got: ${i}")))
    } yield ExitCode.success
}

object StmLunchTime extends App {
  import zio.console._
  import zio.stm._
  import zio.duration.durationInt

  /**
   * EXERCISE
   *
   * Using STM, implement the missing methods of Attendee.
   */
  final case class Attendee(state: TRef[Attendee.State]) {
    import Attendee.State._

    def isStarving: STM[Nothing, Boolean] =
      state.get.map(_ == Starving)

    def feed: STM[Nothing, Unit] = for {
      _ <- state.get.retryUntil(_ == Starving)
      _ <- state.set(Full)
    } yield ()
  }

  object Attendee {
    sealed trait State
    object State {
      case object Starving extends State
      case object Full     extends State
    }
  }

  /**
   * EXERCISE
   *
   * Using STM, implement the missing methods of Table.
   */
  final case class Table(seats: TArray[Boolean]) {
    def findEmptySeat: STM[Nothing, Option[Int]] =
      seats
        .fold[(Int, Option[Int])]((0, None)) {
          case ((index, z @ Some(_)), _) =>
            (index + 1, z)
          case ((index, None), taken) =>
            (index + 1, if (taken) None else Some(index))
        }
        .map(_._2)

    def takeSeat(index: Int): STM[Nothing, Unit] = for {
      _ <- seats(index).retryWhile(identity)
      _ <- seats.update(index, _ => true)
    } yield ()

    def vacateSeat(index: Int): STM[Nothing, Unit] = for {
      _ <- seats(index).retryUntil(identity)
      _ <- seats.update(index, _ => false)
    } yield ()
  }

  /**
   * EXERCISE
   *
   * Using STM, implement a method that feeds a single attendee.
   */
  def feedAttendee(t: Table, a: Attendee): URIO[Console with Clock, Unit] = for {
    seat <- t.findEmptySeat.retryUntil(_.isDefined).map(_.get).tap { seat => t.takeSeat(seat)}.commit
    _ <- putStrLn(s"Taking seat $seat")
    _ <- ZIO.sleep(1.second)
    _ <- a.feed.commit
    _ <- t.vacateSeat(seat).commit
    _ <- putStrLn(s"Vacating seat $seat")
  } yield ()

  /**
   * EXERCISE
   *
   * Using STM, implement a method that feeds only the starving attendees.
   */
  def feedStarving(table: Table, attendees: Iterable[Attendee]): URIO[Clock with Console, Unit] =
    ZIO.foreachPar(attendees)(attendee =>
      feedAttendee(table, attendee)
    ).unit

  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = {
    val Attendees = 100
    val TableSize = 5

    for {
      attendees <- ZIO.foreach(0 to Attendees)(i =>
                    TRef
                      .make[Attendee.State](Attendee.State.Starving)
                      .map(Attendee(_))
                      .commit
                  )
      table <- TArray
                .fromIterable(List.fill(TableSize)(false))
                .map(Table(_))
                .commit
      _ <- feedStarving(table, attendees)
    } yield ExitCode.success
  }
}

object StmPriorityQueue extends App {
  import zio.console._
  import zio.stm._
  import zio.duration._

  /**
   * EXERCISE
   *
   * Using STM, design a priority queue, where smaller integers are assumed
   * to have higher priority than greater integers.
   */

  class PriorityQueue[A] private (
    minLevel: TRef[Option[Int]],
    map: TMap[Int, TQueue[A]]
  ) {
    def offer(a: A, priority: Int): STM[Nothing, Unit] = for {
      maybeQueue: Option[TQueue[A]] <- map.get(priority)
      queue: TQueue[A] <- maybeQueue match {
        case Some(queue) => STM.succeed(queue)
        case None => TQueue.unbounded[A].tap(map.put(priority, _))
      }
      () <- queue.offer(a)
      () <- setMinLevel
    } yield ()

    private def setMinLevel: STM[Nothing, Unit] = for {
      maybeMinLevel <- map.keys.map(_.sorted.headOption)
      () <- minLevel.set(maybeMinLevel)
    } yield ()

    def take: STM[Nothing, A] = for {
      Some(minLevel) <- minLevel.get.retryWhile(_.isEmpty)
      Some(queue) <- map.get(minLevel)
      element <- queue.take
      isEmpty <- queue.isEmpty
      () <- STM.when(isEmpty)(map.delete(minLevel) *> setMinLevel)
    } yield element
  }
  object PriorityQueue {
    def make[A]: STM[Nothing, PriorityQueue[A]] =  for {
      minLevel <- TRef.make(Option.empty[Int])
      map <- TMap.empty[Int, TQueue[A]]
    } yield new PriorityQueue[A](minLevel, map)
  }

  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    (for {
      _     <- putStrLn("Enter any key to exit...")
      queue <- PriorityQueue.make[String].commit
      lowPriority = ZIO.foreach(0 to 100) { i =>
        queue
          .offer(s"Offer: ${i} with priority 3", 3)
          .commit
      }
      highPriority = ZIO.foreach(0 to 100) { i =>
        queue
          .offer(s"Offer: ${i} with priority 0", 0)
          .commit
      }
      _ <- ZIO.collectAllPar(List(lowPriority, highPriority)) *>
          queue.take.commit
            .flatMap(putStrLn(_))
            .forever
            .fork *>
            getStrLn
    } yield 0).exitCode
}

object StmReentrantLock extends App {
  import zio.console._
  import zio.stm._

  case class WriteLock(
    writeCount: Int,
    readCount: Int,
    fiberId: Fiber.Id
  )
  class ReadLock private (readers: Map[Fiber.Id, Int]) {
    def total: Int = readers.values.sum

    def noOtherHolder(fiberId: Fiber.Id): Boolean =
      readers.size == 0 || (readers.size == 1 && readers.contains(fiberId))

    def readLocks(fiberId: Fiber.Id): Int =
      readers.get(fiberId).fold(0)(identity)

    def adjust(fiberId: Fiber.Id, adjust: Int): ReadLock = {
      val total = readLocks(fiberId)

      val newTotal = total + adjust

      new ReadLock(
        readers =
          if (newTotal == 0) readers - fiberId
          else readers.updated(fiberId, newTotal)
      )
    }
  }
  private object ReadLock {
    val empty: ReadLock = new ReadLock(Map())

    def apply(fiberId: Fiber.Id, count: Int): ReadLock =
      if (count <= 0) empty else new ReadLock(Map(fiberId -> count))
  }

  /**
   * EXERCISE
   *
   * Using STM, implement a reentrant read/write lock.
   */
  class ReentrantReadWriteLock(data: TRef[Either[ReadLock, WriteLock]]) {
    type State = Either[ReadLock, WriteLock]
    def writeLocks: State => Int = state => state.fold(_ => 0, _.writeCount)

    def writeLocked: State => Boolean = state => state.fold(_ => false, _.writeCount > 0)

    def readLocks: State => Int = state => state.fold(_.total, _.readCount)

    def readLocked: State => Boolean = state => state.fold(_.total > 0, _.readCount > 0)

    val read: Managed[Nothing, Int] = Managed.make {
      (for {
        fiberId <- ZSTM.fiberId
        _ <- data.get.map(writeLocked).retryWhile(identity)
        readCount <- data.updateAndGet(_.fold(rl => Left(rl.adjust(fiberId, 1)), Right(_))).map(readLocks)
      } yield readCount).commit
    }{ _ =>
      (for {
          fiberId <- ZSTM.fiberId
          // TODO Make sure we are releasing in the correct state
          // isReadLocked <- data.get.map(readLocked)
          // () <- STM.cond(!isReadLocked, (), "Can't release read lock while not in ReadLock state")
          _ <- data.update(_.fold(rl => Left(rl.adjust(fiberId, -1)), Right(_)))
        } yield ()).commit
    }

    val write: Managed[Nothing, Int] = Managed.make {
      (for {
        fiberId <- ZSTM.fiberId
        _ <- data.get.map(_.fold(_.noOtherHolder(fiberId), _.fiberId == fiberId)).retryUntil(identity)
        writeCount <- data.updateAndGet(_.fold(Left(_), wl => Right(wl.copy(writeCount = wl.writeCount + 1)))).map(writeLocks)
      } yield writeCount).commit
    }{ _ =>
      (for {
        fiberId <- ZSTM.fiberId
        // TODO Make sure we are releasing in the correct state
        // isWriteLocked <- data.get.map(writeLocked)
        // _ <- STM.cond(!isWriteLocked, (), "Can't release write lock while not in WriteLock state")
        _ <- data.update(_.fold(Left(_), wl => {
          val newCount = wl.writeCount - 1
          if(newCount == 0) Left(ReadLock(fiberId, wl.readCount))
          else Right(wl.copy(writeCount = newCount))
        } ))
      } yield ()).commit
    }
  }
  object ReentrantReadWriteLock {
    def make: UIO[ReentrantReadWriteLock] =
      TRef.makeCommit[Either[ReadLock, WriteLock]](Left(ReadLock.empty)).map(new ReentrantReadWriteLock(_))
  }

  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = ???
}

object StmDiningPhilosophers extends App {
  import zio.console._
  import zio.stm._

  sealed trait Fork
  val Fork = new Fork {}

  final case class Placement(
    left: TRef[Option[Fork]],
    right: TRef[Option[Fork]]
  )

  final case class Roundtable(seats: Vector[Placement])

  /**
   * EXERCISE
   *
   * Using STM, implement the logic of a philosopher to take not one fork, but
   * both forks when they are both available.
   */
  def takeForks(
    philosopher: Int,
    left: TRef[Option[Fork]],
    right: TRef[Option[Fork]]
  ): STM[Nothing, (Fork, Fork)] =
    for {
      Some(leftFork) <- left.modify {
        case None =>
          println(s"$philosopher attempted to take left fork but it is taken")
          (None, None)
        case Some(fork) =>
          println(s"$philosopher successfully took left fork")
          (Some(fork), None)
      }.retryUntil(_.isDefined)

      Some(rightFork) <- right.modify {
        case None =>
          println(s"$philosopher attempted to take right fork but it is taken")
          (None, None)
        case Some(fork) =>
          println(s"$philosopher successfully took right fork")
          (Some(fork), None)
      }.retryUntil(_.isDefined)

//      leftFork: Fork <- left.get.repeatUntil(_.isDefined).map(_.get)
//      _ <- left.set(None)
//      rightFork: Fork <- right.get.repeatUntil(_.isDefined).map(_.get)
//      _ <- right.set(None)

    } yield (leftFork, rightFork)

  /**
   * EXERCISE
   *
   * Using STM, implement the logic of a philosopher to release both forks.
   */
  def putForks(left: TRef[Option[Fork]], right: TRef[Option[Fork]])(
    tuple: (Fork, Fork)
  ): STM[Nothing, Unit] = {
    val (leftFork, rightFork) = tuple
    for {
      _ <- left.set(Some(leftFork))
      _ <- right.set(Some(rightFork))
    } yield ()
  }


  def setupTable(size: Int): ZIO[Any, Nothing, Roundtable] = {
    val makeFork: USTM[TRef[Option[Fork]]] = TRef.make[Option[Fork]](Some(Fork))

    (for {
      allForks0 <- STM.foreach(0 to size)(i => makeFork)
      allForks  = allForks0 ++ List(allForks0(0))
      placements = (allForks zip allForks.drop(1)).map {
        case (l, r) => Placement(l, r)
      }
    } yield Roundtable(placements.toVector)).commit
  }

  def eat(
    philosopher: Int,
    roundtable: Roundtable
  ): ZIO[Console, Nothing, Unit] = {
    val placement: Placement = roundtable.seats(philosopher)

    val left  = placement.left
    val right = placement.right

    for {
      forks <- takeForks(philosopher, left, right).commit
      _     <- putStrLn(s"Philosopher ${philosopher} eating...")
      _     <- putForks(left, right)(forks).commit
      _     <- putStrLn(s"Philosopher ${philosopher} is done eating")
    } yield ()
  }

  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = {
    val count = 10

    def eaters(table: Roundtable): Iterable[ZIO[Console, Nothing, Unit]] =
      (0 to count).map(index => eat(index, table))

    for {
      table <- setupTable(count)
      fiber <- ZIO.forkAll(eaters(table))
      _     <- fiber.join
      _     <- putStrLn("All philosophers have eaten!")
    } yield ExitCode.success
  }
}

object StreamExample extends App {
  import zio._
  import zio.console._
  import zio.stream._

  val stream = Stream
    .fromIterable(Seq(1, 2, 3, 4, 5))
    .mapMPar(3)(num => ZIO.sleep(zio.duration.Duration.fromMillis(5000)).map(_ => num * 10))
    .foreach(num => putStrLn(s"${java.time.LocalDateTime.now} El nuevo numero es: ${num}"))

  def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    stream.exitCode

}
