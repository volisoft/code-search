package voli.web

import ro.pippo.core.Pippo

object main {
  def main(args: Array[String]): Unit = {
    new Pippo(new App()).start(4567)
  }

}
