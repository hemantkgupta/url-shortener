package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class MixedLoadSimulation extends Simulation {

  val baseUrl  = System.getProperty("baseUrl",  "http://localhost:8080")
  val writeUrl = System.getProperty("writeUrl", "http://localhost:8082")

  val redirectProtocol = http.baseUrl(baseUrl)
    .disableFollowRedirect
    .acceptHeader("text/html")
    .userAgentHeader("Gatling/MixedLoad")

  val writeProtocol = http.baseUrl(writeUrl)
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")

  // Shared in-memory store of created short keys (bounded, thread-safe)
  val createdKeys = new java.util.concurrent.CopyOnWriteArrayList[String]()

  // Pre-seed some keys so redirect simulation has something to hit
  (1 to 200).foreach(i => createdKeys.add(f"seed$i%04d"))

  val urlFeeder = Iterator.continually {
    Map("longUrl" -> s"https://example.com/${java.util.UUID.randomUUID()}")
  }

  val redirectFeeder = Iterator.continually {
    val list = createdKeys
    val key = if (list.isEmpty) "seed0001" else list.get(util.Random.nextInt(list.size))
    Map("shortKey" -> key)
  }

  // 1 write user
  val writeScenario = scenario("Write - Shorten URL")
    .feed(urlFeeder)
    .exec(
      http("POST /v1/urls")
        .post("/v1/urls")
        .body(StringBody("""{"long_url":"#{longUrl}"}""")).asJson
        .check(status.is(201))
        .check(jsonPath("$.short_key").saveAs("createdKey"))
    )
    .exec { session =>
      val key = session("createdKey").as[String]
      createdKeys.add(key)
      if (createdKeys.size() > 10000) createdKeys.remove(0) // bounded
      session
    }

  // 100 read users per 1 write user
  val redirectScenario = scenario("Read - Redirect")
    .feed(redirectFeeder)
    .exec(
      http("GET /{shortKey}")
        .get("/#{shortKey}")
        .check(status.in(302, 404))
    )

  setUp(
    writeScenario.inject(
      rampUsersPerSec(1).to(10).during(2.minutes),
      constantUsersPerSec(10).during(8.minutes)
    ).protocols(writeProtocol),

    redirectScenario.inject(
      rampUsersPerSec(10).to(200).during(2.minutes),
      constantUsersPerSec(200).during(8.minutes)
    ).protocols(redirectProtocol)
  ).assertions(
    global.responseTime.percentile(99).lte(300),
    global.successfulRequests.percent.gte(99.0)
  )
}
