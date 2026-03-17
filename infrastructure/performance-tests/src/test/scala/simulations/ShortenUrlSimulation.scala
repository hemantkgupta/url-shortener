package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class ShortenUrlSimulation extends Simulation {

  val writeUrl = System.getProperty("writeUrl", "http://localhost:8082")

  val httpProtocol = http
    .baseUrl(writeUrl)
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")

  // Generate unique long URLs per user
  val urlFeeder = Iterator.continually {
    val uniqueId = java.util.UUID.randomUUID().toString
    Map(
      "longUrl" -> s"https://example.com/products/$uniqueId?utm_source=test&utm_campaign=perf"
    )
  }

  val shortenScenario = scenario("Shorten URL Write Path")
    .feed(urlFeeder)
    .exec(
      http("POST /v1/urls")
        .post("/v1/urls")
        .body(StringBody("""{"long_url":"#{longUrl}"}""")).asJson
        .check(status.is(201))
        .check(jsonPath("$.short_key").exists.saveAs("shortKey"))
        .check(responseTimeInMillis.lte(500))
    )
    .pause(10.milliseconds, 50.milliseconds)  // slight think time

  setUp(
    shortenScenario.inject(
      rampUsersPerSec(5).to(100).during(2.minutes),
      constantUsersPerSec(100).during(5.minutes),
      rampUsersPerSec(100).to(0).during(1.minute)
    ).protocols(httpProtocol)
  ).assertions(
    global.responseTime.percentile(99).lte(500),     // p99 < 500ms (SLO: 200ms at scale)
    global.responseTime.percentile(95).lte(300),
    global.successfulRequests.percent.gte(99.0),
    global.requestsPerSec.gte(80)                    // at least 80 RPS throughput
  )
}
