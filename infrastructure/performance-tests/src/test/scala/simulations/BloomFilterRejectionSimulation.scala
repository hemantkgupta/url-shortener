package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class BloomFilterRejectionSimulation extends Simulation {

  val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")

  val httpProtocol = http
    .baseUrl(baseUrl)
    .disableFollowRedirect

  // All fake/non-existent keys — should all hit Bloom filter and return 404 immediately
  val fakeKeyFeeder = Iterator.continually {
    Map("fakeKey" -> s"fake${util.Random.alphanumeric.take(7).mkString}")
  }

  val botScenario = scenario("Bloom Filter Bot Rejection")
    .feed(fakeKeyFeeder)
    .exec(
      http("GET /{fakeKey} - should be rejected by Bloom filter")
        .get("/#{fakeKey}")
        .check(status.is(404))
        .check(responseTimeInMillis.lte(50))  // Bloom filter rejection must be ultra-fast
    )

  setUp(
    botScenario.inject(
      constantUsersPerSec(200).during(3.minutes)
    ).protocols(httpProtocol)
  ).assertions(
    global.responseTime.percentile(99).lte(50),   // p99 < 50ms for Bloom filter rejections
    global.successfulRequests.percent.gte(99.9)   // essentially all 404s (success = expected status)
  )
}
