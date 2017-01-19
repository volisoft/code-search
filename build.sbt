name := "cvs-search"

version := "1.0"

scalaVersion := "2.12.1"

val akkaVersion = "2.4.16"

resolvers += Resolver.bintrayRepo("cakesolutions", "maven")

//addSbtPlugin("com.typesafe.sbt" % "sbt-less" % "1.0.6")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % "10.0.1",
  "org.jsoup" % "jsoup" % "1.10.1",
  "com.github.javaparser" % "javaparser-core" % "2.5.1",
  "org.apache.qpid" % "qpid-broker" % "6.1.1",
  "com.lightbend.akka" %% "akka-stream-alpakka-amqp" % "0.4",
  "de.jflex" % "jflex" % "1.6.1",
  "com.cebglobal" % "xpresso" % "0.9.0"
)


    