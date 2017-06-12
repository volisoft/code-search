name := "cvs-search"

version := "1.0"

scalaVersion := "2.12.1"

val akkaVersion = "2.5.1"

resolvers += Resolver.bintrayRepo("cakesolutions", "maven")

//addSbtPlugin("com.typesafe.sbt" % "sbt-less" % "1.0.6")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % "10.0.6",
  "org.jsoup" % "jsoup" % "1.10.2",
  "org.apache.qpid" % "qpid-broker" % "6.1.2",
  "com.lightbend.akka" %% "akka-stream-alpakka-amqp" % "0.7",
  "com.cebglobal" % "xpresso" % "0.9.0",
  "ro.pippo" % "pippo-core" % "1.1.0",
  "ro.pippo" % "pippo-tomcat" % "1.1.0",
  "ro.pippo" % "pippo-freemarker" % "1.1.0",
  "ro.pippo" % "pippo-gson" % "1.1.0",
  "org.aeonbits.owner" % "owner" % "1.0.9",
  "com.rklaehn" %% "radixtree" % "0.5.0",
  "commons-io" % "commons-io" % "2.4",
  "org.scalatest" %% "scalatest" % "3.0.1" % "test"
)


    