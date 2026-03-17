package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class RedirectSimulation extends Simulation {

  val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")

  // Pre-seeded short keys to redirect (these must exist in the DB)
  // In practice, run ShortenUrlSimulation first, or pre-seed via init script
  val shortKeys = (1 to 1000).map(i => f"key$i%04d").toArray
  val feeder = Iterator.continually(Map("shortKey" -> shortKeys(util.Random.nextInt(shortKeys.length))))

  val httpProtocol = http
    .baseUrl(baseUrl)
    .disableFollowRedirect  // don't follow 302 — just measure first hop
    .acceptHeader("text/html,application/xhtml+xml")
    .userAgentHeader("Gatling/PerformanceTest")

  val redirectScenario = scenario("Redirect Hot Path")
    .feed(feeder)
    .exec(
      http("GET /{shortKey}")
        .get("/#{shortKey}")
        .check(status.in(302, 404))   // 404 ok if key not seeded
        .check(responseTimeInMillis.lte(200))  // SLO: p99 < 200ms (gatling assertion is per-request)
    )

  setUp(
    redirectScenario.inject(
      rampUsersPerSec(10).to(500).during(2.minutes),   // ramp up
      constantUsersPerSec(500).during(10.minutes),     // sustained load (~500 RPS single node)
      rampUsersPerSec(500).to(0).during(1.minute)      // cool down
    ).protocols(httpProtocol)
  ).assertions(
    global.responseTime.percentile(99).lte(200),       // p99 < 200ms (SLO: <50ms at scale, 200ms single node)
    global.responseTime.percentile(95).lte(100),       // p95 < 100ms
    global.successfulRequests.percent.gte(99.9),       // 99.9% success rate
    global.failedRequests.percent.lte(0.1)
  )
}
