package net.degoes.zio

import zio.Schedule.exponential
import zio._
import zio.clock._
import zio.duration._
import zio.console._

object Retry extends App {

  /**
   * EXERCISE
   *
   * Using `Schedule.recurs`, create a schedule that recurs 5 times.
   */
  val fiveTimes: Schedule[Any, Any, Long] = Schedule.recurs(5)
  val x: URIO[Clock, Long] = ZIO.succeed(1).repeat(fiveTimes)

  /**
   * EXERCISE
   *
   * Using the `ZIO#repeat`, repeat printing "Hello World" five times to the
   * console.
   */
  val repeated1: ZIO[Console with Clock, Nothing, Long] = console.putStrLn("Hello World").repeat(fiveTimes)

  /**
   * EXERCISE
   *
   * Using `Schedule.spaced`, create a schedule that recurs forever every 1 second.
   */
  val everySecond: Schedule[Any, Any, Long] = Schedule.spaced(1.second)

  /**
   * EXERCISE
   *
   * Using the `&&` method of the `Schedule` object, the `fiveTimes` schedule,
   * and the `everySecond` schedule, create a schedule that repeats fives times,
   * evey second.
   */
  val fiveTimesEverySecond: Schedule[Any, Any, (Long, Long)] = everySecond && fiveTimes

  /**
   * EXERCISE
   *
   * Using the `ZIO#repeat`, repeat the action putStrLn("Hi hi") using
   * `fiveTimesEverySecond`.
   */
  val repeated2: ZIO[Console with Clock, Nothing, (Long, Long)] = putStrLn("Hi hi").repeat(fiveTimesEverySecond)

  /**
   * EXERCISE
   *
   * Using `Schedule#andThen` the `fiveTimes` schedule, and the `everySecond`
   * schedule, create a schedule that repeats fives times rapidly, and then
   * repeats every second forever.
   */
  lazy val fiveTimesThenEverySecond: Schedule[Any, Any, Long] = fiveTimes.andThen(everySecond)

  /**
   * EXERCISE
   *
   * Using `ZIO#retry`, retry the following error a total of five times.
   */
  val error1   = IO.fail("Uh oh!")
  lazy val retried5: ZIO[Clock, String, Nothing] = error1.retry(fiveTimes)

  /**
   * EXERCISE
   *
   * Using the `Schedule#||`, the `fiveTimes` schedule, and the `everySecond`
   * schedule, create a schedule that repeats the minimum of five times and
   * every second.
   */
  lazy val fiveTimesOrEverySecond = fiveTimes || everySecond

  /**
   * EXERCISE
   *
   * Using `Schedule.exponential`, create an exponential schedule that starts
   * from 10 milliseconds.
   */
  lazy val exponentialSchedule: Schedule[Any, Any, Duration] = Schedule.exponential(10.millis)

  // (effect orElse otherService).retry(exponentialSchedule).timeout(60.seconds)

  /**
   * EXERCISE
   *
   * Using `Schedule#jittered` produced a jittered version of `exponentialSchedule`.
   */
  lazy val jitteredExponential = exponentialSchedule.jittered

  /**
   * EXERCISE
   *
   * Using `Schedule#whileOutput`, produce a filtered schedule from `Schedule.forever`
   * that will halt when the number of recurrences exceeds 100.
   */
  lazy val oneHundred = Schedule.forever.whileOutput(_ <= 100)

  /**
   * EXERCISE
   *
   * Using `Schedule.identity`, produce a schedule that recurs forever, without delay,
   * returning its inputs.
   */
  def inputs[A]: Schedule[Any, A, A] = Schedule.identity

  /**
   * EXERCISE
   *
   * Using `Schedule#collect`, produce a schedule that recurs forever, collecting its
   * inputs into a list.
   */
  def collectedInputs[A]: Schedule[Any, A, List[A]] = inputs.collectAll.map(_.to)

  /**
   * EXERCISE
   *
   * Using  `*>` (`zipRight`), combine `fiveTimes` and `everySecond` but return
   * the output of `everySecond`.
   */
  lazy val fiveTimesEverySecondR: Schedule[Any, Any, Long] = fiveTimes *> everySecond

  /**
   * EXERCISE
   *
   * Produce a jittered schedule that first does exponential spacing (starting
   * from 10 milliseconds), but then after the spacing reaches 60 seconds,
   * switches over to fixed spacing of 60 seconds between recurrences, but will
   * only do that for up to 100 times, and produce a list of the inputs to
   * the schedule.
   */
  import zio.random.Random
  import Schedule.{ collectAll, exponential, fixed, recurs }
  def mySchedule[A]: Schedule[ZEnv, A, List[A]] = {
    val initialSchedule: Schedule[Any, Any, Any] =
      (Schedule.exponential(10.millis).whileOutput(_ < 1.minutes) ++ Schedule.fixed(1.minute).untilOutput(_ > 100))
    Schedule.identity[A] <* initialSchedule >>> Schedule.collectAll[A].map(_.to)
  }


  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    clock.instant.flatMap(i => putStrLn(i.toString)).repeat(mySchedule).exitCode
  }
}
