import AssemblyKeys._ // Assembly plugin settings

assemblySettings

jarName in assembly := "transfero.jar"

name := "transfero"

version := "0.1"

scalaVersion := "2.10.2"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies += "com.typesafe.akka" % "akka-actor_2.10" % "2.2.0"

libraryDependencies +=  "org.scalatest" %% "scalatest" % "1.9.1" % "test"